package com.sesame.app.data.profile

import com.sesame.app.data.HoconSupport
import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.route.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import java.io.File

class Sog10ProfileCoreRegressionTest {
    private val release = "5.15.189-android13-8-00004-g1c3825f8ac0a-ab14110541"

    @Test
    fun `SOG10 profile merges and validates`() {
        val profiles = File(repoRoot(), "app/src/main/assets/kernel_profiles")
        val source = File(profiles, "$release.conf")
        /* The r8 bundle cut (38ef1bdc) dropped this candidate from the assets;
         * keep the regression coverage for the day it is restored. A restored
         * asset is in the cleaned shape already. */
        Assume.assumeTrue("$release is not in the bundled assets", source.isFile)
        val builtin = parse(source)
        assertNull(builtin["devices"])

        val tuning = parse(File(profiles, "execution-tuning.conf"))["execution"].asValueMap()
        val presets = RouteKind.entries.mapNotNull { kind ->
            val file = File(profiles, "execution-${kind.token.replace('_', '-')}.conf")
            if (!file.isFile) return@mapNotNull null
            val preset = parse(file)
            kind.token to requireNotNull(
                preset["execution"].asValueMap()?.get("routes").asValueMap()
                    ?.get(kind.token).asValueMap(),
            )
        }.toMap()
        val merged: ValueMap = ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = builtin,
            imported = null,
            tuningExecution = tuning,
            routePresets = presets,
        )
        assertEquals(emptyList<ConfigError>(), ProfileResolver.validateMerged(merged))
        val ghostlock = requireNotNull(merged["ghostlock"].asValueMap())
        assertEquals("multicast_waiter", ghostlock["syscall"])
        val timer = requireNotNull(merged["timer"].asValueMap())
        assertEquals(21L, (timer["match_bits"] as Number).toLong())
    }

    private fun parse(file: File) = requireNotNull(
        HoconSupport.parseValue(file.readText()).asValueMap(),
    ) { "cannot parse ${file.path}" }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(5) {
            if (File(current, "app/src/main/assets/kernel_profiles").isDirectory) return current
            current = current.parentFile ?: error("cannot locate repository root")
        }
        error("cannot locate repository root")
    }
}
