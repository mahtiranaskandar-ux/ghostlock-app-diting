package com.sesame.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.sesame.app.BuildConfig
import com.sesame.app.data.profile.MeasuredOverlay
import com.sesame.app.data.profile.ModuleBlock
import com.sesame.app.data.profile.OverlayFact
import com.sesame.app.data.profile.PerDevicePhys
import com.sesame.app.data.profile.ProfileMerger
import com.sesame.app.data.profile.ProfileResolver
import com.sesame.app.data.profile.ProfileTransport
import com.sesame.app.data.profile.Timer98260
import com.sesame.app.domain.model.AnchorGeometry
import com.sesame.app.domain.model.ExecutionFieldValue
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.ProfileDiffRow
import com.sesame.app.domain.model.ProfileFieldNode
import com.sesame.app.domain.model.ProfileLayers
import com.sesame.app.domain.model.RouteCandidate
import com.sesame.app.domain.model.physPairErrors
import com.sesame.app.domain.repository.NativeRunDocument
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

    override suspend fun load(release: String): ProfileConfig {
        val resolution = resolve(release)
        val full = resolution.profile
        if (full == null) {
            return ProfileConfig(
                release = release,
                hasProfile = false,
                layers = resolution.layers,
            )
        }
        val candidates = viableRoutes(full)
        val chosen = resolveRouteChoice(release, candidates)
        /* The native document/editor always target a native syscall. When the
         * picker chose the timer, the last native syscall the user picked is
         * the subject (remembered per release, self-healing against the viable
         * set); the first viable native syscall is the fallback. */
        val storedNative = preferences.getString(nativeRouteChoiceKey(release), null)
            ?.takeIf { name -> candidates.any { !it.timer && it.name == name } }
        if (storedNative == null && preferences.contains(nativeRouteChoiceKey(release))) {
            preferences.edit { remove(nativeRouteChoiceKey(release)) }
        }
        val route = chosen?.takeIf { it in ProfileConfig.Routes }
            ?: storedNative
            ?: candidates.firstOrNull { !it.timer }?.name
        val pipelineOrder = ProfileResolver.pipelineOrder(full)
        val needsW1 = ProfileResolver.requiresW1(pipelineOrder)
        /* The module chain's pair manifest, when the document carries it. A
         * malformed block is already in invalidPaths; the runtime then has no
         * spec and refuses the chain. */
        val module = if ("module" in pipelineOrder) {
            ModuleBlock.parse(
                (full[ProfileResolver.GhostlockKey] as? Map<*, *>)?.get(ModuleBlock.BLOCK_KEY),
            ).first
        } else {
            null
        }
        val carrierCandidates = candidates.firstOrNull { it.name == route }?.carriers.orEmpty()
        val selectedCarrier = resolveCarrierChoice(release, route, carrierCandidates)
        val dirtyFragSelectable = "dirtyfrag" in pipelineOrder && "ghostlock" in pipelineOrder
        val dirtyFragOn = resolveDirtyFragChoice(release, pipelineOrder)
        val (physLoad, physOffset) = ProfileResolver.physOf(full)
        val invalidPaths = mutableSetOf<String>()
        invalidPaths += ProfileResolver.validateMerged(full).map { it.fieldPath }
        invalidPaths += physPairErrors(
            socName,
            loadPresent = physLoad != null,
            offsetPresent = physOffset != null,
            needsW1 = needsW1,
        ).map { it.substringBefore(':') }
        /* Invalid fields missing from the resolved document still get a row,
         * otherwise the run stays blocked with no red field to fix. */
        materializeInvalidPaths(full, invalidPaths)
        val baseline = full
        val roots = buildTree(full, "")
        val anchor = ProfileResolver.anchorOf(full)?.let { (image, bytes, stride) ->
            image?.let { AnchorGeometry(it, bytes, stride) }
        }
        return ProfileConfig(
            release = release,
            hasProfile = true,
            physLoad = physLoad,
            physOffset = physOffset,
            anchor = anchor,
            layers = resolution.layers,
            roots = roots,
            general = generalFields(full, baseline, route),
            route = route,
            routeCandidates = candidates,
            selectedRoute = chosen,
            carrierCandidates = carrierCandidates,
            selectedCarrier = selectedCarrier,
            dirtyFragSelectable = dirtyFragSelectable,
            dirtyFragOn = dirtyFragOn,
            pipelineOrder = pipelineOrder,
            module = module,
            invalidPaths = invalidPaths,
        )
    }

    override fun selectRoute(release: String, route: String): Boolean {
        val full = resolve(release).profile ?: return false
        val viable = viableRoutes(full).map { it.name }
        return if (route in viable) {
            preferences.edit {
                putString(routeChoiceKey(release), route)
                /* A native pick is also the GhostLock banner's remembered
                 * syscall while the timer backend is active. */
                if (route in ProfileConfig.Routes) {
                    putString(nativeRouteChoiceKey(release), route)
                }
            }
            true
        } else {
            preferences.edit { remove(routeChoiceKey(release)) }
            false
        }
    }

    override fun selectCarrier(
        release: String,
        route: String,
        carrier: String,
    ): Boolean {
        val full = resolve(release).profile ?: return false
        val carriers = ProfileResolver.carriersOf(branchOf(full, route))
        return if (carrier in carriers) {
            preferences.edit { putString(carrierChoiceKey(release, route), carrier) }
            true
        } else {
            preferences.edit { remove(carrierChoiceKey(release, route)) }
            false
        }
    }

    override fun selectDirtyFrag(release: String, on: Boolean): Boolean {
        val full = resolve(release).profile ?: return false
        if (!dirtyFragSelectable(full)) {
            preferences.edit { remove(dirtyFragChoiceKey(release)) }
            return false
        }
        preferences.edit {
            putString(dirtyFragChoiceKey(release), if (on) "on" else "off")
        }
        return true
    }

    private fun routeChoiceKey(release: String): String = "route_choice_$release"

    private fun nativeRouteChoiceKey(release: String): String =
        "native_route_choice_$release"

    private fun carrierChoiceKey(release: String, route: String): String =
        "carrier_choice_${release}_$route"

    private fun dirtyFragChoiceKey(release: String): String = "dirtyfrag_choice_$release"

    /**
     * The remembered carrier for one route, self-healing like the route
     * choice. A single-carrier branch has no choice and the key is cleared;
     * the carrier is still returned so the wire injection knows it.
     */
    private fun resolveCarrierChoice(
        release: String,
        route: String?,
        carriers: List<String>,
    ): String? {
        if (route == null || carriers.isEmpty()) return null
        if (carriers.size <= 1) {
            preferences.edit { remove(carrierChoiceKey(release, route)) }
            return carriers.first()
        }
        val stored = preferences.getString(carrierChoiceKey(release, route), null)
        val valid = stored?.takeIf { it in carriers }
        if (stored != null && valid == null) {
            preferences.edit { remove(carrierChoiceKey(release, route)) }
        }
        return valid ?: carriers.first()
    }

    private fun dirtyFragSelectable(full: ValueMap): Boolean {
        val order = ProfileResolver.pipelineOrder(full)
        return "dirtyfrag" in order && "ghostlock" in order
    }

    /**
     * The DirtyFrag choice, self-healing. The profile's pipeline decides the
     * available values: both entries → the remembered preference (default on);
     * ghostlock only → off; dirtyfrag only (KDP) → forced on.
     */
    private fun resolveDirtyFragChoice(release: String, order: List<String>): Boolean {
        val hasDirtyFrag = "dirtyfrag" in order
        val hasGhostLock = "ghostlock" in order || order.isEmpty()
        if (!hasDirtyFrag || !hasGhostLock) {
            preferences.edit { remove(dirtyFragChoiceKey(release)) }
            return hasDirtyFrag
        }
        return preferences.getString(dirtyFragChoiceKey(release), null) != "off"
    }

    /**
     * The remembered route, self-healing: a stored name that is no longer in
     * the viable set is cleared and the default (the first candidate) applies.
     */
    private fun resolveRouteChoice(release: String, candidates: List<RouteCandidate>): String? {
        val names = candidates.map { it.name }
        if (names.isEmpty()) return null
        val stored = preferences.getString(routeChoiceKey(release), null)
        val valid = stored?.takeIf { it in names }
        if (stored != null && valid == null) {
            preferences.edit { remove(routeChoiceKey(release)) }
        }
        return valid ?: names.first()
    }

    /**
     * The viable candidates: the document's declared syscall branches, each
     * with its declared carrier pair, plus the timer engine when its block and
     * the shared kernel facts supply the fake group. The picker displays them
     * alphabetically; the first is the default.
     */
    private fun viableRoutes(full: ValueMap): List<RouteCandidate> {
        val order = ProfileResolver.pipelineOrder(full)
        val native = if (!ProfileResolver.requiresW1(order)) {
            /* A profile that selects no native chain (timer-only) must not
             * offer the native syscalls: they cannot run without one. */
            emptyList()
        } else {
            ProfileResolver.syscallsOf(full).map { (name, carriers) ->
                RouteCandidate(name, timer = false, carriers = carriers)
            }
        }
        val timer = if (timerViable(full)) {
            listOf(RouteCandidate(Timer98260.BLOCK_KEY, timer = true))
        } else {
            emptyList()
        }
        return (native + timer).sortedBy { it.name }
    }

    private fun timerViable(full: ValueMap): Boolean = Timer98260.fakeSupported(full)

    /** The document's selected syscall. */
    private fun routeNameOf(profile: ValueMap): String? =
        ((profile[ProfileResolver.GhostlockKey] as? Map<*, *>)?.get("syscall")) as? String

    /** The selected syscall's branch (carrier blocks included). */
    private fun branchOf(profile: ValueMap, syscall: String?): Map<*, *>? {
        if (syscall == null) return null
        val syscalls = (profile[ProfileResolver.GhostlockKey] as? Map<*, *>)?.get("syscalls")
        return (syscalls as? Map<*, *>)?.get(syscall) as? Map<*, *>
    }

    /**
     * Renders the resolved profile as the document an export writes. The
     * caller adds the lineage header and the user picks the destination; this
     * class never writes a profile into app data.
     */
    override fun renderResolved(release: String): String? =
        renderMerged(release, emptyMap())

    /**
     * [extraValues] are facts applied to the resolved document — the measured
     * database's merge path. They are validated with everything else, so a bad
     * fact cannot produce an exportable profile.
     */
    fun renderMerged(
        release: String,
        extraValues: Map<String, Long>,
    ): String? {
        /* Keep the selected document's schema-v3 physical maps for the export:
         * the resolved view carries the running device's scalar pair, but the
         * other devices' entries must survive and an edited value is written
         * into the running device's entry. */
        val selection = currentSelection(release)
        val source = selection?.let { runCatching { loadSource(release, it).document }.getOrNull() }
        val resolved = resolve(release).profile ?: return null
        val view = resolved.copyValue().asValueMap() ?: return null
        for ((path, value) in extraValues) view.setValueAt(path, value)
        val needsW1 = ProfileResolver.requiresW1(ProfileResolver.pipelineOrder(view))
        val (load, offset) = ProfileResolver.physOf(view)
        val invalid = ProfileResolver.validateMerged(view).map { it.fieldPath } +
            physPairErrors(
                socName,
                loadPresent = load != null,
                offsetPresent = offset != null,
                needsW1 = needsW1,
            ).map { it.substringBefore(':') }
        if (invalid.isNotEmpty()) return null
        view["schema_version"] = ProfileResolver.ProfileSchemaVersion
        view["release"] = release
        fillRouteExecutionDefaults(view)
        if (source != null) widenDevices(view, source)
        return HoconSupport.render(view)
    }

    /**
     * The canonical backend document for the timer tool (CVE-2026-98260): the
     * resolved profile rendered as self-contained JSON (W2 §1.5). Returns null
     * when the resolved profile carries no timer block — that is the entry's
     * applicability fact. The measured database is not merged: every timer
     * constant is profile data, and the tool re-checks its own invariants.
     */
    fun renderTimerDocument(release: String): String? {
        val resolved = resolve(release).profile ?: return null
        val block = resolved[Timer98260.BLOCK_KEY] ?: return null
        /* Way 2 is the product gate: the timer block and the shared kernel
         * facts must supply the complete fake group. A real-only document is a
         * lab document, not an app entry. */
        val (timer, errors) = Timer98260.parse(block)
        if (timer == null || errors.isNotEmpty() || !Timer98260.fakeSupported(resolved)) return null
        val view = resolved.copyValue().asValueMap() ?: return null
        /* The tool refuses a document whose release differs from uname -r. */
        view["release"] = release
        /* The tool computes its own kernel VAs from `kernel.image_base` plus
         * the image-relative offsets, so it reads the same transport document
         * the ghostlock backend does — only its block matters. */
        return ProfileTransport.render(view)
    }

    /**
     * Restore the selected document's per-device entries into an export: the
     * resolved view carries the running device's single entry, while every
     * other identity the source states must survive. The running device's
     * entry takes the resolved value.
     */
    private fun widenDevices(view: ValueMap, source: ValueMap) {
        val sourceDevices = source["devices"] as? Map<*, *> ?: return
        val out = linkedMapOf<String, Any?>()
        var matchedKey: String? = null
        for ((rawName, rawEntry) in sourceDevices) {
            val name = rawName?.toString() ?: continue
            out[name] = (rawEntry as? Map<*, *>)?.copyValue()
            if (matchedKey == null && deviceCandidates.any { it.equals(name, ignoreCase = true) }) {
                matchedKey = name
            }
        }
        val current = (view["devices"] as? Map<*, *>)?.entries?.firstOrNull()
        if (current != null) {
            val key = matchedKey ?: current.key?.toString() ?: deviceCodename
            if (key != null) out[key] = (current.value as? Map<*, *>)?.copyValue()
        }
        view["devices"] = out
    }

    /** Renders the resolved profile verbatim (no trimming) for debug dumps. */
    override fun renderResolvedForDebug(release: String): String? {
        val resolved = resolve(release).profile ?: return null
        val view = resolved.copyValue().asValueMap() ?: return null
        return HoconSupport.render(view)
    }

    /** Numeric leaves of the resolved profile, the measured merge's comparison. */
    fun resolvedNumbers(release: String): Map<String, Long>? {
        val resolved = resolve(release).profile ?: return null
        return numericLeaves(resolved)
    }

    /** Values of [release]'s active profile that differ from the bundle. */
    override fun diffAgainstBundle(release: String): List<ProfileDiffRow> {
        val current = resolve(release).profile ?: return emptyList()
        val bundled = resolveFor(
            release, ProfileSelection.Bundled(release),
        ).profile ?: return emptyList()
        return numericDiff(current, bundled)
    }

    /** Moves the session's base to the bundled document. */
    override suspend fun rebaseToBundle(release: String): ProfileConfig {
        if (forcedSelection != null) forcedSelection = ProfileSelection.Bundled(release)
        return load(release)
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
    fun nativeDocumentFor(text: String, release: String): ByteArray? {
        val entry = ProfileDocumentParser.entryForRelease(text, release, ::loadBundledInclude)
            ?: return null
        val merged = runCatching { merge(release, entry) }.getOrNull() ?: return null
        /* This is the generation flow's probe frame: the stride is the fact the
         * probe measures, so its absence is expected here (every run, import
         * and export path keeps the default and refuses a null stride on a W1
         * chain). */
        if (ProfileResolver.validateMerged(merged, requireStride = false).isNotEmpty()) return null
        return ProfileTransport.render(
            merged,
            route = routeNameOf(merged),
        ).toByteArray(Charsets.UTF_8)
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
     * Selects [uri] as the external profile for [deviceRelease]. Transactional:
     * the previous copy is restored when the candidate fails verification, so a
     * rejected import never becomes the selection. The selection wins over the
     * bundled asset; a hand-picked builtin is cleared because the last explicit
     * choice wins.
     */
    override suspend fun selectExternalProfile(
        uri: String,
        deviceRelease: String,
    ): ProfileImportResult {
        val text = externalProfiles.read(uri)
            ?: return ProfileImportResult.Rejected(listOf("the file cannot be read"))
        val entries = ProfileDocumentParser.entries(text, ::loadBundledInclude)
            ?: return ProfileImportResult.Rejected(listOf("the file cannot be read"))
        val entry = entries.firstOrNull { it["release"] == deviceRelease }
            ?: return ProfileImportResult.Rejected(
                listOf("the file carries no profile for $deviceRelease"),
            )
        /* An external file must be the schema this build writes. The check reads
         * the selected entry, so a list-form document is gated exactly like a
         * single object (parsing the raw text as one object would skip a list). */
        val schema = (entry["schema_version"] as? Number)?.toInt()
        if (schema != ProfileResolver.ProfileSchemaVersion) {
            return ProfileImportResult.Rejected(
                listOf(ProfileResolver.schemaErrorMessage(schema)),
            )
        }
        val previous = externalProfiles.readImportedProfile()
        if (!externalProfiles.writeImportedProfile(text)) {
            return ProfileImportResult.Rejected(listOf("the profile copy could not be written"))
        }
        preferences.edit { remove(PrefBuiltinRelease) }
        forcedSelection = null
        val config = runCatching { load(deviceRelease) }.getOrNull()
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
            /* Only a chain that translates the image address needs the pair;
             * a timer-only profile never reads it. */
            needsW1 = ProfileResolver.requiresW1(config.pipelineOrder),
        )
        return errors
    }

    override suspend fun clearExternalProfile(deviceRelease: String): ProfileConfig {
        externalProfiles.clearImportedProfile()
        forcedSelection = null
        return load(deviceRelease)
    }

    override suspend fun selectBuiltin(
        release: String?,
        deviceRelease: String,
    ): ProfileConfig {
        externalProfiles.clearImportedProfile()
        preferences.edit {
            if (release.isNullOrBlank()) remove(PrefBuiltinRelease)
            else putString(PrefBuiltinRelease, release)
        }
        forcedSelection = null
        return load(deviceRelease)
    }

    /**
     * Resolved on demand: the result depends on the selection, so a cached
     * document would go stale.
     */
    override fun nativeDocument(
        config: ProfileConfig,
        pipeline: String?,
        safeMode: Boolean,
    ): ByteArray? = nativeDocumentWithOverlay(config, pipeline, safeMode, emptyList())?.bytes

    override fun nativeDocumentWithOverlay(
        config: ProfileConfig,
        pipeline: String?,
        safeMode: Boolean,
        measured: List<OverlayFact>,
    ): NativeRunDocument? = synchronized(lock) {
        val resolved = resolve(config.release).profile
            ?: return@synchronized null
        /* The measured facts replace their values in the resolved map, never in
         * the document. The overlay only writes the declared measurable set, so
         * validation can only fail if the resolved document itself no longer
         * validates (a concurrent selection change); fail closed then. */
        val overlay = MeasuredOverlay.apply(resolved, measured)
        if (overlay.records.isNotEmpty() &&
            ProfileResolver.validateMerged(overlay.document).isNotEmpty()
        ) {
            return@synchronized null
        }
        /* The document carries the picker's selected syscall and carrier, not
         * the superset's alphabetical first branch. */
        val route = config.route?.takeIf { it in ProfileConfig.Routes }
            ?: routeNameOf(overlay.document)
        NativeRunDocument(
            bytes = ProfileTransport.render(
                overlay.document,
                route = route,
                carrier = config.selectedCarrier,
                pipeline = pipeline,
                safeMode = safeMode,
            ).toByteArray(Charsets.UTF_8),
            overlay = overlay.records,
        )
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

    private fun resolve(release: String): Resolution =
        resolveFor(release, forcedSelection ?: liveSelection(release))

    private fun resolveFor(
        release: String,
        selection: ProfileSelection?,
    ): Resolution {
        val source = loadSource(release, selection)
        val document = source.document
            ?: return Resolution(null, layersFor(selection, source.error, source.readFailure), selection)
        val merged = runCatching { merge(release, document) }
        val layers = layersFor(selection, source.error ?: merged.exceptionOrNull()?.message)
        return Resolution(merged.getOrNull(), layers, selection)
    }

    private fun merge(
        release: String,
        document: ValueMap,
    ): ValueMap {
        /* A controller without any device identity (test/exporter callers) is
         * device-neutral: the maps resolve to the native default, as the
         * exporter's own .bin does. The app always has an identity, and a map
         * that does not name it is refused by PerDevicePhys. */
        if (!ProfileResolver.isCleanedShape(document)) {
            throw IllegalArgumentException(
                "the profile is not the cleaned v3 layout; regenerate it with this build",
            )
        }
        val cleaned = document.asValueMap()
            ?: throw IllegalArgumentException("the profile is not an object")
        val resolvedDocument = if (deviceCandidates.isEmpty()) {
            PerDevicePhys.resolveNeutral(cleaned)
        } else {
            PerDevicePhys.resolveFor(cleaned, deviceCandidates).document
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
                /* Schema v3 only: the copy is parsed as authored (includes are
                 * expanded through the bundled loader); the resolver refuses an
                 * older version. */
                val entries = ProfileDocumentParser.entries(raw, ::loadBundledInclude)
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
        require(documentSchema == ProfileResolver.ProfileSchemaVersion) {
            "unsupported profile schema $documentSchema"
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
     * Native decodes one complete `execution.routes` object. Missing groups are
     * filled from the shared `execution-<route>.conf` presets.
     */
    private fun fillRouteExecutionDefaults(profile: ValueMap) {
        val presets = ProfileConfig.Routes
            .mapNotNull { route -> readExecutionRoute(route)?.let { route to it } }
            .toMap()
        ProfileMerger.fillExecutionDefaults(profile, readExecutionTuning(), presets)
    }

    private fun readExecutionRoute(route: String): ValueMap? = runCatching {
        HoconSupport.parseValue(
            readAsset("$BuiltinDirectory/execution-${route.replace('_', '-')}.conf"),
        ).asValueMap()?.get("execution").asValueMap()?.get("routes").asValueMap()?.get(route).asValueMap()
    }.getOrNull()

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
    ): List<ExecutionFieldValue> {
        fun execution(root: ValueMap): ValueMap? =
            (root[ProfileResolver.GhostlockKey] as? Map<*, *>)?.get("execution").asValueMap()

        fun read(root: ValueMap, path: String): Long? = if (path.startsWith("execution.")) {
            execution(root)?.getLongAt(path.removePrefix("execution."))
        } else {
            root.getLongAt(path)
        }
        /* The display paths stay the stable `execution.*` spelling; the read
         * maps them onto the document's `ghostlock.execution`. The active
         * syscall's own tuning group is appended from the document. */
        val paths = ProfileConfig.GeneralPaths + routeTuningPaths(profile, route)
        return paths.map { path ->
            val value = read(profile, path) ?: 0L
            ExecutionFieldValue(
                path = path,
                value = value,
                overridden = read(baseline, path)?.let { baselineValue -> value != baselineValue } == true,
            )
        }
    }

    /** The active syscall's own tuning group, in a stable order. */
    private fun routeTuningPaths(profile: ValueMap, route: String?): List<String> {
        if (route == null || route !in ProfileConfig.Routes) return emptyList()
        val execution = (profile[ProfileResolver.GhostlockKey] as? Map<*, *>)
            ?.get("execution").asValueMap() ?: return emptyList()
        val group = execution.get("routes").asValueMap()?.get(route).asValueMap()
            ?: return emptyList()
        return group.keys.filterIsInstance<String>().sorted()
            .map { "execution.routes.$route.$it" }
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
                    /* Execution tuning is edited on the general page. */
                    if (key == "execution" && path == "ghostlock.execution") continue
                    val children = buildTree(value.asValueMap() ?: valueMapOf(), path)
                    if (children.isNotEmpty()) {
                        groups += ProfileFieldNode(
                            path = path,
                            name = key,
                            children = children,
                        )
                    }
                }

                value is Number -> if (path != "schema_version" && path != "release") {
                    leaves += ProfileFieldNode(
                        path = path,
                        name = key,
                        value = value.toLong(),
                    )
                }

                value == null -> if (path != "schema_version" && path != "release") {
                    leaves += ProfileFieldNode(
                        path = path,
                        name = key,
                        value = null,
                    )
                }

                /* Addresses are quoted hex strings in the cleaned shape; the
                 * editor is a numeric view, so a hex leaf becomes its signed
                 * 64-bit value (the pre-existing spelling for kernel VAs). */
                value is String -> {
                    val text = value.trim()
                    val parsed = if (text.startsWith("0x", ignoreCase = true)) {
                        text.substring(2).toULongOrNull(16)?.toLong()
                    } else {
                        text.toLongOrNull()
                    }
                    if (parsed != null && path != "release" && path != "schema_version") {
                        leaves += ProfileFieldNode(path = path, name = key, value = parsed)
                    }
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
            if (path == "schema_version" || path == "release") {
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

        const val PrefBuiltinRelease = "debug_builtin_release"
    }
}

/** One resolution source, in precedence order (external > builtin > bundled). */
internal sealed interface ProfileSelection {
    data class Bundled(val release: String) : ProfileSelection
    data class Builtin(val release: String) : ProfileSelection
    /** The app-private imported copy; its presence is the whole selection. */
    object External : ProfileSelection
}
