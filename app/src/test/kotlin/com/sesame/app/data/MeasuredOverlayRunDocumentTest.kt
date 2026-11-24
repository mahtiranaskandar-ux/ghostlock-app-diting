package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import com.sesame.app.data.profile.OverlayFact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The measured overlay reaches the native document's resolved map (decision
 * 13, §3.3) — the document itself is never modified, and the records carry the
 * document value, the effective value and the source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeasuredOverlayRunDocumentTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    private fun controller(): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("overlay-run-doc", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = "diting",
        )

    private fun document(bytes: ByteArray): ValueMap =
        HoconSupport.parseValue(String(bytes, Charsets.UTF_8)).asValueMap()
            ?: error("document is not an object")

    @Test
    fun `the measured stride is applied to the run document only`() = runBlocking {
        val config = controller().load(release)
        val plain = document(controller().nativeDocument(config, "dirtyfrag", false)!!)
            .getLongAt("kernel.heap.stride")

        val rendered = controller().nativeDocumentWithOverlay(
            config,
            "dirtyfrag",
            false,
            listOf(OverlayFact("kernel.heap.stride", 1024, "root-slabinfo")),
        )!!

        assertEquals(1024L, document(rendered.bytes).getLongAt("kernel.heap.stride"))
        val record = rendered.overlay.single()
        assertEquals("kernel.heap.stride", record.path)
        assertEquals(plain, record.documentValue)
        assertEquals(1024L, record.effectiveValue)
        assertEquals("root-slabinfo", record.source)
        assertTrue(record.disagrees)
        /* The resolved document is untouched: the next plain render is unchanged. */
        assertEquals(
            plain,
            document(controller().nativeDocument(config, "dirtyfrag", false)!!)
                .getLongAt("kernel.heap.stride"),
        )
    }

    @Test
    fun `an equal measurement is recorded without a disagreement`() = runBlocking {
        val config = controller().load(release)
        val plain = document(controller().nativeDocument(config, "dirtyfrag", false)!!)
            .getLongAt("kernel.heap.stride")
            ?: return@runBlocking

        val rendered = controller().nativeDocumentWithOverlay(
            config,
            "dirtyfrag",
            false,
            listOf(OverlayFact("kernel.heap.stride", plain, "root-slabinfo")),
        )!!

        assertEquals(plain, rendered.overlay.single().effectiveValue)
        assertFalse(rendered.overlay.single().disagrees)
    }

    @Test
    fun `a fact outside the measured set never reaches the document`() = runBlocking {
        val config = controller().load(release)

        val rendered = controller().nativeDocumentWithOverlay(
            config,
            "dirtyfrag",
            false,
            listOf(OverlayFact("kernel.heap.collisions", 4, "root-slabinfo")),
        )!!

        assertTrue(rendered.overlay.isEmpty())
        assertEquals(8L, document(rendered.bytes).getLongAt("kernel.heap.collisions"))
    }
}
