package com.sesame.app.domain.repository

import com.sesame.app.data.profile.OverlayFact
import com.sesame.app.data.profile.OverlayRecord
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
     * carrier is not among that branch's stated carrier blocks; the stale
     * choice is cleared.
     */
    fun selectCarrier(release: String, route: String, carrier: String): Boolean

    /**
     * Stores the DirtyFrag on/off choice. Returns false when the document's
     * ladder does not offer the choice (both DirtyFrag and GhostLock).
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
    fun nativeDocument(
        config: ProfileConfig,
        pipeline: String? = null,
        safeMode: Boolean = false,
    ): ByteArray?

    /**
     * The native document with the measured overlay applied (plan decision 13,
     * §3.3): the resolved map is copied, the declared measurable facts are
     * written into it, and the records carry the document value, the effective
     * value and the source. The document itself is never modified. [measured]
     * is what the measured DB holds for the running release; the caller logs
     * the records and writes them to the run-store meta.
     */
    fun nativeDocumentWithOverlay(
        config: ProfileConfig,
        pipeline: String? = null,
        safeMode: Boolean = false,
        measured: List<OverlayFact> = emptyList(),
    ): NativeRunDocument?

    companion object {
        /** Reference templates ("6.6-template"): manually loadable, never auto-matched. */
        const val TemplateSuffix = "-template"
    }
}

/** One rendered native document and the measured facts the overlay applied. */
data class NativeRunDocument(
    val bytes: ByteArray,
    val overlay: List<OverlayRecord>,
)

