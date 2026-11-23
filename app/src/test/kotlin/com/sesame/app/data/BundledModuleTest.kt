package com.sesame.app.data

import android.app.Application
import com.sesame.app.data.profile.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledModuleTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun spec(release: String = "5.4.233-qgki-gbb70cde46897"): ModuleConfig {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$release.conf"),
        ).asValueMap() ?: error("$release: unreadable profile")
        val block = document["module"].asValueMap()
            ?: error("$release: module block missing")
        return ModuleConfig(
            version = 1,
            asset = block["asset"] as String,
            assetSha256 = block["asset_sha256"] as String,
            vendorFiles = emptyList(),
        )
    }

    @Test
    fun `the bundled pair stages after the hash check`() {
        val dest = Files.createTempDirectory("module-stage").toFile()
        try {
            /* The home's readiness card uses verify without writing anything. */
            assertTrue("the bundled pair must verify", BundledModule.verify(context, spec()))
            assertFalse(
                "a wrong hash must not verify",
                BundledModule.verify(context, spec().copy(assetSha256 = "0".repeat(64))),
            )
            assertFalse(
                "a missing asset must not verify",
                BundledModule.verify(context, spec().copy(asset = "modules/does-not-exist.ko")),
            )

            val file = BundledModule.stage(context, spec(), dest)
            assertNotNull("the bundled asset did not stage", file)
            assertEquals(10_344L, file!!.length())

            val badHash = BundledModule.stage(
                context,
                spec().copy(assetSha256 = "0".repeat(64)),
                dest,
            )
            assertNull("a wrong hash must not stage", badHash)

            val missing = BundledModule.stage(
                context,
                spec().copy(asset = "modules/does-not-exist.ko"),
                dest,
            )
            assertNull("a missing asset must not stage", missing)
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun `both module pairs stage`() {
        val dest = Files.createTempDirectory("module-stage-both").toFile()
        try {
            for (release in listOf(
                "5.4.233-qgki-gbb70cde46897",
                "5.4.274-qgki-g76ac0b265f44",
            )) {
                val file = BundledModule.stage(context, spec(release), dest)
                assertNotNull("$release did not stage", file)
                assertEquals("$release size", 10_344L, file!!.length())
            }
        } finally {
            dest.deleteRecursively()
        }
    }
}
