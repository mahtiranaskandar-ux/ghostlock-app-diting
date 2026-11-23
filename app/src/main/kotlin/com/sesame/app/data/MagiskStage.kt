package com.sesame.app.data

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Locates an installed Magisk manager and stages the binaries the root script
 * copies for the late install (the G2a recipe). The manager APK is the source
 * of truth: the extracted native directory is used when present, the APK's
 * `lib/arm64-v8a` members otherwise, and `magisk32` always comes from the
 * APK's `lib/armeabi-v7a` member (a 64-bit app never sees that directory).
 *
 * The staged directory is inside the app's files dir; the DirtyFrag root
 * script runs as the init shell and can read it (the same directory already
 * receives the script's own log).
 */
object MagiskStage {
    /** The official manager first, then the common alpha fork. */
    val PACKAGES = listOf("com.topjohnwu.magisk", "io.github.vvb2060.magisk")

    private val NATIVE = listOf(
        "magisk" to "libmagisk.so",
        "magiskinit" to "libmagiskinit.so",
        "magiskpolicy" to "libmagiskpolicy.so",
        "magiskboot" to "libmagiskboot.so",
        "busybox" to "libbusybox.so",
        "bootctl" to "libbootctl.so",
        "init-ld" to "libinit-ld.so",
    )
    private val REQUIRED = setOf("magisk", "magiskinit", "magiskpolicy")

    data class Staged(val packageName: String, val dir: File)

    /** The installed manager whose binaries [stage] would copy, or null. */
    fun installed(context: Context): String? = managerInfo(context)?.packageName

    private fun managerInfo(context: Context): android.content.pm.PackageInfo? {
        val pm = context.packageManager
        return PACKAGES.asSequence()
            .mapNotNull { runCatching { pm.getPackageInfo(it, 0) }.getOrNull() }
            .firstOrNull()
    }

    /** The manager package whose binaries were staged, or null when none is usable. */
    fun stage(context: Context, dest: File): Staged? {
        val info = managerInfo(context) ?: return null
        val app = info.applicationInfo ?: return null
        val apk = File(app.sourceDir)
        dest.deleteRecursively()
        if (!dest.isDirectory && !dest.mkdirs()) return null
        val libDir = app.nativeLibraryDir?.let(::File)
        val staged = mutableSetOf<String>()
        for ((name, lib) in NATIVE) {
            val source = libDir?.let { File(it, lib) }?.takeIf { it.isFile }
            val ok = if (source != null) {
                runCatching { source.copyTo(File(dest, name), overwrite = true) }.isSuccess
            } else {
                unzipMember(apk, "lib/arm64-v8a/$lib", File(dest, name))
            }
            if (ok) {
                File(dest, name).setExecutable(true)
                staged += name
            }
        }
        /* The 32-bit daemon half; the root script skips a missing file. */
        if (unzipMember(apk, "lib/armeabi-v7a/libmagisk.so", File(dest, "magisk32"))) {
            File(dest, "magisk32").setExecutable(true)
        }
        if (!staged.containsAll(REQUIRED)) return null
        return Staged(info.packageName, dest)
    }

    private fun unzipMember(apk: File, member: String, out: File): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(member) ?: return@use false
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { sink -> input.copyTo(sink) }
            }
            true
        }
    }.getOrDefault(false)
}
