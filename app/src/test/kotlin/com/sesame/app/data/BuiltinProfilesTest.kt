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

/**
 * Black-box end-to-end test over every bundled profile: resolve it through the
 * controller, require a clean document, and verify the route-scoped UI
 * projection and the v2 round trip. This is the test that catches "a builtin
 * profile no longer loads after a schema/format change".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuiltinProfilesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

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

    /**
     * A controller whose device identity is one the profile's own physical map
     * names — a v2 map that names no identity of the running device is refused
     * by PerDevicePhys. A profile without a map resolves device-neutrally.
     */
    private fun controllerFor(file: String): AndroidProfileConfigController {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$file"),
        ).asValueMap()
        val device = document?.let {
            listOf("kernel_phys_load", "kernel_phys_offset").firstNotNullOfOrNull { key ->
                (it[key] as? Map<*, *>)?.keys?.firstOrNull()?.toString()
            }
        }
        return AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("builtin-profiles-$file", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
        )
    }

    @Test
    fun `every builtin profile resolves cleanly and round trips`() = runBlocking {
        val root = Files.createTempDirectory("builtin-profiles").toFile()
        try {
            val entries = builtinEntries()
            assertTrue("index.conf lists no builtin profiles", entries.isNotEmpty())
            val moduleAssets = mutableSetOf<String>()
            for (entry in entries) {
                val controller = controllerFor(entry.file)
                val config = controller.load(entry.release)
                assertTrue("${entry.release}: profile did not resolve", config.hasProfile)
                assertEquals(
                    "${entry.release}: invalid fields ${config.invalidPaths}",
                    emptySet<String>(),
                    config.invalidPaths,
                )
                /* A module-only profile has no route and no native document:
                 * its pair is the asset + hash in the block. */
                if (isModuleOnly(config)) {
                    assertModulePair(entry, config)
                    config.module?.asset?.let { moduleAssets += it }
                    continue
                }
                val route = config.route
                assertNotNull("${entry.release}: route unresolved", route)

                /* The general list offers the active route (and its fallback)
                 * tuning, and nothing from another route. */
                val allowed = setOfNotNull(
                    route,
                    config.fallbackTo?.takeIf { it != "none" },
                )
                val routePaths = config.general.map { it.path }
                    .filter { it.startsWith("execution.routes.") }
                for (path in routePaths) {
                    val owner = path.removePrefix("execution.routes.").substringBefore('.')
                    assertTrue(
                        "${entry.release}: $path belongs to $owner, expected $allowed",
                        owner in allowed,
                    )
                }

                /* The resolved document re-encodes and decodes identically. */
                val bytes = controller.nativeDocument(config)
                assertNotNull("${entry.release}: no native document", bytes)
                val profile = Profile.fromBinary(bytes!!)
                assertNotNull("${entry.release}: native document failed to decode", profile)
                assertEquals(entry.release, profile!!.release)
                assertEquals(route, profile.route.token)
            }
            /* The bundle check, app-side: a module asset is referenced by a
             * bundled module profile, and no module asset is unreferenced. */
            val onDisk = context.assets.list("modules").orEmpty()
                .map { "modules/$it" }
                .toSet()
            assertEquals("bundled module assets must be referenced exactly once",
                onDisk, moduleAssets)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun isModuleOnly(config: com.sesame.app.domain.model.ProfileConfig): Boolean =
        "module" in config.pipelineOrder &&
            !com.sesame.app.data.profile.ProfileResolver.requiresW1(config.pipelineOrder)

    /** The bundled module asset exists and hashes to the block's value. */
    private fun assertModulePair(
        entry: Entry,
        config: com.sesame.app.domain.model.ProfileConfig,
    ) {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/${entry.file}"),
        ).asValueMap() ?: error("${entry.release}: unreadable profile")
        val block = document["module"].asValueMap()
            ?: error("${entry.release}: module block missing")
        val asset = block["asset"] as? String
            ?: error("${entry.release}: module asset missing")
        val expected = block["asset_sha256"] as? String
            ?: error("${entry.release}: module hash missing")
        assertEquals(
            "${entry.release}: resolved module spec",
            asset,
            config.module?.asset,
        )
        assertEquals(
            "${entry.release}: resolved module hash",
            expected,
            config.module?.assetSha256,
        )
        val actual = context.assets.open(asset).use { input ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
        assertEquals("${entry.release}: bundled module hash", expected, actual)
    }

    private fun leafPaths(nodes: List<com.sesame.app.domain.model.ProfileFieldNode>): List<String> =
        nodes.flatMap { node ->
            if (node.isGroup) leafPaths(node.children) else listOf(node.path)
        }

    @Test
    fun `advanced editor exposes the complete route and shared geometry`() = runBlocking {
        val root = Files.createTempDirectory("editor-fields").toFile()
        try {
            for (entry in builtinEntries()) {
                val controller = controllerFor(entry.file)
                val config = controller.load(entry.release)
                /* The editor's geometry tree is a W1-chain view; a module-only
                 * profile carries no route geometry. */
                if (isModuleOnly(config)) continue
                val paths = leafPaths(config.roots)
                assertTrue("${entry.release}: kernel_phys_load missing", "kernel_phys_load" in paths)
                for (field in listOf(
                    "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
                    "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags", "real_cred",
                    "cred", "comm", "tasks", "seccomp",
                )) {
                    assertTrue("${entry.release}: task_struct.$field missing", "task_struct.$field" in paths)
                }
                for (field in listOf("copy_size", "caps_count", "ref0_offset", "ref3_image")) {
                    assertTrue("${entry.release}: cred.$field missing", "cred.$field" in paths)
                }
                for (field in listOf(
                    "init_task", "init_cred", "empty_zero_page", "root_task_group",
                    "slide_nfulnl_logger", "slide_boot_id",
                )) {
                    assertTrue("${entry.release}: offset.$field missing", "offset.$field" in paths)
                }
                assertTrue("${entry.release}: kernelsnitch.collisions missing",
                    "kernelsnitch.collisions" in paths)
                /* The spray sizes from this value; a profile that silently
                 * drops the declared key falls back to the compiled 1280
                 * (25 objects/slab) without saying so. The bundled set must
                 * carry the family value (or an explicit null). */
                assertTrue("${entry.release}: kernelsnitch.mm_struct_sz missing",
                    "kernelsnitch.mm_struct_sz" in paths)
                assertTrue("${entry.release}: route branch missing",
                    paths.any { it.startsWith("route.") })
                /* Execution tuning belongs to the general page, never here. */
                assertTrue("${entry.release}: execution leaked into advanced tree",
                    paths.none { it.startsWith("execution") })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy shared defaults stay in sync with the bundled 6x templates`() {
        val loader = AssetConfigLoader(context)
        val cred = HoconSupport.parseValue(loader.load("kernel_profiles/credential-6x.conf"))
            .asValueMap()!!["cred"].asValueMap()!!
        val snitch = HoconSupport.parseValue(loader.load("kernel_profiles/kernelsnitch-6x.conf"))
            .asValueMap()!!["kernelsnitch"].asValueMap()!!

        /* LegacyProfileConverter seeds these values into imported reports and
         * carries its own copies; changing the assets requires updating it. */
        assertEquals(136L, (cred["copy_size"] as Number).toLong())
        assertEquals(48L, (cred["caps_offset"] as Number).toLong())
        assertEquals(5L, (cred["caps_count"] as Number).toLong())
        assertEquals(1L, (cred["usage_value"] as Number).toLong())
        assertEquals(-1L, (cred["caps_value"] as Number).toLong())
        assertEquals(4L, (snitch["collisions"] as Number).toLong())
    }
}
