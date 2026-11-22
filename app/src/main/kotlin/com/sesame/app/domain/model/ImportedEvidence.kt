package com.sesame.app.domain.model

/**
 * A user-supplied bugreport reduced in memory for the current export session.
 *
 * The original zip is never copied into app storage, and this object is not
 * persisted: it lives as long as the report sheet, is written into the bundle
 * that includes it, and is dropped when the sheet closes. The automatic
 * pstore captures remain the only crash evidence stored on the device.
 */
data class ImportedEvidence(
    val bugreportName: String,
    val capturedMs: Long,
    /** The app boot during which the report was imported, not the report's boot. */
    val captureBootId: String,
    val facts: Map<String, String>,
    val history: String?,
    /** Evidence files, including `boot.txt` and the history sidecar. */
    val files: Map<String, ByteArray>,
    val crashEvidence: Boolean,
) {
    fun toInfo(): CrashEvidenceInfo = CrashEvidenceInfo(
        source = "bugreport",
        captureBootId = captureBootId,
        capturedMs = capturedMs,
        files = files.keys.sorted(),
        bootReason = facts["ro.boot.bootreason"].orEmpty(),
        bugreportName = bugreportName,
        crashEvidence = crashEvidence,
    )
}
