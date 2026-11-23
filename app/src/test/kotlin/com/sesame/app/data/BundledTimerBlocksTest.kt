package com.sesame.app.data

import android.app.Application
import com.sesame.app.data.profile.Timer98260
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every bundled `timer_98260` block must parse through the app's own timer
 * parser, and a block that carries the fake group must be a complete way-2
 * block.
 *
 * The timer tool re-checks its document at run time, but the bundle is the
 * app's input: a typo, an unquoted 64-bit address or a half-added fake group
 * must fail here, not on a phone. `ship_golden_profiles.py --check` owns the
 * store-to-bundle values; this test owns the bundle's own shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledTimerBlocksTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val loader = AssetConfigLoader(context)

    @Test
    fun `every bundled timer block parses and the way-2 groups are complete`() {
        val index = HoconSupport.parseValue(loader.load("kernel_profiles/index.conf")).asValueMap()
            ?: error("index.conf is not an object")
        val entries = index["profiles"].asValueList().orEmpty()
            .mapNotNull { it.asValueMap() }
            .mapNotNull { entry ->
                val release = entry["release"] as? String ?: return@mapNotNull null
                val file = entry["file"] as? String ?: return@mapNotNull null
                release to file
            }

        var blocks = 0
        var way2 = 0
        for ((release, file) in entries) {
            val document = HoconSupport.parseValue(loader.load("kernel_profiles/$file"))
                .asValueMap() ?: continue
            val raw = document["timer_98260"] ?: continue
            blocks++
            val (config, errors) = Timer98260.parse(raw)
            assertTrue("$release: $errors", errors.isEmpty())
            val parsed = requireNotNull(config) { "$release: timer block did not parse" }
            val label = if (parsed.fakeSupported) "real,fake" else "real"
            assertEquals("$release: endgame label", label, parsed.endgameLabel)
            if (parsed.fakeSupported) way2++
        }

        assertTrue("no bundled timer blocks parsed", blocks > 0)
        /* Every bundled block is the complete way-2 group: a real-only block
         * parses but is inert (the picker never offers the timer from it), so
         * shipping one would be a silent no-op. */
        assertEquals("every bundled timer block must be way 2", blocks, way2)
    }
}
