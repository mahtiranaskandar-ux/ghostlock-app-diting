package com.sesame.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G3 ROM preflight over a real libc++.
 *
 * Point `GHOSTLOCK_DIRTYFRAG_LIB` at the ROM's extracted libc++ (the file init
 * actually maps: `/system/lib64/libc++.so` or the runtime-apex copy) and run:
 *
 *   GHOSTLOCK_DIRTYFRAG_LIB=/path/libc++.so \
 *     ./gradlew :app:testDebugUnitTest --tests '*DirtyfragRomPreflightTest*'
 *
 * The test is skipped when the variable is unset, so the normal suite is
 * unaffected. It runs the same `inspect` + `checkCave` the app's preflight
 * runs on the device; the stub size comes from the shipped asset.
 */
class DirtyfragRomPreflightTest {
    @Test
    fun `the rom libcxx carries the sentry hook and a usable cave`() {
        val path = System.getenv("GHOSTLOCK_DIRTYFRAG_LIB") ?: return
        val lib = File(path)
        assertTrue("missing $path", lib.isFile)
        val stub = File("src/main/assets/dirtyfrag_stub.bin")
        assertTrue("missing stub asset ${stub.absolutePath}", stub.isFile)

        val layout = DirtyfragPlan.inspect(lib)
        /* The DirtyFrag pipeline's stub (the plain blob; the module chain has
         * its own, larger asset and is checked with that one). */
        val reasons = DirtyfragPlan.checkCave(layout, stub.length().toInt(), 0x50)
        println(
            "libcxx $path exec=0x${layout.execOffset.toString(16)} " +
                "filesz=0x${layout.execFilesz.toString(16)} hook=0x${layout.hook.toString(16)} " +
                "file=${layout.fileLength} reasons=$reasons",
        )
        assertTrue("cave check failed: $reasons", reasons.isEmpty())
    }
}
