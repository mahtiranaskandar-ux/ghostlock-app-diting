package com.sesame.app.domain.repository

import com.sesame.app.domain.model.CpuPair
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
    suspend fun load(release: String, pair: CpuPair): ProfileConfig

    /** Renders the active document as the HOCON an export writes. */
    fun renderResolved(release: String, pair: CpuPair): String?

    /** Renders the resolved document verbatim (no trimming) for debug dumps. */
    fun renderResolvedForDebug(release: String, pair: CpuPair): String?

    /** Fields where this source differs from the bundled profile for [release]. */
    fun diffAgainstBundle(release: String, pair: CpuPair): List<ProfileDiffRow>

    /** Makes the bundle the session's base. */
    suspend fun rebaseToBundle(release: String, pair: CpuPair): ProfileConfig

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
        pair: CpuPair,
    ): ProfileImportResult

    /** Drops the external selection; the bundle (or builtin) applies again. */
    suspend fun clearExternalProfile(deviceRelease: String, pair: CpuPair): ProfileConfig

    /**
     * Dangerous escape hatch: use another built-in profile as the source for
     * the current device. Null restores automatic matching. Also clears the
     * external selection, because the last explicit choice wins.
     */
    suspend fun selectBuiltin(
        release: String?,
        deviceRelease: String,
        pair: CpuPair,
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
