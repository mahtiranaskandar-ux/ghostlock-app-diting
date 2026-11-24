package com.sesame.app.data.profile

import com.sesame.app.data.HoconSupport
import com.sesame.app.data.ValueMap
import com.sesame.app.data.asValueMap
import com.sesame.app.data.route.RouteKind
import java.io.File

/**
 * Renders the bundled HOCON profiles into the one JSON document every backend
 * reads. It shares [ProfileMerger], [ProfileResolver] and [ProfileTransport]
 * with the app, so the exporter and the runtime can no longer drift. The work
 * list comes from `index.conf`; a missing/invalid entry fails the export
 * instead of silently dropping a profile.
 */
object ProfileExporter {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3) {
            "usage: ProfileExporter <profilesDir> <outputDir> <expectedOutputDir>"
        }
        val srcDir = File(args[0]).canonicalFile
        val outDir = File(args[1]).canonicalFile
        val expectedOutDir = File(args[2]).canonicalFile
        require(srcDir.isDirectory) { "profiles dir does not exist: $srcDir" }
        validateOutputDir(srcDir, outDir, expectedOutDir)

        val index = parseFile(File(srcDir, "index.conf"), srcDir)
            ?: error("index.conf is missing or invalid")
        val profiles = index["profiles"]
        require(profiles is List<*>) { "index.conf profiles must be a list" }
        val entries = profiles.mapIndexed { index, raw ->
            raw.asValueMap() ?: error("index.conf profiles[$index] is not an object")
        }

        val routes = RouteKind.entries.map { it.token }
        val tuningExecution = parseFile(File(srcDir, "execution-tuning.conf"), srcDir)
            ?.get("execution").asValueMap()
        val routePresets = routes.mapNotNull { route ->
            parseFile(File(srcDir, "execution-${route.replace('_', '-')}.conf"), srcDir)
                ?.get("execution").asValueMap()
                ?.get("routes").asValueMap()
                ?.get(route).asValueMap()
                ?.let { route to it }
        }.toMap()

        /* Stage into a sibling temp dir and swap in only on full success, so a
         * failure never leaves a half-deleted or partial output dir. */
        val staging = File(outDir.parentFile, "${outDir.name}.staging-${System.nanoTime()}")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        var count = 0
        for (entry in entries) {
            val file = entry["file"] as? String ?: error("index entry missing 'file'")
            val release = entry["release"] as? String ?: error("index entry missing 'release'")
            /* Reference templates are not device profiles and are not exported. */
            if (release.endsWith("-template")) continue
            val parsed = parseFile(File(srcDir, file), srcDir) ?: error("cannot parse $file")
            require(ProfileResolver.isCleanedShape(parsed)) {
                "$file is not the cleaned v3 layout; regenerate it with this build"
            }
            val cleaned = parsed
            /* The exporter is device-neutral: the per-device entries are
             * dropped (the native default). */
            val resolved = PerDevicePhys.resolveNeutral(cleaned)
            val actualRelease = resolved["release"] as? String ?: error("$file has no release")
            require(actualRelease == release) { "index/file release mismatch for $file" }
            val merged = ProfileMerger.resolveMerged(
                deviceRelease = actualRelease,
                builtin = resolved,
                imported = null,
                tuningExecution = tuningExecution,
                routePresets = routePresets,
            )
            val errors = ProfileResolver.validateMerged(merged)
            if (errors.isNotEmpty()) {
                error("$file fails validation: ${errors.joinToString()}")
            }
            /* A module-only profile has no native document: the DirtyFrag stage
             * is invoked with its plan, not a profile document. */
            val order = ProfileResolver.pipelineOrder(merged)
            if ("module" in order && !ProfileResolver.requiresW1(order)) {
                println("exportKernelProfiles: $actualRelease (module-only; no native document)")
                continue
            }
            /* The app's defaults: the alphabetically first declared syscall and,
             * within it, the first declared carrier. */
            val syscalls = ProfileResolver.syscallsOf(merged).sortedBy { it.first }
            val route = syscalls.firstOrNull()?.first
            val carrier = syscalls.firstOrNull()?.second?.firstOrNull()
            val document = ProfileTransport.render(
                merged,
                route = route,
                carrier = carrier,
            )
            File(staging, "$actualRelease.json").writeText(document)
            count++
            println("exportKernelProfiles: $actualRelease (${document.length} bytes)")
        }

        /* Swap without ever deleting the previous output first: move it aside,
         * then move the staging dir in, and roll back if the move fails. */
        if (outDir.exists()) {
            val backup = File(outDir.parentFile, "${outDir.name}.bak-${System.nanoTime()}")
            if (!outDir.renameTo(backup)) error("cannot move current output aside: $outDir")
            if (!staging.renameTo(outDir)) {
                backup.renameTo(outDir)
                error("cannot move staging dir into place: $outDir (previous output restored)")
            }
            backup.deleteRecursively()
        } else if (!staging.renameTo(outDir)) {
            error("cannot move staging dir into place: $outDir")
        }
        println("exportKernelProfiles: $count profile(s) -> ${outDir.absolutePath}")
    }

    /**
     * Refuses any output that is not exactly the configured generated directory
     * (and never the source tree). [expectedDir] is the build directory the
     * Gradle task owns, so an attacker-supplied path can never cause a replace
     * of unrelated data even if it contains a `build` segment.
     */
    internal fun validateOutputDir(srcDir: File, outDir: File, expectedDir: File) {
        val src = srcDir.path
        val out = outDir.path
        require(outDir.parentFile != null && outDir.name.isNotEmpty()) {
            "invalid output dir: $outDir"
        }
        require(out == expectedDir.path) {
            "output dir must be the configured export dir $expectedDir, got $outDir"
        }
        val underBuild = out.contains("${File.separator}build${File.separator}") ||
                outDir.parentFile!!.name == "build"
        require(underBuild) { "output dir must live under a build directory: $outDir" }
        require(out != src) { "output dir must not be the profiles dir: $outDir" }
        require(!out.startsWith(src + File.separator)) {
            "output dir must not be inside the profiles dir: $outDir"
        }
        require(!src.startsWith(out + File.separator)) {
            "output dir must not contain the profiles dir: $outDir"
        }
        require(!out.contains("${File.separator}app${File.separator}src${File.separator}")) {
            "refusing to write into the source tree: $outDir"
        }
    }

    private fun parseFile(file: File, baseDir: File): ValueMap? {
        if (!file.isFile) return null
        return runCatching { HoconSupport.parseValue(expand(file, baseDir, linkedSetOf())).asValueMap() }
            .getOrNull()
    }

    private fun expand(file: File, baseDir: File, visiting: MutableSet<String>): String {
        val name = file.absolutePath
        if (!visiting.add(name)) return ""
        val text = runCatching { file.readText() }.getOrElse {
            visiting.remove(name)
            return ""
        }
        val builder = StringBuilder()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val target = when {
                trimmed.startsWith("#") || trimmed.startsWith("//") -> null
                trimmed.startsWith("include ") ->
                    trimmed.removePrefix("include ").trim().trim('"').takeIf { it.isNotEmpty() }

                else -> null
            }
            if (target == null) {
                builder.append(line).append('\n')
            } else {
                builder.append(expand(File(baseDir, target), baseDir, visiting)).append('\n')
            }
        }
        visiting.remove(name)
        return builder.toString()
    }
}
