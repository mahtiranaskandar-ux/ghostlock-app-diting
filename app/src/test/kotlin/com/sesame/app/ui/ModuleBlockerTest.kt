package com.sesame.app.ui

import com.sesame.app.data.profile.ModuleConfig
import com.sesame.app.domain.model.ProfileLayers
import com.sesame.app.ui.state.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The home and the parameters screen refuse a module chain whose delivery
 * facts are not all true; this pins the order and the null case so the card
 * and the screen cannot disagree about why a run is blocked.
 */
class ModuleBlockerTest {
    private val module = ModuleConfig(
        asset = "modules/5.4.233-qgki-gbb70cde46897.ko",
        assetSha256 = "64981aad" + "0".repeat(56),
        vendorFiles = listOf("/vendor/lib64/libstagefrighthw.so"),
    )

    private fun state(
        module: ModuleConfig? = this.module,
        bundled: Boolean = true,
        assetVerified: Boolean = true,
        magisk: String? = "com.topjohnwu.magisk",
    ): SessionState = SessionState(
        support = SessionState.SupportState(
            hasProfile = true,
            layers = ProfileLayers(bundledAuthoritative = bundled),
            module = module,
            moduleAssetVerified = assetVerified,
            magiskManager = magisk,
        ),
    )

    @Test
    fun `no module profile has no blocker`() {
        assertNull(moduleBlockerOf(state(module = null)))
    }

    @Test
    fun `a complete pair with a manager is runnable`() {
        assertNull(moduleBlockerOf(state()))
    }

    @Test
    fun `the bundled profile is the first requirement`() {
        assertEquals(
            ModuleBlocker.NotBundled,
            moduleBlockerOf(state(bundled = false, assetVerified = false, magisk = null)),
        )
    }

    @Test
    fun `the asset hash is checked before the manager`() {
        assertEquals(
            ModuleBlocker.AssetMissing,
            moduleBlockerOf(state(assetVerified = false, magisk = null)),
        )
    }

    @Test
    fun `a missing manager blocks the chain`() {
        assertEquals(ModuleBlocker.MagiskMissing, moduleBlockerOf(state(magisk = null)))
    }
}
