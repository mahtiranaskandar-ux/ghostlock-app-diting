package com.sesame.app.data

import android.app.Application
import androidx.core.content.edit
import com.sesame.app.data.profile.PerDevicePhys
import com.sesame.app.domain.model.ProfileConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bundled 5.10 profiles leave their timing null and their physical pair in
 * a per-device map. The app must supply the measured diting defaults only on a
 * diting, and the generic tuning everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceDefaultsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.198-android12-9-00085-g226a9632f13d-ab11136126"

    private fun controller(device: String): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("device-defaults", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
        )

    private fun value(config: ProfileConfig, path: String): Long =
        config.general.first { it.path == path }.value

    @Test
    fun `diting gets the measured route timing`() = runBlocking {
        val config = controller("diting").load(release)
        assertEquals(30L, value(config, "execution.race.route_wait_ms"))
        assertEquals(10000L, value(config, "execution.race.setup_settle_us"))
        assertEquals(5000L, value(config, "execution.routes.select_stack.enter_delay_us"))
    }

    @Test
    fun `a device in the map gets the generic timing`() = runBlocking {
        val config = controller("yunluo").load(release)
        assertEquals(1000L, value(config, "execution.race.route_wait_ms"))
        assertEquals(50000L, value(config, "execution.race.setup_settle_us"))
        assertEquals(50000L, value(config, "execution.routes.select_stack.enter_delay_us"))
    }

    @Test
    fun `a device the physical map does not name is refused`() = runBlocking {
        val config = controller("not-diting").load(release)
        assertFalse(config.hasProfile)
        assertTrue(
            config.layers?.externalError.orEmpty().contains("does not name not-diting"),
        )
    }

    @Test
    fun `the bundled physical maps resolve diting, yunluo and rubens`() {
        val releases = listOf(
            release,
            "5.10.209-android12-9-00019-g4ea09a298bb4-ab12292661",
        )
        for (r in releases) {
            val text = AssetConfigLoader(context).load("kernel_profiles/$r.conf")
            val document = HoconSupport.parseValue(text).asValueMap()
                ?: error("$r is not an object")
            val cleaned = document
            fun pair(device: String) = com.sesame.app.data.profile.ProfileResolver.physOf(
                PerDevicePhys.resolve(cleaned, device),
            )
            /* The store states no DRAM base for the diting: the native's
             * compiled default (0x80000000) applies. */
            assertEquals(2818572288L to null, pair("diting"))
            assertEquals(1073741824L to 1073741824L, pair("yunluo"))
            assertEquals(1073741824L to 1073741824L, pair("rubens"))
            assertThrows(IllegalArgumentException::class.java) {
                PerDevicePhys.resolve(cleaned, "some-other-device")
            }
        }
    }

    @Test
    fun `the measured 6x device maps resolve`() {
        val cases = mapOf(
            "6.1.138-android14-11-g44bda9e8f6e9-ab13792638" to
                Triple("duchamp", 1073741824L, 1073741824L),
            "6.12.30-android16-5-g6e872b4863d6-ab13847919-4k" to
                Triple("spinel", 1073741824L, 1073741824L),
            "6.6.89-android15-8-g5a0ffb447c1d-ab13771415-4k" to
                Triple("dew", 1074266112L, 1073741824L),
        )
        for ((r, spec) in cases) {
            val (device, load, offset) = spec
            val text = AssetConfigLoader(context).load("kernel_profiles/$r.conf")
            val document = HoconSupport.parseValue(text).asValueMap()
                ?: error("$r is not an object")
            val cleaned = document
            val (actualLoad, actualOffset) = com.sesame.app.data.profile.ProfileResolver.physOf(
                PerDevicePhys.resolve(cleaned, device),
            )
            assertEquals("$r $device load", load, actualLoad)
            assertEquals("$r $device offset", offset, actualOffset)
        }
    }

    @Test
    fun `the vendor-device alias resolves the spinel map when Build_DEVICE is missi`() = runBlocking {
        val spinelRelease = "6.12.30-android16-5-g6e872b4863d6-ab13847919-4k"
        val controller = AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = context.getSharedPreferences("device-defaults-alias", 0)
                .also { it.edit().clear().commit() },
            deviceCodename = "missi",
            deviceAliases = listOf("spinel"),
        )
        val config = controller.load(spinelRelease)
        assertEquals(1073741824L, config.physLoad)
        assertEquals(1073741824L, config.physOffset)
    }
}
