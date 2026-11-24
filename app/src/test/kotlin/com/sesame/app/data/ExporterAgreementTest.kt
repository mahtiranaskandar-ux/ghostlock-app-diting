package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Cross-checks the Gradle exporter output against the app's native documents.
 * Both share `:profile-core`. It asserts the exported set equals the index's
 * non-template entries, then compares each file byte-for-byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExporterAgreementTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun isModuleOnly(file: String): Boolean {
        val authored = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$file"),
        ).asValueMap() ?: return false
        val document = authored
        val order = com.sesame.app.data.profile.ProfileResolver.pipelineOrder(document)
        return "module" in order &&
            !com.sesame.app.data.profile.ProfileResolver.requiresW1(order)
    }

    @Test
    fun `exporter output matches the app native documents`() = runBlocking {
        val exporterDir = File("../build/kernel-profiles")
        assertTrue(
            "exporter output missing; run :profile-core:exportKernelProfiles",
            exporterDir.isDirectory,
        )

        val index = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/index.conf"),
        ).asValueMap() ?: error("index.conf is not an object")
        val expected = index["profiles"].asValueList().orEmpty()
            .mapNotNull { it.asValueMap() }
            .mapNotNull { entry ->
                val release = entry["release"] as? String ?: return@mapNotNull null
                val file = entry["file"] as? String ?: return@mapNotNull null
                release to file
            }
            .filterNot { it.first.endsWith("-template") }
            /* The exporter writes no native wire for a module-only profile. */
            .filterNot { (_, file) -> isModuleOnly(file) }
            .map { it.first }
            .toSortedSet()
        val actual = exporterDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty().map { it.name.removeSuffix(".json") }.toSortedSet()
        assertEquals("exported set must match the index (excluding templates)", expected, actual)

        val root = Files.createTempDirectory("exporter-agreement").toFile()
        try {
            val controller = AndroidProfileConfigController(
                context = context,
                externalProfiles = FakeExternalProfileStore(),
                preferences = context.getSharedPreferences("exporter-agreement", 0)
                    .also { it.edit().clear().commit() },
                /* The exporter's .bin is device-neutral (the physical maps
                 * resolve to the native default), so the app side must be too. */
                deviceCodename = null,
            )
            for (release in expected) {
                val appText = String(
                    checkNotNull(controller.nativeDocument(controller.load(release))) {
                        "$release: app has no native document"
                    },
                    Charsets.UTF_8,
                )
                /* Both sides are the same resolved map through the same
                 * renderer; the exporter is device-neutral, so the device entry
                 * is absent on both. */
                assertEquals(
                    "$release: exporter differs from the app",
                    appText,
                    File(exporterDir, "$release.json").readText(),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
