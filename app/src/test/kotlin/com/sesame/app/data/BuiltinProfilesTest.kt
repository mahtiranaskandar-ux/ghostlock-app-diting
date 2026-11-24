package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * projection and the external-document round trip. This is the test that
 * catches "a builtin profile no longer loads after a schema/format change".
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
     * names — a `devices` map that names no identity of the running device is
     * refused by PerDevicePhys. A profile without a map resolves
     * device-neutrally.
     */
    private fun controllerFor(file: String): AndroidProfileConfigController {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$file"),
        ).asValueMap()
        val device = document?.get("devices").asValueMap()?.keys?.firstOrNull()
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

                /* The general list offers the active route's tuning, and
                 * nothing from another route. */
                val allowed = setOf(route)
                val routePaths = config.general.map { it.path }
                    .filter { it.startsWith("execution.routes.") }
                for (path in routePaths) {
                    val owner = path.removePrefix("execution.routes.").substringBefore('.')
                    assertTrue(
                        "${entry.release}: $path belongs to $owner, expected $allowed",
                        owner in allowed,
                    )
                }

                /* The resolved document renders as the transport shape and
                 * names the same engine selection the config resolved. */
                val bytes = controller.nativeDocument(config)
                assertNotNull("${entry.release}: no native document", bytes)
                val rendered = checkNotNull(
                    HoconSupport.parseValue(String(bytes!!, Charsets.UTF_8)).asValueMap(),
                )
                assertEquals(entry.release, rendered["release"])
                assertEquals(
                    route,
                    rendered["ghostlock"].asValueMap()?.get("syscall"),
                )
                assertEquals(
                    3L,
                    (rendered["schema_version"] as Number).toLong(),
                )
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

    private fun flattenPaths(nodes: List<com.sesame.app.domain.model.ProfileFieldNode>): List<String> =
        nodes.flatMap { node -> listOf(node.path) + flattenPaths(node.children) }

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
        val block = (document["ghostlock"] as? Map<*, *>)?.get("module").asValueMap()
            ?: error("${entry.release}: module block missing")
        val asset = block["asset"] as? String
            ?: error("${entry.release}: module asset missing")
        val expected = block["sha256"] as? String
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
                /* The physical pair is a per-device entry; the numeric tree
                 * does not carry address strings, so the resolved pair is
                 * checked on the config. A profile that leaves the pair null
                 * keeps the native default. */
                val raw = HoconSupport.parseValue(
                    AssetConfigLoader(context).load("kernel_profiles/${entry.file}"),
                ).asValueMap()
                val devices = raw?.get("devices").asValueMap()
                val device = devices?.keys?.firstOrNull()
                val phys = (devices?.get(device) as? Map<*, *>)
                    ?.get("phys") as? Map<*, *>
                if ((phys?.get("load") as? String) != null) {
                    assertNotNull("${entry.release}: physLoad unset for a mapped pair", config.physLoad)
                } else {
                    assertNull("${entry.release}: physLoad without a map", config.physLoad)
                }
                for (field in listOf(
                    "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
                    "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags", "real_cred",
                    "cred", "comm", "tasks", "seccomp",
                )) {
                    assertTrue(
                        "${entry.release}: kernel.struct.task.$field missing",
                        "kernel.struct.task.$field" in paths,
                    )
                }
                for (field in listOf(
                    "kernel.struct.cred.copy_size",
                    "kernel.struct.cred.caps.count",
                    "kernel.struct.cred.refs.user.offset",
                    "kernel.struct.cred.refs.group_info.offset",
                )) {
                    assertTrue("${entry.release}: $field missing", field in paths)
                }
                for (field in listOf(
                    "init_task", "init_cred", "empty_zero_page", "root_task_group",
                    "nf_logger", "boot_id",
                )) {
                    assertTrue(
                        "${entry.release}: kernel.symbols.$field missing",
                        "kernel.symbols.$field" in paths,
                    )
                }
                assertTrue("${entry.release}: kernel.heap.collisions missing",
                    "kernel.heap.collisions" in paths)
                /* The spray sizes from this value; a profile that silently
                 * drops the declared key falls back to the compiled 1280
                 * (25 objects/slab) without saying so. The bundled set must
                 * carry the family value (or an explicit null). */
                assertTrue("${entry.release}: kernel.heap.stride missing",
                    "kernel.heap.stride" in paths)
                assertTrue("${entry.release}: route branch missing",
                    paths.any { it.startsWith("ghostlock.syscalls.") })
                /* Execution tuning belongs to the general page, never here. */
                assertTrue("${entry.release}: execution leaked into advanced tree",
                    paths.none { it.startsWith("ghostlock.execution") })
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
