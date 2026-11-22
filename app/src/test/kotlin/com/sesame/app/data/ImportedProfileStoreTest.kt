package com.sesame.app.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The app-private copy: one fixed file, atomic overwrite, clear on demand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportedProfileStoreTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = AndroidExternalProfileStore(context)

    @Before
    fun clean() {
        store.clearImportedProfile()
    }

    @Test
    fun `write read and clear the copy`() {
        assertFalse(store.importedProfileExists())
        assertNull(store.readImportedProfile())

        assertTrue(store.writeImportedProfile("release = \"x\"\n"))
        assertTrue(store.importedProfileExists())
        assertEquals("release = \"x\"\n", store.readImportedProfile())

        store.clearImportedProfile()
        assertFalse(store.importedProfileExists())
        assertNull(store.readImportedProfile())
    }

    @Test
    fun `a second write replaces the copy`() {
        assertTrue(store.writeImportedProfile("release = \"one\"\n"))
        assertTrue(store.writeImportedProfile("release = \"two\"\n"))

        assertEquals("release = \"two\"\n", store.readImportedProfile())
    }

    @Test
    fun `existence is independent of the content`() {
        assertTrue(store.writeImportedProfile("not hocon {{{"))
        assertTrue(store.importedProfileExists())
        assertEquals("not hocon {{{", store.readImportedProfile())
    }
}
