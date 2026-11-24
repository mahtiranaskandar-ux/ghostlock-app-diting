package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Transport-document equivalence lock: every builtin profile must still
 * produce the exact rendered document bytes frozen before the refactor
 * (`app/src/test/resources/native-doc-golden.sha256`). Both backends read this
 * one document; the digest covers the whole shape, run keys included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeDocumentEquivalenceTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `builtin native documents match the frozen golden`() = runBlocking {
        val golden = readGolden()
        assertTrue("golden fixture is empty", golden.isNotEmpty())

        val root = Files.createTempDirectory("native-doc-equivalence").toFile()
        try {
            /* The bundled profiles carry their physical pair in a per-device
             * map and their timing null. Resolve each with its authoring device
             * so the frozen bytes still describe the shipped no-op on that
             * device; a device-neutral resolution is covered by
             * ExporterAgreementTest. */
            val controllers = mutableMapOf<String?, AndroidProfileConfigController>()
            fun controllerFor(release: String): AndroidProfileConfigController {
                val device = when {
                    release in setOf(
                        "5.10.66-android12-9-00001-g41ff3fa8fff9-ab8171928",
                        "5.10.168-android12-9-00002-gf3c080171fd5-ab10346004",
                        "5.10.226-android12-9-00047-g4968e29b7f92-ab12786767",
                    ) -> "xaga"
                    release == "5.10.101-android12-9-00001-gf4c0e37dbcde-ab8596533" -> "yunluo"
                    release == "5.10.168-android12-9-00001-g81e7418c6466-ab10486262" -> "rubens"
                    release == "5.10.236-android12-9-00020-gf997514b333d-ab13743839" -> "tanzanite"
                    release.startsWith("5.10.") -> "diting"
                    release == "6.6.89-android15-8-g5a0ffb447c1d-ab13771415-4k" -> "dew"
                    else -> null
                }
                return controllers.getOrPut(device) {
                    AndroidProfileConfigController(
                        context = context,
                        externalProfiles = FakeExternalProfileStore(),
                        preferences = context.getSharedPreferences(
                            "native-doc-equivalence-$device",
                            0,
                        ).also { it.edit().clear().commit() },
                        deviceCodename = device,
                    )
                }
            }
            val computed = LinkedHashMap<String, String>()
            for ((release, _) in golden) {
                val config = controllerFor(release).load(release)
                assertTrue("$release did not resolve", config.hasProfile)
                val bytes = controllerFor(release).nativeDocument(config)
                assertNotNull("$release has no native document", bytes)
                computed[release] = sha256(bytes!!)
            }
            /* Deliberate re-freeze for a schema change: the digest covers every
             * declared key, so adding an absent key moves every document's
             * bytes while changing no value. Set the env var to the resource
             * path to regenerate; a normal run compares. */
            val updatePath = System.getenv("UPDATE_NATIVE_DOC_GOLDEN")
            if (updatePath != null) {
                val transportNote =
                    "\n# Re-frozen 2026-11-24: the native input is the one rendered JSON\n" +
                    "# document (the GLK1 wire is gone). Every row moved; no value did."
                val header = goldenHeader().let {
                    if (it.contains("Re-frozen 2026-11-24")) it else it + transportNote
                }
                val rows = computed.entries.joinToString("\n") { "${it.key} ${it.value}" }
                Files.writeString(java.nio.file.Path.of(updatePath), "$header\n$rows\n")
                return@runBlocking
            }
            for ((release, expected) in golden) {
                assertEquals(
                    "$release transport document drifted",
                    expected,
                    computed.getValue(release),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readGolden(): Map<String, String> {
        val text = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("native-doc-golden.sha256"),
        ).bufferedReader().use { it.readText() }
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .associate { line ->
                val parts = line.trim().split(' ')
                parts[0] to parts[1]
            }
    }

    /** The comment block currently in the resource, preserved across a re-freeze. */
    private fun goldenHeader(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream("native-doc-golden.sha256"),
        ).bufferedReader().use { reader ->
            reader.readText().lineSequence()
                .filter { it.trimStart().startsWith("#") }
                .joinToString("\n")
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
