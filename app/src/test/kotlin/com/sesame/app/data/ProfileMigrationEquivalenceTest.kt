package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import com.sesame.app.domain.model.ProfileConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every bundled profile must survive the external-document path unchanged: the
 * asset exactly as it ships (schema 3) and the same document through an
 * external selection must resolve to the same values and the same native
 * bytes. Schema v3 is the only accepted format — the v1/v2 and unversioned
 * documents are refused by the resolver, so there is no older leg to check.
 *
 * `tools/ship_golden_profiles.py --check` owns the store-to-bundle coverage
 * rule; [BuiltinProfilesTest] owns the bundle's own validity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileMigrationEquivalenceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val loader = AssetConfigLoader(context)

    private data class Entry(val release: String, val file: String)

    @Test
    fun `every bundled profile resolves through the external path`() = runBlocking {
        val entries = bundledEntries()
        assertTrue("index.conf lists no profiles", entries.isNotEmpty())

        for (entry in entries) {
            val nativeText = loader.load("kernel_profiles/${entry.file}")
            val native = checkNotNull(HoconSupport.parseValue(nativeText).asValueMap()) {
                "${entry.release}: bundled profile is not an object"
            }
            val device = deviceKey(native) ?: TestDevice
            val baseline = controller("baseline", device)
            val external = FakeExternalProfileStore()
            val imported = controller("imported", device, external)
            val baselineModel = baseline.load(entry.release)
            /* A module-only profile has no native document to compare: the
             * external path cannot authorize the module chain either. */
            if ("module" in baselineModel.pipelineOrder &&
                !com.sesame.app.data.profile.ProfileResolver.requiresW1(
                    baselineModel.pipelineOrder,
                )
            ) {
                continue
            }

            assertMatchesBundle(
                entry.release, entry.release, nativeText,
                baseline, baselineModel, imported, external,
            )
        }
    }

    /** The same document through the bundle and through an external selection. */
    private suspend fun assertMatchesBundle(
        label: String,
        release: String,
        text: String,
        baseline: AndroidProfileConfigController,
        baselineModel: ProfileConfig,
        imported: AndroidProfileConfigController,
        external: FakeExternalProfileStore,
    ) {
        val uri = external.add("content://$label", text)
        assertNotNull(
            "$label: could not select the document",
            imported.selectExternalProfile(uri, release),
        )
        val model = imported.load(release)
        assertTrue("$label: profile did not resolve", model.hasProfile)
        assertEquals(
            "$label: invalid fields ${model.invalidPaths}",
            emptySet<String>(),
            model.invalidPaths,
        )
        assertEquals("$label: route", baselineModel.route, model.route)
        assertEquals(
            "$label: resolved values",
            baseline.resolvedNumbers(release),
            imported.resolvedNumbers(release),
        )
        val baselineNative = checkNotNull(baseline.nativeDocument(baselineModel)) {
            "$label: no bundled native document"
        }
        val importedNative = checkNotNull(imported.nativeDocument(model)) {
            "$label: no external native document"
        }
        assertArrayEquals("$label: transport document", baselineNative, importedNative)
        val rendered = checkNotNull(
            HoconSupport.parseValue(String(importedNative, Charsets.UTF_8)).asValueMap(),
        )
        assertEquals("$label: document release", release, rendered["release"])
        assertEquals(
            "$label: document syscall",
            baselineModel.route,
            rendered["ghostlock"].asValueMap()?.get("syscall"),
        )
    }

    private fun bundledEntries(): List<Entry> {
        val index = HoconSupport.parseValue(loader.load("kernel_profiles/index.conf")).asValueMap()
            ?: error("index.conf is not an object")
        return index["profiles"].asValueList().orEmpty()
            .mapNotNull { it.asValueMap() }
            .mapNotNull { entry ->
                val release = entry["release"] as? String ?: return@mapNotNull null
                val file = entry["file"] as? String ?: return@mapNotNull null
                Entry(release, file)
            }
            .filterNot { it.release.endsWith("-template") }
    }

    /** One device identity from the document, so the physical pair resolves. */
    private fun deviceKey(document: ValueMap): String? =
        (document["devices"] as? Map<*, *>)?.keys?.firstOrNull()?.toString()

    private fun controller(
        preferencesName: String,
        device: String,
        external: FakeExternalProfileStore = FakeExternalProfileStore(),
    ): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = context.getSharedPreferences(preferencesName, 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
            deviceAliases = emptyList(),
        )

    private companion object {
        private const val TestDevice = "test-device"
    }
}
