package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import com.sesame.app.data.profile.Timer98260
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The timer tool's input is the canonical backend document (W2 §1.5). The
 * entry's applicability fact is the presence of the `timer` block; the
 * renderer must emit it under its top-level key with the running release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerDocumentTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val diting = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
    private val ditingFile = "$diting.conf"

    private data class Entry(val release: String, val file: String)

    private fun builtinEntries(): List<Entry> {
        val index = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/index.conf"),
        ).asValueMap() ?: error("index.conf is not an object")
        return index["profiles"].asValueList().orEmpty()
            .mapNotNull { it.asValueMap() }
            .mapNotNull { entry ->
                val release = entry["release"] as? String ?: return@mapNotNull null
                val file = entry["file"] as? String ?: return@mapNotNull null
                Entry(release, file)
            }
            .filterNot { it.release.endsWith("-template") }
    }

    private fun controllerFor(file: String): AndroidProfileConfigController {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$file"),
        ).asValueMap()
        val device = document?.get("devices").asValueMap()?.keys?.firstOrNull()
        return AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("timer-document-$file", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
        )
    }

    @Test
    fun `the diting document carries the timer block and the exact release`() {
        val text = controllerFor(ditingFile).renderTimerDocument(diting)
        assertNotNull("no timer document for $diting", text)
        val json = JSONObject(text!!)
        assertTrue("the timer block is missing", json.has("timer"))
        assertEquals(diting, json.getString("release"))
        assertEquals("core_state", json.getJSONObject("timer").getString("trigger"))
        /* One document for every backend: the engine blocks are siblings and
         * the tool reads only `timer` (plus the shared `kernel` facts). */
        assertTrue(json.has("kernel"))
        assertTrue(json.has("ghostlock"))
        assertTrue(!json.has("route"))
        assertTrue(!json.has("timer_98260"))
    }

    @Test
    fun `a profile without a timer block produces no document`() {
        val entry = builtinEntries().firstOrNull { candidate ->
            candidate.file != ditingFile &&
                AssetConfigLoader(context).load("kernel_profiles/${candidate.file}")
                    .let { !it.contains(Timer98260.BLOCK_KEY) }
        }
        if (entry == null) return
        assertNull(controllerFor(entry.file).renderTimerDocument(entry.release))
    }
}
