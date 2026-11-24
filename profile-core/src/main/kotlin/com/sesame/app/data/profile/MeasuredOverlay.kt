package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.copyValue
import com.sesame.app.data.getLongAt
import com.sesame.app.data.setValueAt

/**
 * One measured fact the overlay may apply: the profile path it belongs to, the
 * value, and who measured it (the measured DB's source string).
 */
data class OverlayFact(
    val path: String,
    val value: Long,
    val source: String,
)

/** One fact the overlay actually applied, with the document's own value. */
data class OverlayRecord(
    val path: String,
    val documentValue: Long?,
    val effectiveValue: Long,
    val source: String,
) {
    /** The document states a different value than the measurement. */
    val disagrees: Boolean get() = documentValue != null && documentValue != effectiveValue
}

/** The effective document plus the records of what the overlay changed. */
data class OverlayResult(
    val document: ValueMap,
    val records: List<OverlayRecord>,
)

/**
 * The measured facts' overlay (plan decision 13, §3.3).
 *
 * A matching measurement replaces the document's value **in the resolved map,
 * never in the document**: the resolved tree is copied, the whitelisted paths
 * are written, and the records carry the document value and the source so the
 * run log, the run-store meta and the UI can show effective vs document. The
 * merge preview/export remains the way to materialize an effective fact into a
 * self-contained document.
 *
 * Only the declared measurable set can be overlaid; a fact outside it is
 * ignored silently — the caller never sees a partial overlay. The DirtyFrag
 * probe is not a document value here: it is the ladder's viability input
 * (`AndroidGhostlockRepository`'s pipeline step), which is the existing
 * measured-DB exception.
 */
object MeasuredOverlay {
    /** The declared measurable set. Nothing outside this set may be overlaid. */
    val Paths: Set<String> = setOf("kernel.heap.stride")

    fun apply(document: Map<String, Any?>, facts: List<OverlayFact>): OverlayResult {
        val effective = document.copyValue().asValueMap() ?: ValueMap()
        val records = mutableListOf<OverlayRecord>()
        for (fact in facts) {
            if (fact.path !in Paths) continue
            val current = effective.getLongAt(fact.path)
            effective.setValueAt(fact.path, fact.value)
            records += OverlayRecord(
                path = fact.path,
                documentValue = current,
                effectiveValue = fact.value,
                source = fact.source,
            )
        }
        return OverlayResult(effective, records)
    }
}
