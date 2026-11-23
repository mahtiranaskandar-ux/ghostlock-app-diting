package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import com.sesame.app.data.profile.PerDevicePhys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The editor export must keep a schema-v2 physical map: the running device's
 * entry takes the edited value, every other device entry survives, and a
 * load-only source (the diting `uefi` case) keeps its `null` offset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorPhysMapTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun entry(text: String): ValueMap =
        HoconSupport.parseValue(text).asValueMap() ?: error("not a document")

    private fun controller(release: String, device: String): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("editor-phys-$release", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
            forcedSelection = ProfileSelection.Bundled(release),
        )

    @Test
    fun `a device absent from the map cannot load the profile to edit`() = runBlocking {
        /* The 5.10.101 map names yunluo/matisse; a diting may not run or edit
         * it (PerDevicePhys refuses an unlisted device), so the editor never
         * invents an entry for it. */
        val release = "5.10.101-android12-9-00001-gf4c0e37dbcde-ab8596533"
        val controller = controller(release, "diting")
        assertFalse(controller.load(release).hasProfile)
        assertNull(controller.renderResolved(release))
    }

    @Test
    fun `the editor export preserves a load-only physical map`() {
        val release = "5.10.198-android12-9-00085-g226a9632f13d-ab11136126"
        val asset = AssetConfigLoader(context).load("kernel_profiles/$release.conf")
        val raw = HoconSupport.parseValue(asset).asValueMap() ?: error("no bundle")
        raw["kernel_phys_offset"] = null
        val externalText = HoconSupport.render(raw)
        val external = FakeExternalProfileStore().also { it.writeImportedProfile(externalText) }
        val controller = AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = context.getSharedPreferences("editor-phys-load-only", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = "diting",
            forcedSelection = ProfileSelection.External,
        )

        val document = entry(controller.renderResolved(release) ?: error("no render"))
        assertEquals(3, (document["schema_version"] as Number).toInt())
        assertTrue(document["kernel_phys_load"] is Map<*, *>)
        assertNull(document["kernel_phys_offset"])
        val diting = PerDevicePhys.resolveFor(document, listOf("diting")).document
        assertEquals(2818572288L, diting["kernel_phys_load"])
        assertNull(diting["kernel_phys_offset"])
    }
}
