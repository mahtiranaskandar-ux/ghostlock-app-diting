package com.sesame.app.data

/**
 * In-memory SD-card documents and the app-private copy for controller tests.
 * [importedReadable] models an I/O failure with the file still present.
 */
internal class FakeExternalProfileStore : ExternalProfileStore {
    val documents = linkedMapOf<String, String>()
    var imported: String? = null
    var importedReadable: Boolean = true

    fun add(uri: String, text: String): String {
        documents[uri] = text
        return uri
    }

    override fun read(uri: String): String? = documents[uri]

    override fun readImportedProfile(): String? = if (importedReadable) imported else null

    override fun writeImportedProfile(text: String): Boolean {
        imported = text
        importedReadable = true
        return true
    }

    override fun clearImportedProfile() {
        imported = null
        importedReadable = true
    }

    override fun importedProfileExists(): Boolean = imported != null
}
