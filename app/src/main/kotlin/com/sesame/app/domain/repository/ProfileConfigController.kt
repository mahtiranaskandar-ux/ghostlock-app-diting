package com.sesame.app.domain.repository

import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.model.ProfileDiffRow

/**
 * Single authority for profile configuration, measured-db model.
 *
 * A profile is a document (a bundled asset or an external file on the SD card).
 * The live controller has no edit layer and never persists one: exporting a
 * document is the only save (see `docs/analysis/measured-db-plan.md`).
 */
interface ProfileConfigController {
    /** Resolves the active source (external > builtin > bundled) for [release]. */
    suspend fun load(release: String): ProfileConfig

    /**
     * Stores the picker's route choice for [release]. Returns false when the
     * route is not in the current viable set; the stale choice is cleared and
     * the default applies.
     */
    fun selectRoute(release: String, route: String): Boolean

    /**
     * Stores the carrier choice for one native route. Returns false when the
     * carrier is not in that branch's `carriers`; the stale choice is cleared.
     */
    fun selectCarrier(release: String, route: String, carrier: String): Boolean

    /**
     * Stores the DirtyFrag on/off choice. Returns false when the profile's
     * pipeline does not offer the choice (it must list both entries).
     */
    fun selectDirtyFrag(release: String, on: Boolean): Boolean

    /** Renders the active document as the HOCON an export writes. */
    fun renderResolved(release: String): String?

    /** Renders the resolved document verbatim (no trimming) for debug dumps. */
    fun renderResolvedForDebug(release: String): String?

    /** Fields where this source differs from the bundled profile for [release]. */
    fun diffAgainstBundle(release: String): List<ProfileDiffRow>

    /** Makes the bundle the session's base. */
    suspend fun rebaseToBundle(release: String): ProfileConfig

    /** Releases listed by the bundled kernel_profiles/index.conf. */
    suspend fun builtinReleases(): List<String>

    /** Manually selected builtin source, or null for automatic matching. */
    fun activeBuiltinRelease(): String?

    /** True when the app-private imported copy exists (the external selection). */
    fun importedProfileExists(): Boolean

    /**
     * Moves the pre-import-copy `external_profile_*` preferences into the
     * app-private copy once. Idempotent; called before the support decision and
     * at every load.
     */
    fun migrateLegacyExternalSelection(deviceRelease: String)

    /**
     * Imports the SD-card document [uri] for [deviceRelease] into the
     * app-private copy. Transactional: the document is verified before the copy
     * is kept, so a rejected import leaves the previous selection untouched and
     * reports the failing field paths.
     */
    suspend fun selectExternalProfile(
        uri: String,
        deviceRelease: String,
    ): ProfileImportResult

    /** Drops the external selection; the bundle (or builtin) applies again. */
    suspend fun clearExternalProfile(deviceRelease: String): ProfileConfig

    /**
     * Dangerous escape hatch: use another built-in profile as the source for
     * the current device. Null restores automatic matching. Also clears the
     * external selection, because the last explicit choice wins.
     */
    suspend fun selectBuiltin(
        release: String?,
        deviceRelease: String,
    ): ProfileConfig

    /**
     * Typed binary document handed to the native process for [config]'s
     * release and pair; null when the source cannot be resolved. [pipeline] is
     * the app's resolved pipeline ("ghostlock"/"dirtyfrag"); the native refuses
     * an invocation that does not match it. Null omits the cross-check (the
     * parse/preview paths).
     */
    fun nativeDocument(config: ProfileConfig, pipeline: String? = null): ByteArray?

    companion object {
        /** Reference templates ("6.6-template"): manually loadable, never auto-matched. */
        const val TemplateSuffix = "-template"
    }
}
