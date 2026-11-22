package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.domain.model.CpuPair
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The star `5.4.233-qgki-gbb70cde46897` v1 (all-device scalar pair) profiles,
 * imported and resolved through the real controller.
 *
 * The documents live in the port repo at `external/port/star-5.4/v1/`; the test
 * finds them from the working directory or `GHOSTLOCK_STAR_V1_DIR`, and skips
 * when neither is present (the same convention as the store tests that need
 * `GHOSTLOCK_IMAGE_DIR`). Its job is the two claims the v1 form makes:
 *  - the scalar pair resolves without any device-name match (even under an
 *    unrelated identity such as `venus`);
 *  - an unversioned copy is upgraded to `schema_version = 1` on load and the
 *    upgraded form is what the app keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StarV1ProfileImportTest {
    private val release = "5.4.233-qgki-gbb70cde46897"
    private val pair = CpuPair(primary = 0, consumer = 1)
    private val primaryLoad = 0xa0080000L
    private val contingencyLoad = 0xa0000000L
    private val dramBase = 0x80000000L

    private val primaryName = "profile-5.4.233-qgki-gbb70cde46897-v1-lA008O800.conf"
    private val contingencyName = "profile-5.4.233-qgki-gbb70cde46897-v1-lA000O800.conf"
    private val unversionedName =
        "profile-5.4.233-qgki-gbb70cde46897-unversioned-lA008O800.conf"

    private val context: Application = RuntimeEnvironment.getApplication()
    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore
    private lateinit var controller: AndroidProfileConfigController

    @Before
    fun setUp() {
        assumeTrue(
            "set GHOSTLOCK_STAR_V1_DIR or run from inside the port repo",
            profileDir()?.isDirectory == true,
        )
        preferences = context.getSharedPreferences("star-v1", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
        controller = controllerFor("star")
    }

    @Test
    fun `the explicit v1 primary profile loads and resolves the scalar pair`() {
        val config = checkNotNull(import(primaryName))

        assertTrue(config.hasProfile)
        assertEquals(primaryLoad, config.physLoad)
        assertEquals(dramBase, config.physOffset)
        assertEquals("sigreturn", config.route)
        assertTrue(config.invalidPaths.toString(), config.invalidPaths.isEmpty())
        assertTrue(config.layers!!.externalApplied)
        /* An explicit version is left byte-for-byte alone. */
        assertEquals(file(primaryName).readText(), external.imported)
    }

    @Test
    fun `the explicit v1 load contingency carries its own pair`() {
        val config = checkNotNull(import(contingencyName))

        assertTrue(config.hasProfile)
        assertEquals(contingencyLoad, config.physLoad)
        assertEquals(dramBase, config.physOffset)
        assertEquals("sigreturn", config.route)
        assertTrue(config.invalidPaths.toString(), config.invalidPaths.isEmpty())
    }

    @Test
    fun `the v1 pair resolves under an unrelated identity`() {
        /* The whole point of v1: no identity is compared to the document. */
        controller = controllerFor("venus")
        val config = checkNotNull(import(primaryName))

        assertTrue(config.hasProfile)
        assertEquals(primaryLoad, config.physLoad)
        assertEquals(dramBase, config.physOffset)
        assertTrue(config.layers!!.externalApplied)
    }

    @Test
    fun `an unversioned v1 copy is auto-upgraded to schema 1`() {
        val raw = file(unversionedName).readText()
        assertTrue(
            "the probe file must carry no version field",
            raw.lineSequence().none { it.trimStart().startsWith("schema_version") },
        )

        val config = checkNotNull(import(unversionedName))

        assertTrue(config.hasProfile)
        assertEquals(primaryLoad, config.physLoad)
        assertEquals(dramBase, config.physOffset)
        val stored = checkNotNull(external.imported)
        assertTrue(stored, stored.contains("schema_version = 1"))
        assertFalse(stored, stored.contains("schema_version = 2"))
        assertFalse(stored, stored.contains("kernel_phys_load = {"))
    }

    private fun controllerFor(identity: String): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            buildIdentity = "test-build",
            deviceCodename = identity,
            deviceAliases = emptyList(),
        )

    /** Imports [name] through the controller's real transactional path. */
    private fun import(name: String): ProfileConfig? {
        val uri = "content://profiles/$name"
        external.add(uri, file(name).readText())
        val result = runBlocking { controller.selectExternalProfile(uri, release, pair) }
        val accepted = result as? ProfileImportResult.Accepted
            ?: throw AssertionError("import rejected: $result")
        return accepted.config
    }

    private fun file(name: String): File = File(checkNotNull(profileDir()), name)

    private fun profileDir(): File? {
        val override: String? = System.getenv("GHOSTLOCK_STAR_V1_DIR")
        override?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            for (relative in listOf("external/port/star-5.4/v1", "port/star-5.4/v1")) {
                val candidate = dir?.resolve(relative)
                if (candidate?.isDirectory == true) return candidate
            }
            dir = dir?.parentFile
        }
        return null
    }
}
