package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import com.sesame.app.domain.repository.ProfileConfigController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The measured-db source policy as behaviour: external > builtin > bundled, an
 * unreadable external selection is an error rather than a silent fallback, and
 * a selection only applies to its own release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileLayerPolicyTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val bundledRelease = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    /** A kernel no profile ships for: an external document is the only source. */
    private val foreignRelease = "6.1.999-android14-11-gdeadbeefcafe-ab99999999"
    private val routeWaitPath = "execution.race.route_wait_ms"

    /** The bundled value, i.e. what a run gets when nothing overrides it. */
    private val bundledRouteWaitMs = 30L

    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore
    private lateinit var controller: AndroidProfileConfigController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("layer-policy", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
        controller = AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            buildIdentity = TestBuild,
            /* The bundled 5.10 timing is now a diting default; resolve as the
             * authoring device so the bundled value and the layer assertions
             * stay meaningful. */
            deviceCodename = "diting",
        )
    }

    @Test
    fun `the bundled profile applies by default`() = runBlocking {
        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertEquals(bundledRouteWaitMs, generalValue(config, routeWaitPath))
        val layers = checkNotNull(config.layers)
        assertTrue(layers.bundledAuthoritative)
        assertFalse(layers.builtinSelected)
        assertFalse(layers.externalApplied)
        assertNull(layers.externalError)
    }

    @Test
    fun `an external document wins over the bundle for its release`() = runBlocking {
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))

        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertEquals(4242L, generalValue(config, routeWaitPath))
        val layers = checkNotNull(config.layers)
        assertFalse(layers.bundledAuthoritative)
        assertTrue(layers.externalApplied)
        assertTrue(controller.importedProfileExists())
    }

    @Test
    fun `the imported copy survives the source document disappearing`() = runBlocking {
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))
        /* The CN MIUI sequence: the provider stops opening the URI after the
         * import. Every later resolution reads the copy. */
        external.documents.clear()

        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertEquals(4242L, generalValue(config, routeWaitPath))
        assertTrue(config.layers!!.externalApplied)
    }

    @Test
    fun `import stores only the copy, not the reference keys`() = runBlocking {
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))

        assertTrue(controller.importedProfileExists())
        for (key in listOf(
            AndroidProfileConfigController.PrefExternalUri,
            AndroidProfileConfigController.PrefExternalName,
            AndroidProfileConfigController.PrefExternalRelease,
            AndroidProfileConfigController.PrefExternalSha256,
            AndroidProfileConfigController.PrefExternalParent,
            AndroidProfileConfigController.PrefExternalParentSha256,
        )) {
            assertFalse("$key must not be written by the import", preferences.contains(key))
        }
    }

    @Test
    fun `the old selection migrates into the copy once`() = runBlocking {
        val uri = external.add(
            "content://profiles/old.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        preferences.edit(commit = true) {
            putString(AndroidProfileConfigController.PrefExternalUri, uri)
            putString(AndroidProfileConfigController.PrefExternalName, "old.conf")
            putString(AndroidProfileConfigController.PrefExternalRelease, bundledRelease)
            putString(AndroidProfileConfigController.PrefExternalSha256, "sha")
            putString(AndroidProfileConfigController.PrefExternalParent, "parent")
            putString(AndroidProfileConfigController.PrefExternalParentSha256, "parent-sha")
        }

        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertEquals(4242L, generalValue(config, routeWaitPath))
        assertTrue(controller.importedProfileExists())
        assertFalse(preferences.contains(AndroidProfileConfigController.PrefExternalUri))
        assertFalse(preferences.contains(AndroidProfileConfigController.PrefExternalParentSha256))
    }

    @Test
    fun `an unreadable old selection is dropped, not guessed`() = runBlocking {
        preferences.edit(commit = true) {
            putString(AndroidProfileConfigController.PrefExternalUri, "content://missing/doc.conf")
            putString(AndroidProfileConfigController.PrefExternalRelease, bundledRelease)
        }

        val config = controller.load(bundledRelease)

        assertFalse(controller.importedProfileExists())
        assertFalse(preferences.contains(AndroidProfileConfigController.PrefExternalUri))
        /* The bundled profile applies again: the old reference is gone. */
        assertTrue(config.hasProfile)
        assertFalse(config.layers!!.externalApplied)
    }

    @Test
    fun `a copy plus stale old keys deletes the keys without a read`() = runBlocking {
        external.writeImportedProfile(document(bundledRelease, routeWaitMs = 4242))
        preferences.edit(commit = true) {
            putString(AndroidProfileConfigController.PrefExternalUri, "content://whatever/doc.conf")
        }
        /* A read would fail; the copy must win and the stale key must go. */
        external.documents.clear()

        assertTrue(controller.load(bundledRelease).hasProfile)

        assertTrue(controller.importedProfileExists())
        assertFalse(preferences.contains(AndroidProfileConfigController.PrefExternalUri))
    }

    @Test
    fun `an external document applies with no bundled profile`() = runBlocking {
        val uri = external.add(
            "content://profiles/foreign.conf",
            document(foreignRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, foreignRelease))

        val config = controller.load(foreignRelease)

        assertTrue(config.hasProfile)
        assertEquals(4242L, generalValue(config, routeWaitPath))
        val layers = checkNotNull(config.layers)
        assertFalse(layers.bundledAuthoritative)
        assertTrue(layers.externalApplied)
    }

    @Test
    fun `a selected copy makes the release a candidate`() = runBlocking {
        val uri = external.add(
            "content://profiles/foreign.conf",
            document(foreignRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, foreignRelease))

        assertTrue(controller.importedProfileExists())
        assertTrue(releaseSupportedByProfileSources(foreignRelease, emptySet(), true))
        assertTrue(
            releaseSupportedByProfileSources(
                foreignRelease,
                setOf(bundledRelease),
                true,
            ),
        )
        /* The copy is the selection for every release; applicability is decided
         * by the document's entries, not the support predicate. */
        assertTrue(releaseSupportedByProfileSources(bundledRelease, emptySet(), true))
        assertFalse(releaseSupportedByProfileSources(foreignRelease, emptySet(), false))
    }

    @Test
    fun `an external selection for another release refuses, not a silent bundle fallback`() = runBlocking {
        val uri = external.add(
            "content://profiles/foreign.conf",
            document(foreignRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, foreignRelease))

        val config = controller.load(bundledRelease)

        assertFalse(config.hasProfile)
        val layers = checkNotNull(config.layers)
        assertFalse(layers.externalApplied)
        assertNotNull(layers.externalError)
        assertTrue(layers.externalError!!.contains("no entry for $bundledRelease"))
        /* A readable document that misses this release is a content miss, not a
         * read failure: it must not take the "re-import" path. */
        assertFalse(layers.externalReadFailure)
    }

    @Test
    fun `an unreadable external selection is an error, not a fallback`() = runBlocking {
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))
        external.importedReadable = false

        val config = controller.load(bundledRelease)

        assertFalse(config.hasProfile)
        val layers = checkNotNull(config.layers)
        assertFalse(layers.externalApplied)
        assertNotNull(layers.externalError)
        /* The typed read-failure fact: the repository withdraws the selection's
         * release from the supported set on this, and the home offers re-import
         * (the copy read-failure case). */
        assertTrue(layers.externalReadFailure)
    }

    @Test
    fun `the same selection applies again once the document is readable`() = runBlocking {
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))
        external.importedReadable = false
        assertTrue(controller.load(bundledRelease).layers!!.externalReadFailure)

        /* Re-importing the same file repairs the state without a restart: the
         * copy already exists, and the next resolution reads it. */
        external.importedReadable = true
        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        val layers = checkNotNull(config.layers)
        assertTrue(layers.externalApplied)
        assertFalse(layers.externalReadFailure)
    }

    @Test
    fun `a hand-picked builtin applies and clears the external selection`() = runBlocking {
        val other = controller.builtinReleases()
            .filterNot { it.endsWith(ProfileConfigController.TemplateSuffix) }
            .first { it != bundledRelease }
        val uri = external.add(
            "content://profiles/wins.conf",
            document(bundledRelease, routeWaitMs = 4242),
        )
        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))

        controller.selectBuiltin(other, bundledRelease)

        assertFalse(controller.importedProfileExists())
        val layers = checkNotNull(controller.load(bundledRelease).layers)
        assertTrue(layers.builtinSelected)
        assertEquals(other, layers.builtinRelease)
    }

    @Test
    fun `nothing resolves for an unknown release`() = runBlocking {
        val config = controller.load("9.9.9-unknown")

        assertFalse(config.hasProfile)
        assertNull(config.layers?.builtinRelease)
        assertFalse(config.layers!!.externalApplied)
    }

    @Test
    fun `a copy without the newly optional pipeline still loads`() = runBlocking {
        val text = document(bundledRelease, routeWaitMs = 4242)
            .replace(Regex("(?m)^pipeline\\s*=.*$"), "")
        val uri = external.add("content://profiles/no-pipeline.conf", text)

        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))
        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertTrue(config.invalidPaths.isEmpty())
        assertTrue(config.pipelineOrder.isEmpty())
    }

    @Test
    fun `a copy without the required execution block gets the preset defaults`() = runBlocking {
        val text = removeTopLevelBlock(document(bundledRelease, routeWaitMs = 4242), "execution")
        val uri = external.add("content://profiles/no-execution.conf", text)

        assertNotNull(controller.selectExternalProfile(uri, bundledRelease))
        val config = controller.load(bundledRelease)

        assertTrue(config.hasProfile)
        assertTrue(config.invalidPaths.isEmpty())
        assertEquals(bundledRouteWaitMs, generalValue(config, routeWaitPath))
    }

    @Test
    fun `a copy missing a required offset is rejected, not defaulted`() = runBlocking {
        val text = document(bundledRelease, routeWaitMs = 4242)
            .replace(Regex("(?m)^\\s*init_task\\s*=.*$"), "")
        val uri = external.add("content://profiles/no-init-task.conf", text)

        val result = controller.selectExternalProfile(uri, bundledRelease)
        assertTrue(
            result.toString(),
            result is ProfileImportResult.Rejected &&
                result.errors.any { it.startsWith("offset.init_task") },
        )
        /* The rejected copy is not kept: the bundled profile applies again. */
        assertTrue(controller.load(bundledRelease).hasProfile)
        assertFalse(controller.importedProfileExists())
    }

    @Test
    fun `an older schema is refused, not upgraded`() = runBlocking {
        val text = document(bundledRelease, routeWaitMs = 4242)
        val variants = listOf(
            text.replace("schema_version = 3", "schema_version = 2"),
            text.replace("schema_version = 3", "schema_version = 1"),
            text.replace(Regex("(?m)^schema_version = 3\n"), ""),
        )
        for (old in variants) {
            val uri = external.add("content://profiles/old-schema.conf", old)
            val result = controller.selectExternalProfile(uri, bundledRelease)
            assertTrue(
                result.toString(),
                result is ProfileImportResult.Rejected &&
                    result.errors.any { it.contains("schema") },
            )
            assertFalse(controller.importedProfileExists())
        }
    }

    private fun generalValue(config: ProfileConfig, path: String): Long? =
        config.general.firstOrNull { it.path == path }?.value

    /** Removes a top-level `name { ... }` block (its close is at column 0). */
    private fun removeTopLevelBlock(text: String, name: String): String =
        text.replace(Regex("(?ms)^$name \\{.*?^\\}\\s*$"), "")

    /** The bundled profile, re-labelled and edited, as an external file would be. */
    private fun document(release: String, routeWaitMs: Long): String {
        val text = AssetConfigLoader(context).load("kernel_profiles/$bundledRelease.conf")
        check(text.isNotBlank()) { "the bundled profile is missing from the assets" }
        val relabelled = text.replace("release = \"$bundledRelease\"", "release = \"$release\"")
        val replaced = relabelled.replace("route_wait_ms = null", "route_wait_ms = $routeWaitMs")
        /* A refreshed superset omits the execution block (the app fills the
         * tuning presets); insert the override when no null placeholder
         * exists. */
        return if (replaced != relabelled) {
            replaced
        } else {
            relabelled +
                "\nexecution {\n  race {\n    route_wait_ms = $routeWaitMs\n  }\n}\n"
        }
    }

    @Test
    fun `a compat multicast external profile validates and applies`() = runBlocking {
        val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"
        val uri = external.add("content://profiles/compat.conf", compatMulticastDocument(release))

        val result = controller.selectExternalProfile(uri, release)
        assertTrue(result.toString(), result is ProfileImportResult.Accepted)
        val config = controller.load(release)

        assertTrue(config.hasProfile)
        assertTrue(config.layers!!.externalApplied)
        /* The 64-bit pair and the 5.x cred reference slots are legitimately
         * absent; the compat geometry replaces them. Nothing may be flagged. */
        assertTrue(config.invalidPaths.toString(), config.invalidPaths.isEmpty())
        assertEquals("multicast_waiter", config.route)
    }

    @Test
    fun `a compat multicast switch without its geometry is refused`() = runBlocking {
        val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"
        val text = compatMulticastDocument(release)
            .replace("compat_waiter_off = 72\n", "")
        val uri = external.add("content://profiles/compat-truncated.conf", text)

        val result = controller.selectExternalProfile(uri, release)
        assertTrue(
            result.toString(),
            result is ProfileImportResult.Rejected &&
                result.errors.any {
                    it.startsWith("route.multicast_waiter.compat_waiter_off")
                },
        )
    }

    /**
     * A compat-carrier profile as the extractor renders it: the 64-bit pair
     * absent, the compat geometry present, the 6.x cred template (no 5.x
     * reference slots) and the measured slab stride.
     */
    private fun compatMulticastDocument(release: String): String = """
        include "credential-6_1.conf"
        include "kernelsnitch-6x.conf"
        release = "$release"
        schema_version = 3
        kernel_major = 6
        kernel_phys_load = 2684354560
        kernel_phys_offset = 2147483648
        pipeline = [ "ghostlock" ]
        route {
          multicast_waiter {
            waiter_off = null
            buffer_size = null
            task_offset = 48
            lock_offset = 56
            compact_waiter = 1
            compat_carrier = 1
            compat_futex_abi = 1
            compat_waiter_off = 72
            compat_buffer_size = 260
            lock_anchor_image = 35802320
            lock_anchor_bytes = 4096
            lock_anchor_stride = 32
          }
        }
        fallback {
          to = "none"
        }
        selinux {
          enforcing = 0
          initialized = 2
        }
        w1_scratch {
          image = 35459072
        }
        kernelsnitch {
          mm_struct_sz = 1024
        }
        task_struct {
          prio = 132
          normal_prio = 140
          sched_task_group = 840
          pi_lock = 2340
          pi_waiters = 2360
          pi_top_task = 2376
          pi_blocked_on = 2384
          pid = 1584
          tgid = 1588
          atomic_flags = 1520
          real_cred = 2096
          cred = 2104
          comm = 2120
          tasks = 1360
          seccomp = 2304
        }
        waiter_layout {
          compact = 1
          pi_tree = 24
          task = 48
          lock = 56
          wake_state = 64
        }
        offset {
          init_task = 33486336
          init_cred = 33561192
          empty_zero_page = 35454976
          root_task_group = 35485056
          selinux_enforcing = 35820496
          selinux_blob_sizes = 22768872
          security_hook_heads = 22767064
          slide_nfulnl_logger = 33434056
          slide_loggers_0_1 = 33433880
          slide_boot_id = 35955800
        }
    """.trimIndent()

    private companion object {
        const val TestBuild = "test-build"
    }
}
