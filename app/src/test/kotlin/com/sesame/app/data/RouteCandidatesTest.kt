package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The route superset is a pure profile parse: a native branch plus the timer
 * block is already a two-candidate profile. The picker's choice is remembered
 * per release and self-heals when the remembered route is no longer viable.
 * See docs/analysis/route-superset-plan.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteCandidatesTest {
    private fun document(bytes: ByteArray?): ValueMap =
        checkNotNull(
            HoconSupport.parseValue(String(checkNotNull(bytes), Charsets.UTF_8)).asValueMap(),
        )

    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638"
    private val file = "$release.conf"

    private fun controller(prefsName: String, prefs: SharedPreferences? = null): AndroidProfileConfigController {
        val document = HoconSupport.parseValue(
            AssetConfigLoader(context).load("kernel_profiles/$file"),
        ).asValueMap()
        val device = document?.get("devices").asValueMap()?.keys?.firstOrNull()
        return AndroidProfileConfigController(
            context = context,
            externalProfiles = FakeExternalProfileStore(),
            preferences = prefs ?: context.getSharedPreferences(prefsName, 0)
                .also { it.edit().clear().commit() },
            deviceCodename = device,
        )
    }

    private fun controllerFor(
        fileName: String,
        device: String,
        prefsName: String,
    ): AndroidProfileConfigController = AndroidProfileConfigController(
        context = context,
        externalProfiles = FakeExternalProfileStore(),
        preferences = context.getSharedPreferences(prefsName, 0)
            .also { it.edit().clear().commit() },
        deviceCodename = device,
    )

    @Test
    fun `the select route lists both carriers and the choice drives the wire`() = runBlocking {
        val controller = controller("route-carriers-a")
        val initial = controller.load(release)
        /* The compat alternative is feasible for select on this image; the
         * multicast route has only its compat carrier. */
        assertEquals(
            listOf("native", "compat"),
            initial.routeCandidates.first { it.name == "select_stack" }.carriers,
        )
        assertEquals(
            listOf("compat"),
            initial.routeCandidates.first { it.name == "multicast_waiter" }.carriers,
        )
        assertTrue(controller.selectRoute(release, "select_stack"))

        val native = controller.load(release)
        assertEquals(listOf("native", "compat"), native.carrierCandidates)
        assertEquals("native", native.selectedCarrier)
        val nativeDoc = document(controller.nativeDocument(native))
        assertEquals("native", nativeDoc["ghostlock"].asValueMap()?.get("carrier"))
        assertEquals("select_stack", nativeDoc["ghostlock"]?.asValueMap()?.get("syscall"))
        assertEquals(
            1L,
            nativeDoc.getLongAt("ghostlock.syscalls.select_stack.native.waiter_shift"),
        )

        assertTrue(controller.selectCarrier(release, "select_stack", "compat"))
        val compat = controller.load(release)
        assertEquals("compat", compat.selectedCarrier)
        val compatDoc = document(controller.nativeDocument(compat))
        assertEquals("compat", compatDoc["ghostlock"]?.asValueMap()?.get("carrier"))
        assertEquals(
            488L,
            compatDoc.getLongAt("ghostlock.syscalls.select_stack.compat.waiter_home"),
        )
        assertEquals(
            496L,
            compatDoc.getLongAt("ghostlock.syscalls.select_stack.compat.dst_depth"),
        )
        assertEquals(
            "time64",
            compatDoc["ghostlock"].asValueMap()
                ?.get("syscalls").asValueMap()
                ?.get("select_stack").asValueMap()
                ?.get("compat").asValueMap()
                ?.get("futex_abi"),
        )

        /* A stale/unknown carrier is refused and the choice falls back. */
        assertFalse(controller.selectCarrier(release, "select_stack", "bogus"))
        assertEquals("native", controller.load(release).selectedCarrier)
    }

    @Test
    fun `dirtyfrag is offered only with both ladder engines and self-heals`() = runBlocking {
        val ditingRelease = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"
        val controller = controllerFor("$ditingRelease.conf", "diting", "route-carriers-df")
        val config = controller.load(ditingRelease)
        assertTrue(config.dirtyFragSelectable)
        assertTrue(config.dirtyFragOn)
        assertTrue(controller.selectDirtyFrag(ditingRelease, false))
        val off = controller.load(ditingRelease)
        assertTrue(off.dirtyFragSelectable)
        assertFalse(off.dirtyFragOn)

        /* This image's profile names GhostLock only: no choice to offer. */
        val duChamp = controller("route-carriers-df2")
        assertFalse(duChamp.load(release).dirtyFragSelectable)
        assertFalse(duChamp.selectDirtyFrag(release, false))
    }

    @Test
    fun `the native branches and the timer block are the candidates, alphabetically`() = runBlocking {
        val config = controller("route-candidates-a").load(release)
        assertEquals(
            listOf(
                "multicast_waiter", "select_stack", "sigreturn", "tcp_zerocopy", "timer",
            ),
            config.routeCandidates.map { it.name },
        )
        assertFalse(config.routeCandidates.first().timer)
        assertTrue(config.routeCandidates.last().timer)
        /* No order: the default is the alphabetically first viable route. */
        assertEquals("multicast_waiter", config.selectedRoute)
        assertEquals("multicast_waiter", config.route)
    }

    @Test
    fun `the timer choice is remembered and the editor keeps the native route`() = runBlocking {
        val controller = controller("route-candidates-b")
        assertTrue(controller.selectRoute(release, "timer"))
        val config = controller.load(release)
        assertEquals("timer", config.selectedRoute)
        assertEquals("multicast_waiter", config.route)
    }

    @Test
    fun `a stale choice is cleared at load`() = runBlocking {
        val prefs = context.getSharedPreferences("route-candidates-c", 0)
        prefs.edit { clear() }
        prefs.edit { putString("route_choice_$release", "bogus_route") }
        val config = controller("route-candidates-c", prefs).load(release)
        assertEquals("multicast_waiter", config.selectedRoute)
        assertEquals(null, prefs.getString("route_choice_$release", null))
    }

    @Test
    fun `a non-viable selection is refused and cleared`() = runBlocking {
        val controller = controller("route-candidates-d")
        assertFalse(controller.selectRoute(release, "bogus_route"))
        assertTrue(controller.selectRoute(release, "timer"))
    }

    @Test
    fun `the export keeps the timer block and the native route`() = runBlocking {
        val text = controller("route-candidates-e").renderResolved(release)
        assertNotNull(text)
        assertTrue(text!!.contains("timer"))
        assertTrue(text.contains("select_stack"))
    }
}
