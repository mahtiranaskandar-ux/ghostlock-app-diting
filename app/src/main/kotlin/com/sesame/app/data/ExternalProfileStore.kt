package com.sesame.app.data

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The user's profile document.
 *
 * Import reads the SAF document once and copies its text into app-private
 * storage; every later resolution reads that copy and never touches the
 * provider again (measured-db-plan §1 as amended by the import-copy plan).
 */
internal interface ExternalProfileStore {
    /** The SAF document text, for the one import read (and the old-selection migration). */
    fun read(uri: String): String?

    /** The app-private copy text, or null when the file is missing or unreadable. */
    fun readImportedProfile(): String?

    /** Writes the copy atomically; false when the write failed. */
    fun writeImportedProfile(text: String): Boolean

    /** Deletes the copy; a missing file is not an error. */
    fun clearImportedProfile()

    /** True when the copy file exists, independent of whether it parses. */
    fun importedProfileExists(): Boolean
}

internal class AndroidExternalProfileStore(private val context: Context) : ExternalProfileStore {
    private val directory = File(context.filesDir, ImportedProfileCopy.DirectoryName)
    private val file = File(directory, ImportedProfileCopy.FileName)

    override fun read(uri: String): String? = runCatching {
        context.contentResolver.openInputStream(uri.toUri())
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
    }.getOrNull()

    override fun readImportedProfile(): String? =
        runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()

    override fun writeImportedProfile(text: String): Boolean = runCatching {
        directory.mkdirs()
        val temp = File.createTempFile("imported_profile", ".tmp", directory)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.fd.sync()
            }
            check(temp.renameTo(file)) { "cannot replace $file" }
            true
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }.getOrDefault(false)

    override fun clearImportedProfile() {
        file.delete()
        directory.listFiles { candidate ->
            candidate.name.startsWith("imported_profile") && candidate.name.endsWith(".tmp")
        }?.forEach { it.delete() }
    }

    override fun importedProfileExists(): Boolean = file.isFile
}

/** The one app-private copy. */
internal object ImportedProfileCopy {
    const val DirectoryName = "profiles"
    const val FileName = "imported_profile.conf"

    /** Fixed label for the editor's export name; the copy has no user-visible name. */
    const val Label = FileName
}

/**
 * Parses one profile document (HOCON text, an object or a list of objects) and
 * returns every entry as authored. `include` targets are resolved
 * through [loadInclude] — the bundled assets in the app; imported documents are
 * not searched for siblings, because the model has no folder to search. An
 * app export is self-contained and never needs one.
 */
internal object ProfileDocumentParser {
    fun entries(
        text: String,
        loadInclude: ((String) -> String?)? = null,
    ): List<ValueMap>? {
        val expanded = expandIncludes(text, loadInclude, emptyList()) ?: return null
        val value = HoconSupport.parseValue(expanded) ?: return null
        val maps = when (value) {
            is Map<*, *> -> listOfNotNull(value.asValueMap())
            is List<*> -> value.mapNotNull { it.asValueMap() }
            else -> return null
        }
        /* Schema v3 only: the entries are returned as authored; a v1/v2 or
         * unversioned document is refused by the resolver's schema check. */
        val entries = maps.filter { it["release"] is String }
        return entries.takeIf { it.isNotEmpty() }
    }

    /** First entry carrying [release], converted; null when absent. */
    fun entryForRelease(text: String, release: String, loadInclude: ((String) -> String?)? = null): ValueMap? =
        entries(text, loadInclude)?.firstOrNull { it["release"] == release }

    private fun expandIncludes(
        text: String,
        loadInclude: ((String) -> String?)?,
        visiting: List<String>,
    ): String? {
        if (!text.contains("include ")) return text
        val expanded = StringBuilder()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("include ")) {
                expanded.append(line).append('\n')
                continue
            }
            val target = trimmed.removePrefix("include ").trim().trim('"')
            val key = target.substringAfterLast('/')
            if (key in visiting) continue
            val included = loadInclude?.invoke(target) ?: return null
            val nested = expandIncludes(included, loadInclude, visiting + key) ?: return null
            expanded.append(nested).append('\n')
        }
        return expanded.toString()
    }
}

/** SHA-256 of a document text, used by tests and diagnostic records. */
internal fun sha256Of(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
