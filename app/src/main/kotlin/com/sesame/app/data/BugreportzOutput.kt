package com.sesame.app.data

/**
 * The `bugreportz` command's result line.
 *
 * `bugreportz` prints `PROGRESS:<done>/<total>` lines while it runs and ends
 * with `OK:<absolute path>` on success (or `FAIL:<reason>`); the path is under
 * the shell app's private bugreports directory, which the app cannot open —
 * only the shell service can, which is why it streams the file to us.
 */
internal object BugreportzOutput {
    /** The report path from a completed run, or null when there is no OK line. */
    fun parsePath(output: String): String? = output
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("OK:") }
        ?.removePrefix("OK:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
