package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timer fragment is document-shaped, so [mergeTimerFragment] must put it
 * inside the options envelope's `document` — the map `OptionSuperset.cut`
 * clones — and not beside it. A fragment merged at the envelope root is
 * invisible to the cut, which silently drops the block from every export.
 */
class TimerFragmentMergeTest {
    private val release = "6.12.23-android16-5-test"

    private fun fragment(): String = HoconSupport.render(valueMapOf(
        "kernel" to valueMapOf(
            "symbols" to valueMapOf("panic_on_warn" to "0x23dafe0"),
            "struct" to valueMapOf(
                "cred" to valueMapOf("security_sid" to 4L, "uid" to 8L),
            ),
        ),
        "timer" to valueMapOf(
            "trigger" to "killable",
            "match_bits" to 21L,
            "sites" to valueMapOf(
                "timer" to valueMapOf("offset" to "0x1dfb20"),
                "task_alloc" to valueMapOf("low_bits" to "0xcfd98"),
            ),
            "object_size" to 256L,
            "task_alloc_size" to 5184L,
            "node_offset" to 120L,
            "cred" to valueMapOf("request_size" to 184L, "alloc_size" to 192L),
            "trace" to valueMapOf("gfp" to valueMapOf("offset" to 24L, "size" to 8L)),
        ),
    ))

    private fun optionsEnvelope(): String = HoconSupport.render(valueMapOf(
        "schema_version" to 3L,
        "release" to release,
        "routes" to valueListOf(),
        "document" to valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "kernel" to valueMapOf(
                "image_base" to "0xffffffc080000000",
                "symbols" to valueMapOf("panic_on_warn" to null),
            ),
            "timer" to null,
        ),
    ))

    @Test
    fun `the fragment lands inside the options envelope's document`() {
        val merged = mergeTimerFragment(optionsEnvelope(), fragment())
        assertNotNull(merged)
        val root = HoconSupport.parseValue(merged!!).asValueMap()!!
        assertNull("the fragment must not sit beside document", root["timer"])
        assertNull("the fragment must not sit beside document", root["kernel"])
        val document = root["document"].asValueMap()!!
        assertNotNull("the timer block was lost", document["timer"])
        val kernel = document["kernel"].asValueMap()!!
        assertEquals("0x23dafe0", kernel["symbols"].asValueMap()!!["panic_on_warn"])
        val cred = kernel["struct"].asValueMap()!!["cred"].asValueMap()!!
        assertEquals(4L, (cred["security_sid"] as Number).toLong())
    }

    @Test
    fun `a cut of the merged envelope keeps the timer block`() {
        val merged = mergeTimerFragment(optionsEnvelope(), fragment())
        val superset = OptionSuperset.parse(merged!!)
        assertNotNull(superset)
        assertNotNull("the merged document is not the envelope's document", superset!!.document)
        assertNotNull(superset.document!!["timer"])
    }

    @Test
    fun `a bare document is merged at the top level`() {
        val documentText = HoconSupport.render(valueMapOf(
            "schema_version" to 3L,
            "release" to release,
            "kernel" to valueMapOf("image_base" to "0xffffffc080000000"),
            "timer" to null,
        ))
        val merged = mergeTimerFragment(documentText, fragment())
        val root = HoconSupport.parseValue(merged!!).asValueMap()!!
        assertNotNull("the timer block was lost", root["timer"])
        val kernel = root["kernel"].asValueMap()!!
        assertEquals("0x23dafe0", kernel["symbols"].asValueMap()!!["panic_on_warn"])
    }

    @Test
    fun `a fragment without a timer block merges nothing`() {
        assertNull(mergeTimerFragment(optionsEnvelope(), HoconSupport.render(valueMapOf())))
    }
}
