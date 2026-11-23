package com.sesame.app.data

import com.sesame.app.data.profile.Timer98260

/**
 * Text-level carriage of the optional `timer_98260` block.
 *
 * A generated document may carry the block (the repository derives it and the
 * profile then offers the timer route), but a single-route native cut rendered
 * from the option superset does not. The block is metadata only — the native
 * wire never carries it — so appending it to the exported cut keeps the timer
 * route available without changing the cut itself. See
 * `docs/analysis/timer-block-generation-plan.md`.
 */
object TimerBlockText {
    /* Top-level HOCON block: the opening at column 0, the closing `}` at
     * column 0 (every document the app writes renders blocks that way). */
    private val BlockPattern = Regex("(?ms)^timer_98260\\s*\\{.*?^\\}")

    /** The block's text in [document], or null when the document has none. */
    fun extract(document: String): String? = BlockPattern.find(document)?.value?.trim()

    /** Appends [block] unless [document] already carries one. */
    fun append(document: String, block: String): String {
        if (extract(document) != null) return document
        if (!block.trimStart().startsWith(Timer98260.BLOCK_KEY)) return document
        return document.trimEnd() + "\n\n" + block.trimEnd() + "\n"
    }
}
