package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.domain.model.CpuPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The controller's document resolution: the rendered export carries measured
 * facts and the CPU pair, and there is no persisted edit layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerOverrideTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
    private val pair = CpuPair(primary = 0, consumer = 1)

    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore
    private lateinit var controller: AndroidProfileConfigController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("controller-override", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
        controller = sessionController()
    }

    private fun sessionController(): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            deviceCodename = "diting",
            forcedSelection = ProfileSelection.Bundled(release),
        )

    @Test
    fun `renderMerged applies a measured fact and refuses an implausible one`() = runBlocking {
        val text = requireNotNull(
            controller.renderMerged(release, pair, mapOf("kernelsnitch.mm_struct_sz" to 960L)),
        )
        val parsed = checkNotNull(HoconSupport.parseValue(text).asValueMap())
        val snitch = checkNotNull(parsed["kernelsnitch"].asValueMap())
        assertEquals(960L, (snitch["mm_struct_sz"] as Number).toLong())

        assertNull(
            controller.renderMerged(release, pair, mapOf("kernelsnitch.mm_struct_sz" to 7L)),
        )
    }

    @Test
    fun `probe facts in the measured database do not poison the merge`() = runBlocking {
        /* The DB also records the DirtyFrag probe decision. Handing that to
         * the renderer creates an unknown top-level key, which the validator
         * refuses — the whole export fails. The merge consumes the
         * profile-addressable projection only. */
        val kernel = MeasuredKernel(
            release = release,
            updatedAt = 0L,
            facts = listOf(
                MeasuredFact(
                    "kernelsnitch.mm_struct_sz", 960L, "root-slabinfo", "mm_struct …", 0L,
                ),
                MeasuredFact(
                    MeasuredProfileStore.KeyDirtyfragProbe, 1L, "dirtyfrag-probe", "control=pass", 0L,
                ),
                MeasuredFact(
                    MeasuredProfileStore.KeyDirtyfragProbeRom, 42L, "dirtyfrag-probe", "rom=…", 0L,
                ),
                MeasuredFact(
                    MeasuredProfileStore.KeyDirtyfragProbeProtocol, 1L, "dirtyfrag-probe",
                    "protocol=1", 0L,
                ),
            ),
        )
        assertNull(
            controller.renderMerged(release, pair, kernel.facts.associate { it.key to it.value }),
        )
        val text = requireNotNull(
            controller.renderMerged(
                release,
                pair,
                kernel.profileFacts().associate { it.key to it.value },
            ),
        )
        val parsed = checkNotNull(HoconSupport.parseValue(text).asValueMap())
        assertEquals(960L, parsed.getLongAt("kernelsnitch.mm_struct_sz"))
        assertNull(parsed["dirtyfrag"])
    }

    @Test
    fun `the cpu pair reaches the native document`() = runBlocking {
        val pair = CpuPair(primary = 2, consumer = 3)
        val config = controller.load(release, pair)
        val decoded = checkNotNull(
            NativeProfileDocument.fromBinary(checkNotNull(controller.nativeDocument(config))),
        )
        assertEquals(2u, decoded.execution.recommendedMainCpu)
        assertEquals(3u, decoded.execution.recommendedConsumerCpu)
    }
}
