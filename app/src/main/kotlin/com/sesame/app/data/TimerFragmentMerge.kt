package com.sesame.app.data

import com.sesame.app.data.profile.Timer98260

/**
 * Merges the timer analyzer's cleaned fragment (`kernel` facts + `timer`) into a
 * generation pass's document.
 *
 * The fragment is document-shaped. The parse path hands this either the
 * extractor's profile document (`--out`) or its options envelope
 * (`--options-out`), whose profile document lives under `document`.
 *
 * The envelope's `document` is the base [OptionSuperset.cut] clones, so the
 * fragment must land *inside* it. Merging it beside `document` (at the envelope
 * root) leaves every cut of the superset without a `timer` block — the block
 * silently disappears from a single-route generation, which is exactly the
 * export path the user sees.
 *
 * Returns null when the input is not an object or the fragment carries no
 * `timer` block (nothing to merge).
 */
internal fun mergeTimerFragment(documentText: String, fragmentText: String): String? {
    val root = HoconSupport.parseValue(documentText).asValueMap() ?: return null
    val fragment = HoconSupport.parseValue(fragmentText).asValueMap() ?: return null
    if (fragment[Timer98260.BLOCK_KEY] == null) return null
    val envelope = root["document"]
    if (envelope is Map<*, *>) {
        val inner = envelope.asValueMap() ?: return null
        deepMergeValues(inner, fragment)
        root["document"] = inner
    } else {
        deepMergeValues(root, fragment)
    }
    return HoconSupport.render(root)
}
