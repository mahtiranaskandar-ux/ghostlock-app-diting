package com.sesame.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.sesame.app.BuildConfig
import com.sesame.app.data.profile.CpuPairView
import com.sesame.app.data.profile.PerDevicePhys
import com.sesame.app.data.profile.ProfileMerger
import com.sesame.app.data.profile.ProfileResolver
import com.sesame.app.data.route.RouteKind
import com.sesame.app.domain.model.AnchorGeometry
import com.sesame.app.domain.model.CpuPair
import com.sesame.app.domain.model.ExecutionFieldValue
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.ProfileDiffRow
import com.sesame.app.domain.model.ProfileFieldNode
import com.sesame.app.domain.model.ProfileLayers
import com.sesame.app.domain.model.physPairErrors
import com.sesame.app.domain.repository.ProfileConfigController

/**
 * Controller-centered profile architecture, measured-db model (2026-10-25),
 * amended by the import-copy model (2026-11-19).
 *
 * A profile is a **document**: a bundled asset, a hand-picked builtin asset, or
 * the app-private copy of the external file the user imported. The SD card is
 * the user's document store; app data holds exactly one copy
 * (`filesDir/profiles/imported_profile.conf`) and no persisted override layer.
 * Resolution order is external > builtin > bundled.
 *
 * The resolved document is the source itself: there is no sparse edit layer.
 * See `docs/analysis/measured-db-plan.md`.
 */
internal class AndroidProfileConfigController(
    context: Context,
    private val externalProfiles: ExternalProfileStore,
    private val preferences: SharedPreferences,
    private val buildIdentity: String = BuildConfig.BUILD_IDENTITY,
    /**
     * Primary device identity (`Build.DEVICE`) used to look up `device_defaults`
     * and the per-device physical maps. The measured route timing and the
     * bootloader physical layout are properties of this phone, not of the
     * kernel image; the profile carries null and the app resolves them here.
     * Injectable so the host tests can pin it.
     *
     * `Build.DEVICE` is not always unique: on MediaTek mainline builds it can
     * be the generic `missi` while the hardware codename is the vendor device
     * (`spinel`). [deviceAliases] carries those fallback identities, tried in
     * order after this one.
     */
    private val deviceCodename: String? = Build.DEVICE,
    private val deviceAliases: List<String> = DeviceIdentity.aliases(),
    /**
     * The SoC identity (`ro.soc.manufacturer` + `ro.soc.model`). Import
     * verification needs it because the physical pair requirement is a
     * platform fact: MediaTek has no compiled default for either half, while
     * Qualcomm resolves the offset from its DRAM-base default and only the
     * load must come from xbl_config/uefi. Injectable so host tests pin it.
     */
    private val socName: String = DeviceIdentity.socName(),
    /** Pins the source for an editor session; null follows the stored choice. */
    forcedSelection: ProfileSelection? = null,
) : ProfileConfigController {
    private val appContext = context.applicationContext
    private val assetLoader = AssetConfigLoader(appContext)
    private val lock = Any()
    private var forcedSelection: ProfileSelection? = forcedSelection
    private val deviceCandidates: List<String> =
        DeviceIdentity.candidates(deviceCodename, deviceAliases)

    override suspend fun load(release: String, pair: CpuPair): ProfileConfig {
        migrateLegacyExternalSelection(release)
        val resolution = resolve(release, pair)
        val full = resolution.profile
        if (full == null) {
            return ProfileConfig(
                release = release,
                hasProfile = false,
                pair = pair,
                layers = resolution.layers,
            )
        }
        val route = routeNameOf(full)
        val fallbackTo = fallbackTargetOf(full)
        val pipelineOrder = when (val raw = full["pipeline"]) {
            is String -> listOf(raw.trim().lowercase()).filter { it.isNotEmpty() }
            is List<*> -> raw.mapNotNull {
                (it as? String)?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            }
            else -> emptyList()
        }
        val invalidPaths = validateProfileFields(full, route, fallbackTo) +
            ProfileResolver.validateMerged(full, route, fallbackTo)
                .mapTo(mutableSetOf()) { it.fieldPath }
        /* Invalid fields missing from the resolved document still get a row,
         * otherwise the run stays blocked with no red field to fix. */
        materializeInvalidPaths(full, invalidPaths)
        /* The editor shows every field of the active route plus the shared
         * geometry, so a field the profile did not carry appears as an
         * editable `null` row instead of being invisible. */
        val complete = completeProfileFields(full, route, fallbackTo)
        val baseline = full
        val roots = buildTree(complete, "")
        /* The anchor trio lives in the active route's own branch; the native
         * writes only that section to the wire (NativeProfile: "only the
         * active route's route.* section is written"). */
        val anchor = route?.let { name ->
            full.getLongAt("route.$name.lock_anchor_image")?.let { image ->
                AnchorGeometry(
                    image = image,
                    bytes = full.getLongAt("route.$name.lock_anchor_bytes"),
                    stride = full.getLongAt("route.$name.lock_anchor_stride"),
                )
            }
        }
        return ProfileConfig(
            release = release,
            hasProfile = true,
            pair = pair,
            physLoad = full.getLongAt("kernel_phys_load"),
            physOffset = full.getLongAt("kernel_phys_offset"),
            anchor = anchor,
            layers = resolution.layers.copy(
                uncarriedKeys = uncarriedRouteKeys(full, route),
            ),
            roots = roots,
            general = generalFields(full, baseline, route, fallbackTo),
            route = route,
            fallbackTo = fallbackTo,
            pipelineOrder = pipelineOrder,
            invalidPaths = invalidPaths,
        )
    }

    /**
     * Kotlin-side geometry/credential validation. The profile declares its
     * route explicitly; fields that belong to other routes may stay absent.
     */
    private fun validateProfileFields(
        profile: ValueMap,
        explicitRoute: String?,
        fallbackTo: String?,
    ): Set<String> {
        val invalid = mutableSetOf<String>()
        fun value(path: String): Long? = profile.getLongAt(path)
        fun requireNonZero(vararg paths: String) {
            paths.forEach { path ->
                val current = value(path)
                if (current == null || current == 0L) invalid += path
            }
        }

        /* The route is profile-controlled: a missing or unknown branch is
         * invalid. Legacy documents get their route from the converter, so an
         * unresolved route here means the profile is genuinely broken. */
        val route = explicitRoute?.takeIf { it in ProfileConfig.Routes }
        if (route == null) invalid += "route"

        requireNonZero(*RouteCommonRequired.toTypedArray())
        val major = value("kernel_major")
        if (major != 5L && major != 6L) invalid += "kernel_major"

        val copySize = value("cred.copy_size")
        if (copySize == null || copySize == 0L) invalid += "cred.copy_size"
        /* Absent cred.usage_offset decodes as 0 and passes, exactly like native. */
        val usageOffset = value("cred.usage_offset") ?: 0L
        if (copySize != null && usageOffset + SizeofU32 > copySize) {
            invalid += "cred.usage_offset"
        }

        val capsCount = value("cred.caps_count")
        val capsOffset = value("cred.caps_offset")
        if (capsCount == null || capsCount == 0L) invalid += "cred.caps_count"
        if (copySize != null && capsCount != null && capsOffset != null &&
            capsOffset + capsCount * SizeofU64 > copySize
        ) {
            invalid += "cred.caps_offset"
            invalid += "cred.caps_count"
        }

        val refCount = value("cred.ref_count") ?: 0L
        if (refCount > 4) invalid += "cred.ref_count"
        for (index in 0 until 4) {
            if (index >= refCount) break
            val image = value("cred.ref${index}_image")
            if (image == null || image == 0L) invalid += "cred.ref${index}_image"
            val offset = value("cred.ref${index}_offset")
            if (copySize != null && offset != null && offset + SizeofU64 > copySize) {
                invalid += "cred.ref${index}_offset"
            }
        }

        val stride = value("kernelsnitch.mm_struct_sz")
        if (stride != null && (stride < MinSlabStride || stride > MaxSlabStride)) {
            invalid += "kernelsnitch.mm_struct_sz"
        }

        val routePrefix = route?.let { "route.$it" } ?: "route"
        val fallbackPrefix = fallbackTo?.takeIf { it != "none" }
            ?.let { "fallback.route.$it" }
        when (route) {
            "tcp_zerocopy" -> {
                val compact = value("$routePrefix.compact_waiter")
                if (compact == null || compact == 0L) {
                    invalid += "$routePrefix.compact_waiter"
                } else if (compact < 0L || compact > 0xffL) {
                    invalid += "$routePrefix.compact_waiter"
                }
            }

            "select_stack" -> {
                /* The AArch32 compat carrier places the stamp from the
                 * measured geometry (waiter_home / select_dst_depth), not from
                 * the 64-bit fd_set word shift; a compat profile legitimately
                 * omits waiter_shift and must state both depths instead. */
                val compat = value("$routePrefix.compat_carrier") ?: 0L
                if (compat != 0L) {
                    for (field in listOf("waiter_home", "select_dst_depth")) {
                        val current = value("$routePrefix.$field")
                        if (current == null || current <= 0L ||
                            current > UInt.MAX_VALUE.toLong()
                        ) {
                            invalid += "$routePrefix.$field"
                        }
                    }
                    /* Optional: the derived ABI (0 time32, 1 time64). Absent
                     * keeps the native's legacy home table. */
                    val abi = value("$routePrefix.compat_futex_abi")
                    if (abi != null && abi !in 0L..1L) {
                        invalid += "$routePrefix.compat_futex_abi"
                    }
                } else {
                    val shift = value("$routePrefix.waiter_shift")
                    if (shift == null ||
                        shift < Int.MIN_VALUE.toLong() || shift > Int.MAX_VALUE.toLong()
                    ) {
                        invalid += "$routePrefix.waiter_shift"
                    }
                }
            }

            "multicast_waiter" -> invalid += validateMulticastGeometry(routePrefix, ::value)
        }
        if (fallbackTo != null && fallbackTo != "none" && fallbackTo !in ProfileConfig.Routes) {
            invalid += "fallback.to"
        } else if (fallbackPrefix != null) {
            when (fallbackTo) {
                "tcp_zerocopy" -> {
                    val compact = value("$fallbackPrefix.compact_waiter")
                    if (compact == null || compact == 0L) {
                        invalid += "$fallbackPrefix.compact_waiter"
                    } else if (compact < 0L || compact > 0xffL) {
                        invalid += "$fallbackPrefix.compact_waiter"
                    }
                }

                "select_stack" -> {
                    val shift = value("$fallbackPrefix.waiter_shift")
                    if (shift == null ||
                        shift < Int.MIN_VALUE.toLong() || shift > Int.MAX_VALUE.toLong()
                    ) {
                        invalid += "$fallbackPrefix.waiter_shift"
                    }
                }

                "multicast_waiter" -> invalid += validateMulticastGeometry(fallbackPrefix, ::value)
            }
        }
        return invalid
    }

    /**
     * `route.multicast_waiter`'s geometry, in either carrier form. The 64-bit
     * form (`waiter_off`/`buffer_size`) is the 5.x-6.1 `group_source_req`
     * copy and carries the 5.x cred reference slots; the compat form
     * (`compat_carrier` + the three `compat_*` keys, docs/analysis/
     * compat-multicast-carrier-plan.md) is the armv7 helper's 32-bit copy at
     * the extractor's derived depth. A compat profile states that geometry
     * instead and, on 6.x, legitimately omits the 5.x cred slots (the native
     * treats absent references as zero and uses `usage_size`/`uid_offset`).
     */
    private fun validateMulticastGeometry(
        prefix: String,
        value: (String) -> Long?,
    ): Set<String> {
        val invalid = mutableSetOf<String>()
        val compat = value("$prefix.compat_carrier") ?: 0L
        for (field in listOf("task_offset", "lock_offset")) {
            val current = value("$prefix.$field")
            if (current == null || current == 0L) {
                invalid += "$prefix.$field"
            } else if (current < 0L || current > UInt.MAX_VALUE.toLong()) {
                invalid += "$prefix.$field"
            }
        }
        val compact = value("$prefix.compact_waiter")
        if (compact == null || compact == 0L) {
            invalid += "$prefix.compact_waiter"
        } else if (compact < 0L || compact > 0xffL) {
            invalid += "$prefix.compact_waiter"
        }
        if (value("offset.empty_zero_page") == null || value("offset.empty_zero_page") == 0L) {
            invalid += "offset.empty_zero_page"
        }
        val lockOffset = value("$prefix.lock_offset")
        if (compat != 0L) {
            val compatOff = value("$prefix.compat_waiter_off")
            if (compatOff == null || compatOff <= 0L || compatOff > Int.MAX_VALUE.toLong()) {
                invalid += "$prefix.compat_waiter_off"
            }
            val compatBuffer = value("$prefix.compat_buffer_size")
            if (compatBuffer == null || compatBuffer <= 0L ||
                compatBuffer > UInt.MAX_VALUE.toLong()
            ) {
                invalid += "$prefix.compat_buffer_size"
            }
            val compatAbi = value("$prefix.compat_futex_abi")
            if (compatAbi == null || compatAbi < 0L || compatAbi > 1L) {
                invalid += "$prefix.compat_futex_abi"
            }
            if (compatOff != null && compatOff > 0L && compatOff <= Int.MAX_VALUE.toLong() &&
                compatBuffer != null && compatBuffer in 0L..UInt.MAX_VALUE.toLong() &&
                lockOffset != null && lockOffset in 0L..UInt.MAX_VALUE.toLong() &&
                compatOff + lockOffset + SizeofU64 > compatBuffer
            ) {
                invalid += "$prefix.compat_waiter_off"
                invalid += "$prefix.lock_offset"
                invalid += "$prefix.compat_buffer_size"
            }
            return invalid
        }
        for (field in RouteMulticastFields) {
            val current = value("$prefix.$field")
            if (current == null || current == 0L) {
                invalid += "$prefix.$field"
            } else if (current < 0L || current > UInt.MAX_VALUE.toLong()) {
                invalid += "$prefix.$field"
            }
        }
        if (value("cred.ref_count") == null || value("cred.ref_count") == 0L) {
            invalid += "cred.ref_count"
        }
        val copySize = value("cred.copy_size")
        if (copySize != null && copySize < 0xa0L) invalid += "cred.copy_size"
        val waiterOff = value("$prefix.waiter_off")
        if (waiterOff == null || waiterOff <= 0L || waiterOff > Int.MAX_VALUE.toLong()) {
            invalid += "$prefix.waiter_off"
        }
        val bufferSize = value("$prefix.buffer_size")
        if (waiterOff != null && waiterOff > 0L && waiterOff <= Int.MAX_VALUE.toLong() &&
            bufferSize != null && bufferSize in 0L..UInt.MAX_VALUE.toLong() &&
            lockOffset != null && lockOffset in 0L..UInt.MAX_VALUE.toLong() &&
            waiterOff + lockOffset + SizeofU64 > bufferSize
        ) {
            invalid += "$prefix.waiter_off"
            invalid += "$prefix.lock_offset"
            invalid += "$prefix.buffer_size"
        }
        return invalid
    }

    /** The single branch key declared under "route" (string legacy allowed). */
    private fun routeNameOf(profile: ValueMap): String? {
        return when (val value = profile["route"]) {
            is String -> value.takeIf { it.isNotEmpty() && it != "null" }
            is Map<*, *> -> value.keys.filterIsInstance<String>()
                .firstOrNull { it in ProfileConfig.Routes }
            else -> null
        }
    }

    private fun fallbackTargetOf(profile: ValueMap): String? {
        profile["fallback"].asValueMap()?.let { fallback ->
            val to = fallback["to"] as? String ?: ""
            if (to.isNotEmpty() && to != "null") return to
        }
        /* Legacy flat spelling from transition builds. */
        return (profile["fallback_to"] as? String)
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * Renders the resolved profile as the document an export writes. The
     * caller adds the lineage header and the user picks the destination; this
     * class never writes a profile into app data.
     */
    override fun renderResolved(release: String, pair: CpuPair): String? =
        renderMerged(release, pair, emptyMap())

    /**
     * [extraValues] are facts applied to the resolved document — the measured
     * database's merge path. They are validated with everything else, so a bad
     * fact cannot produce an exportable profile.
     */
    fun renderMerged(
        release: String,
        pair: CpuPair,
        extraValues: Map<String, Long>,
    ): String? {
        /* Keep the selected document's schema-v2 physical maps for the export:
         * the resolved view carries the running device's scalar pair, but the
         * other devices' entries must survive and an edited value is written
         * into the running device's entry. */
        val selection = currentSelection(release)
        val source = selection?.let { runCatching { loadSource(release, it).document }.getOrNull() }
        val resolved = resolve(release, pair).profile ?: return null
        val view = resolved.copyValue().asValueMap() ?: return null
        for ((path, value) in extraValues) view.setValueAt(path, value)
        val route = routeNameOf(view)
        val fallbackTo = fallbackTargetOf(view)
        val invalid = validateProfileFields(view, route, fallbackTo) +
            ProfileResolver.validateMerged(view, route, fallbackTo).map { it.fieldPath }
        if (invalid.isNotEmpty()) return null
        view["schema_version"] = 1
        view["release"] = release
        /* The CPU choice follows the device pair, it must not be frozen here. */
        view["execution"].asValueMap()?.remove("selected_cpus")
        fillRouteExecutionDefaults(view)
        trimRouteTuning(view)
        if (source != null) injectPhysMaps(view, source)
        if (view["kernel_phys_load"] !is Map<*, *> && view["kernel_phys_offset"] !is Map<*, *>) {
            view["schema_version"] = 1
        }
        return HoconSupport.render(view)
    }

    /**
     * Restore the source's per-device physical maps into an export. The
     * running device's entry takes the resolved value (or the source's own
     * value); every other device key is preserved. A source
     * field that is null stays null (the native default), and an edited value
     * for a device the source did not carry adds its key.
     */
    private fun injectPhysMaps(view: ValueMap, source: ValueMap) {
        val loadMap = source["kernel_phys_load"] as? Map<*, *>
        val offsetMap = source["kernel_phys_offset"] as? Map<*, *>
        if (loadMap == null && offsetMap == null) return
        val matched = runCatching {
            PerDevicePhys.resolveFor(source, deviceCandidates).device
        }.getOrNull()
        val key = matched ?: deviceCodename ?: deviceCandidates.firstOrNull()
        view["schema_version"] = 2
        view["kernel_phys_load"] = physMap(loadMap, key, view.getLongAt("kernel_phys_load"))
        view["kernel_phys_offset"] = physMap(offsetMap, key, view.getLongAt("kernel_phys_offset"))
    }

    private fun physMap(sourceMap: Map<*, *>?, device: String?, value: Long?): Any? {
        if (sourceMap == null) return null
        val out = linkedMapOf<String, Any?>()
        for ((rawName, rawValue) in sourceMap) {
            val name = rawName as? String ?: continue
            out[name] = rawValue
        }
        if (device != null) {
            val existing = out.keys.firstOrNull { it.equals(device, ignoreCase = true) }
            if (value != null) {
                out[existing ?: device] = value
            } else if (existing != null) {
                out.remove(existing)
            }
        }
        return out
    }

    /** Renders the resolved profile verbatim (no trimming) for debug dumps. */
    override fun renderResolvedForDebug(release: String, pair: CpuPair): String? {
        val resolved = resolve(release, pair).profile ?: return null
        val view = resolved.copyValue().asValueMap() ?: return null
        return HoconSupport.render(view)
    }

    /** Numeric leaves of the resolved profile, the measured merge's comparison. */
    fun resolvedNumbers(release: String, pair: CpuPair): Map<String, Long>? {
        val resolved = resolve(release, pair).profile ?: return null
        return numericLeaves(resolved)
    }

    /** Values of [release]'s active profile that differ from the bundle. */
    override fun diffAgainstBundle(release: String, pair: CpuPair): List<ProfileDiffRow> {
        val current = resolve(release, pair).profile ?: return emptyList()
        val bundled = resolveFor(
            release, pair, ProfileSelection.Bundled(release),
        ).profile ?: return emptyList()
        return numericDiff(current, bundled)
    }

    /** Moves the session's base to the bundled document. */
    override suspend fun rebaseToBundle(release: String, pair: CpuPair): ProfileConfig {
        if (forcedSelection != null) forcedSelection = ProfileSelection.Bundled(release)
        return load(release, pair)
    }

    /** The selection a resolution of [release] would use right now. */
    fun currentSelection(release: String): ProfileSelection? =
        forcedSelection ?: liveSelection(release)

    /** Human label of the source, for the editor banner and export name. */
    fun currentSelectionLabel(release: String): String? = when (val source = currentSelection(release)) {
        is ProfileSelection.External -> ImportedProfileCopy.Label
        is ProfileSelection.Builtin -> source.release
        is ProfileSelection.Bundled -> source.release
        null -> null
    }

    /**
     * Builds the native document from an in-memory profile text (the generator's
     * output before it is exported). Nothing is stored: the probe needs the
     * document, not a saved copy.
     */
    fun nativeDocumentFor(text: String, release: String, pair: CpuPair): ByteArray? {
        val entry = ProfileDocumentParser.entryForRelease(text, release, ::loadBundledInclude)
            ?: return null
        val merged = runCatching { merge(release, pair, entry) }.getOrNull() ?: return null
        val route = routeNameOf(merged)
        val fallbackTo = fallbackTargetOf(merged)
        val invalid = validateProfileFields(merged, route, fallbackTo) +
            ProfileResolver.validateMerged(merged, route, fallbackTo).map { it.fieldPath }
        if (invalid.isNotEmpty()) return null
        return buildNativeDocument(release, merged)?.toBinary()
    }

    override suspend fun builtinReleases(): List<String> {
        val index = readIndex() ?: return emptyList()
        val profiles = index["profiles"].asValueList() ?: return emptyList()
        return profiles
            .mapNotNull { entry -> (entry.asValueMap()?.get("release") as? String) }
            .filter { it.isNotEmpty() }
            .sorted()
    }

    override fun activeBuiltinRelease(): String? {
        forcedSelection?.let { return (it as? ProfileSelection.Builtin)?.release }
        return preferences.getString(PrefBuiltinRelease, null)?.takeIf { it.isNotEmpty() }
    }

    override fun importedProfileExists(): Boolean = externalProfiles.importedProfileExists()

    /**
     * Moves the pre-import-copy selection (`external_profile_*` preferences)
     * into the app-private copy once. A readable URI for the running release
     * is copied; the old keys are deleted either way. When a copy already
     * exists it wins and the keys are deleted without a read (the crash window
     * between the copy write and the key delete).
     */
    override fun migrateLegacyExternalSelection(deviceRelease: String) {
        if (!LegacyExternalKeys.any(preferences::contains)) return
        if (!externalProfiles.importedProfileExists()) {
            val uri = preferences.getString(PrefExternalUri, null)?.takeIf { it.isNotEmpty() }
            if (uri != null) {
                val text = externalProfiles.read(uri)
                if (text != null &&
                    ProfileDocumentParser.entryForRelease(
                        text, deviceRelease, ::loadBundledInclude,
                    ) != null
                ) {
                    externalProfiles.writeImportedProfile(text)
                }
            }
        }
        clearLegacyExternalPreferences()
    }

    private fun clearLegacyExternalPreferences() {
        preferences.edit { LegacyExternalKeys.forEach { remove(it) } }
    }

    /**
     * Selects [uri] as the external profile for [deviceRelease]. Transactional:
     * the previous copy is restored when the candidate fails verification, so a
     * rejected import never becomes the selection. The selection wins over the
     * bundled asset; a hand-picked builtin is cleared because the last explicit
     * choice wins.
     */
    override suspend fun selectExternalProfile(
        uri: String,
        deviceRelease: String,
        pair: CpuPair,
    ): ProfileImportResult {
        val text = externalProfiles.read(uri)
            ?: return ProfileImportResult.Rejected(listOf("the file cannot be read"))
        ProfileDocumentParser.entryForRelease(text, deviceRelease, ::loadBundledInclude)
            ?: return ProfileImportResult.Rejected(
                listOf("the file carries no profile for $deviceRelease"),
            )
        val previous = externalProfiles.readImportedProfile()
        if (!externalProfiles.writeImportedProfile(text)) {
            return ProfileImportResult.Rejected(listOf("the profile copy could not be written"))
        }
        clearLegacyExternalPreferences()
        preferences.edit { remove(PrefBuiltinRelease) }
        forcedSelection = null
        val config = runCatching { load(deviceRelease, pair) }.getOrNull()
        val errors = importErrors(config)
        if (errors.isNotEmpty() || config == null) {
            /* Restore the previous selection; a rejected import changes nothing. */
            if (previous != null) {
                externalProfiles.writeImportedProfile(previous)
            } else {
                externalProfiles.clearImportedProfile()
            }
            return ProfileImportResult.Rejected(errors)
        }
        return ProfileImportResult.Accepted(config)
    }

    /** Why a resolved candidate cannot be selected; empty means it can. */
    private fun importErrors(config: ProfileConfig?): List<String> {
        if (config == null) return listOf("the profile could not be resolved")
        val errors = mutableListOf<String>()
        if (!config.hasProfile) errors += "no profile resolved for this kernel"
        config.layers?.externalError?.let { errors += it }
        if (config.layers?.externalReadFailure == true) {
            errors += "the profile copy cannot be read"
        }
        config.invalidPaths.sorted().forEach { errors += "$it: invalid" }
        /* Only a layout the runtime cannot resolve is a rejection: on MediaTek
         * both halves are required, elsewhere the compiled default fills a
         * null (the bundled profiles for those devices carry nulls). */
        errors += physPairErrors(
            socName,
            loadPresent = config.physLoad != null,
            offsetPresent = config.physOffset != null,
        )
        return errors
    }

    override suspend fun clearExternalProfile(deviceRelease: String, pair: CpuPair): ProfileConfig {
        externalProfiles.clearImportedProfile()
        clearLegacyExternalPreferences()
        forcedSelection = null
        return load(deviceRelease, pair)
    }

    override suspend fun selectBuiltin(
        release: String?,
        deviceRelease: String,
        pair: CpuPair,
    ): ProfileConfig {
        externalProfiles.clearImportedProfile()
        clearLegacyExternalPreferences()
        preferences.edit {
            if (release.isNullOrBlank()) remove(PrefBuiltinRelease)
            else putString(PrefBuiltinRelease, release)
        }
        forcedSelection = null
        return load(deviceRelease, pair)
    }

    /**
     * Resolved on demand: the result depends on the selection and the CPU pair,
     * so a cached document would go stale.
     */
    override fun nativeDocument(config: ProfileConfig, pipeline: String?): ByteArray? = synchronized(lock) {
        val resolved = resolve(config.release, config.pair).profile
            ?: return@synchronized null
        buildNativeDocument(config.release, resolved, pipeline)?.toBinary()
    }

    /** Builds the single resolved authority native consumes at run time. */
    private fun buildNativeDocument(
        release: String,
        profile: ValueMap,
        /** Only a run has a resolved pipeline; previews and schema checks omit it. */
        pipeline: String? = null,
    ): Profile? {
        val route = routeNameOf(profile)
        val fallbackTo = fallbackTargetOf(profile)
        return Profile.fromValueMap(
            release = release,
            route = RouteKind.fromToken(route),
            fallbackTo = RouteKind.fromToken(fallbackTo),
            pipeline = pipeline,
        ) { path -> ProfileResolver.nativeValue(profile, route, fallbackTo, path) }
    }

    // ---- resolution ----

    private data class Source(
        val document: ValueMap?,
        val error: String?,
        /** The selected document itself could not be read (not a content miss). */
        val readFailure: Boolean = false,
    )

    private data class Resolution(
        val profile: ValueMap?,
        val layers: ProfileLayers,
        val selection: ProfileSelection?,
    )

    /** External > builtin > bundled. A stored external selection is the user's
     * statement: it stays the source even when its release names another image,
     * and the identity check then refuses instead of silently using the bundle. */
    private fun liveSelection(release: String): ProfileSelection? {
        val external = storedExternalSelection()
        if (external != null) return external
        val builtin = preferences.getString(PrefBuiltinRelease, null)?.takeIf { it.isNotEmpty() }
        if (builtin != null) return ProfileSelection.Builtin(builtin)
        return if (bundledDocument(release) != null) ProfileSelection.Bundled(release) else null
    }

    private fun storedExternalSelection(): ProfileSelection.External? =
        if (externalProfiles.importedProfileExists()) ProfileSelection.External else null

    private fun resolve(release: String, pair: CpuPair): Resolution =
        resolveFor(release, pair, forcedSelection ?: liveSelection(release))

    private fun resolveFor(
        release: String,
        pair: CpuPair,
        selection: ProfileSelection?,
    ): Resolution {
        val source = loadSource(release, selection)
        val document = source.document
            ?: return Resolution(null, layersFor(selection, source.error, source.readFailure), selection)
        val merged = runCatching { merge(release, pair, document) }
        val layers = layersFor(selection, source.error ?: merged.exceptionOrNull()?.message)
        return Resolution(merged.getOrNull(), layers, selection)
    }

    private fun merge(
        release: String,
        pair: CpuPair,
        document: ValueMap,
    ): ValueMap {
        /* A controller without any device identity (test/exporter callers) is
         * device-neutral: the maps resolve to the native default, as the
         * exporter's own .bin does. The app always has an identity, and a map
         * that does not name it is refused by PerDevicePhys. */
        val resolvedDocument = if (deviceCandidates.isEmpty()) {
            PerDevicePhys.resolveNeutral(document)
        } else {
            PerDevicePhys.resolveFor(document, deviceCandidates).document
        }
        val tuningExecution = readExecutionTuning()
        val routePresets = ProfileConfig.Routes
            .mapNotNull { route -> readExecutionRoute(route)?.let { route to it } }
            .toMap()
        return ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = resolvedDocument,
            imported = null,
            tuningExecution = tuningExecution,
            pair = CpuPairView(pair.primary, pair.consumer),
            routePresets = routePresets,
        )
    }

    /** Reads the selected source. An external read failure is an error, never
     * a silent fallback to the bundle: the user's selection is a statement. */
    private fun loadSource(release: String, selection: ProfileSelection?): Source {
        if (selection == null) return Source(null, null)
        return when (selection) {
            is ProfileSelection.Bundled -> {
                val document = bundledDocument(selection.release)
                Source(
                    document,
                    if (document == null) "bundled profile ${selection.release} is unavailable" else null,
                )
            }

            is ProfileSelection.Builtin -> {
                val document = bundledDocument(selection.release)
                Source(
                    document,
                    if (document == null) "built-in profile ${selection.release} is unavailable" else null,
                )
            }

            is ProfileSelection.External -> {
                val raw = externalProfiles.readImportedProfile()
                    ?: return Source(null, "cannot read the imported profile", readFailure = true)
                val text = ImportedProfileSchema.ensureSchema(raw, ::loadBundledInclude)
                if (text != raw) externalProfiles.writeImportedProfile(text)
                val entries = ProfileDocumentParser.entries(text, ::loadBundledInclude)
                    ?: return Source(null, "cannot parse the imported profile", readFailure = true)
                val document = entries.firstOrNull { it["release"] == release }
                Source(
                    document,
                    if (document == null) "the imported profile has no entry for $release" else null,
                )
            }
        }
    }

    private fun loadBundledInclude(path: String): String? =
        readAsset("$BuiltinDirectory/$path").takeIf { it.isNotBlank() }

    private fun bundledDocument(release: String): ValueMap? = runCatching {
        val index = readIndex() ?: return null
        require((index["schema_version"] as? Number)?.toInt() == 1) {
            "unsupported profile schema"
        }
        val entry = findProfile(index["profiles"].asValueList(), release) ?: return null
        val path = (entry["file"] as? String).orEmpty()
        val document = HoconSupport.parseValue(readAsset("$BuiltinDirectory/$path")).asValueMap()
            ?: error("profile is not an object")
        val documentSchema = (document["schema_version"] as? Number)?.toInt()
        require(documentSchema == 1 || documentSchema == 2) {
            "unsupported profile schema"
        }
        require(document["release"] == entry["release"]) {
            "profile index release mismatch"
        }
        document
    }.getOrNull()

    private fun layersFor(
        selection: ProfileSelection?,
        error: String?,
        readFailure: Boolean = false,
    ): ProfileLayers {
        val external = selection is ProfileSelection.External
        val builtinRelease = when (selection) {
            is ProfileSelection.Bundled -> selection.release
            is ProfileSelection.Builtin -> selection.release
            else -> null
        }
        return ProfileLayers(
            builtinRelease = builtinRelease,
            bundledAuthoritative = selection is ProfileSelection.Bundled,
            builtinSelected = selection is ProfileSelection.Builtin,
            build = buildIdentity,
            externalApplied = external && error == null,
            externalError = error,
            externalReadFailure = readFailure,
        )
    }

    /**
     * Keys the resolved profile states in its **active route branch** that no
     * wire section declares: settings the profile asks for and the transport
     * cannot deliver. This is the check that would have caught the lock anchor
     * vanishing between the resolved document and the wire. The fallback branch
     * is excluded on purpose — its geometry belongs to another route's section
     * and never crosses the transport either way.
     */
    private fun uncarriedRouteKeys(profile: ValueMap, route: String?): List<String> {
        val release = profile["release"] as? String ?: return emptyList()
        val known = buildNativeDocument(release, profile)?.document?.wireKeys() ?: return emptyList()
        val branch = route?.let { name -> profile["route"].asValueMap()?.get(name).asValueMap() }
            ?: return emptyList()
        return branch.keys.filterIsInstance<String>().filterNot { it in known }.sorted()
    }

    /**
     * Native decodes one complete `execution.routes` object, while profiles only
     * include the route they use (plus their fallback). Missing groups are
     * filled from the shared `execution-<route>.conf` files.
     */
    private fun fillRouteExecutionDefaults(profile: ValueMap) {
        val presets = ProfileConfig.Routes
            .mapNotNull { route -> readExecutionRoute(route)?.let { route to it } }
            .toMap()
        ProfileMerger.fillRouteExecutionDefaults(profile, presets)
    }

    private fun readExecutionRoute(route: String): ValueMap? = runCatching {
        HoconSupport.parseValue(
            readAsset("$BuiltinDirectory/execution-${route.replace('_', '-')}.conf"),
        ).asValueMap()?.get("execution").asValueMap()?.get("routes").asValueMap()?.get(route).asValueMap()
    }.getOrNull()

    /** The exported document keeps only the selected route's tuning (+ fallback). */
    private fun trimRouteTuning(profile: ValueMap) {
        val routes = profile["execution"].asValueMap()?.get("routes").asValueMap() ?: return
        val keep = buildSet {
            routeNameOf(profile)?.let(::add)
            fallbackTargetOf(profile)?.takeIf { it != "none" }?.let(::add)
        }
        routes.keys.toList().filter { it !in keep }.forEach(routes::remove)
    }

    private fun readIndex(): ValueMap? = runCatching {
        HoconSupport.parseValue(readAsset("$BuiltinDirectory/index.conf")).asValueMap()
            ?: error("index.conf is not an object")
    }.getOrNull()

    private fun findProfile(profiles: List<*>?, release: String): ValueMap? =
        profiles.orEmpty().asSequence()
            .mapNotNull { it.asValueMap() }
            .firstOrNull { it["release"] == release }

    private fun readAsset(path: String): String = assetLoader.load(path)

    /**
     * Shared execution tuning every profile includes ("execution-tuning.conf"),
     * with the device preset merged under it on a diting. The preset exists
     * because the extractor no longer emits `execution`: a generated profile
     * must still run with the measured 30/10000/5000 values rather than the
     * shared 1000/50000/50000 defaults.
     */
    internal fun readExecutionTuning(): ValueMap? = runCatching {
        val common = HoconSupport.parseValue(readAsset("$BuiltinDirectory/execution-tuning.conf"))
            .asValueMap()?.get("execution").asValueMap()
        val devicePreset = readDeviceExecutionDefaults()
        when {
            common == null -> devicePreset
            devicePreset == null -> common
            else -> deepMergeValues(common, devicePreset)
        }
    }.getOrNull()

    /**
     * The measured route timing belongs to this phone, not to a kernel. The
     * table is data keyed by the device identities (Build.DEVICE first, then
     * the vendor/boot-image aliases); a device without an entry gets the
     * generic tuning from execution-tuning.conf.
     */
    private fun readDeviceExecutionDefaults(): ValueMap? = runCatching {
        val entries = HoconSupport.parseValue(readAsset("$BuiltinDirectory/device_defaults.conf"))
            .asValueMap()?.get("device_defaults").asValueMap() ?: return null
        val entry = deviceCandidates.asSequence()
            .mapNotNull { device ->
                entries.entries.firstOrNull { it.key.equals(device, ignoreCase = true) }
            }
            .firstOrNull() ?: return null
        entry.value.asValueMap()?.get("execution").asValueMap()
    }.getOrNull()

    /** Creates null placeholders for invalid fields the document does not carry. */
    private fun materializeInvalidPaths(profile: ValueMap, invalidPaths: Set<String>) {
        invalidPaths.forEach { path ->
            val segments = path.split('.')
            var node: ValueMap = profile
            for (index in 0 until segments.size - 1) {
                node = node.mutableChild(segments[index])
            }
            if (!node.containsKey(segments.last())) node[segments.last()] = null
        }
    }

    // ---- controller model helpers ----

    private fun generalFields(
        profile: ValueMap,
        baseline: ValueMap,
        route: String?,
        fallbackTo: String?,
    ): List<ExecutionFieldValue> {
        fun read(root: ValueMap, path: String): Long? = if (path.startsWith("execution.")) {
            root["execution"].asValueMap()?.getLongAt(path.removePrefix("execution."))
        } else {
            root.getLongAt(path)
        }
        /* Route tuning is appended from the resolved document itself: only the
         * active route's leaves (sorted, so the order is stable), plus the
         * declared fallback's, so the editor never offers another route's
         * knobs. Both route groups are filled into `execution.routes` during
         * resolution, so the keys come straight from the HOCON. */
        val paths = ProfileConfig.GeneralPaths +
            routeTuningPaths(profile, route) +
            routeTuningPaths(profile, fallbackTo?.takeIf { it != "none" && it != route })
        return paths.map { path ->
            val value = read(profile, path) ?: 0L
            ExecutionFieldValue(
                path = path,
                value = value,
                overridden = read(baseline, path)?.let { baselineValue -> value != baselineValue } == true,
            )
        }
    }

    private fun routeTuningPaths(profile: ValueMap, route: String?): List<String> {
        if (route == null || route !in ProfileConfig.Routes) return emptyList()
        val group = profile["execution"].asValueMap()?.get("routes").asValueMap()
            ?.get(route).asValueMap() ?: return emptyList()
        return group.keys.filterIsInstance<String>().sorted()
            .map { "execution.routes.$route.$it" }
    }

    /**
     * Fills every field of the shared geometry and the active route with an
     * explicit `null` when the resolved profile omitted it, so the advanced
     * editor always presents the complete editable surface.
     */
    private fun completeProfileFields(
        profile: ValueMap,
        route: String?,
        fallbackTo: String?,
    ): ValueMap {
        val out = profile.copyValue().asValueMap() ?: return profile
        if (!out.containsKey("kernel_phys_load")) out["kernel_phys_load"] = null
        if (!out.containsKey("kernel_phys_offset")) out["kernel_phys_offset"] = null
        completeSection(out, "task_struct", TaskStructFieldNames)
        completeSection(out, "cred", CredFieldNames)
        completeSection(out, "offset", OffsetFieldNames)
        completeSection(out, "kernelsnitch", KernelsnitchFieldNames)
        route?.let { completeRouteBranch(out["route"].asValueMap(), it) }
        if (fallbackTo != null && fallbackTo != "none") {
            completeRouteBranch(
                out["fallback"].asValueMap()?.get("route").asValueMap(),
                fallbackTo,
            )
        }
        return out
    }

    private fun completeSection(out: ValueMap, section: String, fields: List<String>) {
        val target = out[section].asValueMap() ?: valueMapOf().also { out[section] = it }
        for (field in fields) if (!target.containsKey(field)) target[field] = null
    }

    private fun completeRouteBranch(container: ValueMap?, route: String) {
        val fields = RouteBranchFields[route] ?: return
        val containerMap = container ?: return
        val branch = containerMap[route].asValueMap()
            ?: valueMapOf().also { containerMap[route] = it }
        for (field in fields) if (!branch.containsKey(field)) branch[field] = null
    }

    private fun buildTree(
        node: ValueMap,
        prefix: String,
    ): List<ProfileFieldNode> {
        val groups = mutableListOf<ProfileFieldNode>()
        val leaves = mutableListOf<ProfileFieldNode>()
        for ((key, value) in node) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                value is Map<*, *> -> {
                    /* Execution tuning is edited on the general page
                     * (ProfileOverrideScreen / ExecutionEditor), not here. */
                    if (key == "execution") continue
                    val children = buildTree(value.asValueMap() ?: valueMapOf(), path)
                    if (children.isNotEmpty()) {
                        groups += ProfileFieldNode(
                            path = path,
                            name = key,
                            children = children,
                        )
                    }
                }

                value is Number -> if (path != "schema_version" && path != "release" &&
                    path != "recommend_shizuku"
                ) {
                    leaves += ProfileFieldNode(
                        path = path,
                        name = key,
                        value = value.toLong(),
                    )
                }

                value == null -> if (path != "schema_version" && path != "release" &&
                    path != "recommend_shizuku"
                ) {
                    leaves += ProfileFieldNode(
                        path = path,
                        name = key,
                        value = null,
                    )
                }
            }
        }
        return groups.sortedBy { it.name } + leaves.sortedBy { it.name }
    }

    // ---- diff helpers ----

    private fun numericDiff(current: ValueMap, bundled: ValueMap): List<ProfileDiffRow> {
        val left = numericLeaves(current)
        val right = numericLeaves(bundled)
        return (left.keys + right.keys).sorted().mapNotNull { path ->
            if (path == "schema_version" || path == "release" || path == "recommend_shizuku") {
                return@mapNotNull null
            }
            val currentValue = left[path]
            val bundledValue = right[path]
            if (currentValue == bundledValue) null
            else ProfileDiffRow(path, currentValue, bundledValue)
        }
    }

    private fun numericLeaves(root: ValueMap): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        fun walk(prefix: String, node: Map<*, *>) {
            for ((rawKey, value) in node) {
                val key = rawKey.toString()
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is Map<*, *> -> walk(path, value)
                    is Number -> out[path] = value.toLong()
                }
            }
        }
        walk("", root)
        return out
    }

    // ---- persistence ----

    internal companion object {
        private const val BuiltinDirectory = "kernel_profiles"
        private val RouteCommonRequired = listOf(
            "offset.init_task", "offset.init_cred", "offset.root_task_group", "offset.selinux_enforcing",
            "task_struct.prio", "task_struct.pi_lock", "task_struct.pi_waiters", "task_struct.pi_blocked_on",
            "task_struct.cred", "task_struct.seccomp",
        )
        /** Full shared-geometry field universes (native `kTask`/`kCred`/`kOffset`). */
        private val TaskStructFieldNames = listOf(
            "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters", "pi_top_task",
            "pi_blocked_on", "pid", "tgid", "atomic_flags", "real_cred", "cred", "comm", "tasks",
            "seccomp",
        )
        private val CredFieldNames = listOf(
            "copy_size", "usage_offset", "usage_value", "caps_offset", "caps_count", "caps_value",
            "ref_count", "ref0_offset", "ref1_offset", "ref2_offset", "ref3_offset",
            "ref0_image", "ref1_image", "ref2_image", "ref3_image",
        )
        private val OffsetFieldNames = listOf(
            "init_task", "init_cred", "empty_zero_page", "root_task_group", "selinux_enforcing",
            "selinux_blob_sizes", "security_hook_heads", "slide_nfulnl_logger", "slide_loggers_0_1",
            "slide_boot_id",
        )
        private val KernelsnitchFieldNames = listOf("collisions", "mm_struct_sz")

        /** Fields each route branch carries, used to seed a switched-to route. */
        private val RouteBranchFields = mapOf(
            "tcp_zerocopy" to listOf("compact_waiter"),
            "select_stack" to listOf("waiter_shift"),
            "multicast_waiter" to listOf(
                "waiter_off", "buffer_size", "task_offset", "lock_offset",
                "compact_waiter",
            ),
        )
        private val RouteMulticastFields = listOf(
            "buffer_size", "task_offset", "lock_offset",
        )
        private const val SizeofU32 = 4L
        private const val SizeofU64 = 8L

        /** Native's spray clamp (`MM_STRUCT_SZ` sanity range in `main.cpp`). */
        private const val MinSlabStride = 64L
        private const val MaxSlabStride = 32768L

        const val PrefBuiltinRelease = "debug_builtin_release"
        const val PrefExternalUri = "external_profile_uri"
        const val PrefExternalName = "external_profile_name"
        const val PrefExternalRelease = "external_profile_release"
        const val PrefExternalSha256 = "external_profile_sha256"
        const val PrefExternalParent = "external_profile_parent"
        const val PrefExternalParentSha256 = "external_profile_parent_sha256"

        /** Pre-import-copy selection keys; read only by the one-time migration. */
        private val LegacyExternalKeys = listOf(
            PrefExternalUri, PrefExternalName, PrefExternalRelease,
            PrefExternalSha256, PrefExternalParent, PrefExternalParentSha256,
        )
    }
}

/** One resolution source, in precedence order (external > builtin > bundled). */
internal sealed interface ProfileSelection {
    data class Bundled(val release: String) : ProfileSelection
    data class Builtin(val release: String) : ProfileSelection
    /** The app-private imported copy; its presence is the whole selection. */
    object External : ProfileSelection
}
