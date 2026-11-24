package com.sesame.app.data

/**
 * Removes the lock anchor from a generated profile so the route uses the
 * reclaimed payload page. This is the user's "page" choice at profile
 * generation; the rest of the document is unchanged.
 *
 * The document is the cleaned v3 shape: the anchor is `ghostlock.anchor`, so
 * the page choice sets it to null. (The pre-v3 `route`/`fallback` branch triples
 * no longer exist.)
 */
internal fun stripLockAnchor(documentText: String): String? =
    rewriteDocument(documentText) { entry ->
        entry["ghostlock"].asValueMap()?.put("anchor", null) != null
    }

/**
 * Writes the generation flow's chosen slab stride into a generated document
 * (`kernel.heap.stride`), so the exported profile carries it and does not
 * depend on this device's measured database. The same value is also recorded
 * there as weaker evidence (`MeasuredProfileStore.SourceGenerationProbe`); a
 * root `/proc/slabinfo` measurement still overrides it at run time.
 *
 * Null when the text cannot be parsed or carries no kernel block.
 */
internal fun materializeDocumentStride(documentText: String, value: Long): String? {
    var changed = false
    val text = rewriteDocument(documentText) { entry ->
        val kernel = entry["kernel"].asValueMap() ?: return@rewriteDocument false
        val heap = kernel["heap"].asValueMap()
        if (heap != null) {
            heap["stride"] = value
        } else {
            /* The v3 document always has `heap`; this is the labelled fallback
             * for a hand-edited text, not the generated shape. */
            kernel["heap"] = valueMapOf("stride" to value, "collisions" to null)
        }
        changed = true
        true
    }
    return text.takeIf { changed }
}

/**
 * Parses the document, applies [edit] to every entry and renders it again.
 * The leading comment block (the extractor's `# fact_sources` provenance) is
 * carried over verbatim: the HOCON renderer writes values, not comments.
 */
private fun rewriteDocument(documentText: String, edit: (ValueMap) -> Boolean): String? {
    val entries = ProfileDocumentParser.entries(documentText) ?: return null
    for (entry in entries) edit(entry)
    val body = HoconSupport.render(if (entries.size == 1) entries.first() else entries)
    return leadingComments(documentText) + body
}

/** The `# …` block before the first key, or "" when the text has none. */
private fun leadingComments(text: String): String {
    val lines = text.lines()
        .takeWhile { it.isBlank() || it.trimStart().startsWith("#") }
        .dropLastWhile { it.isBlank() }
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}
