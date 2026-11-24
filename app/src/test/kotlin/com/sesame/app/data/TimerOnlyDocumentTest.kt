package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.domain.model.ProfileConfig
import com.sesame.app.domain.model.ProfileImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The timer-only projection: a document that claims only the 98260 route. It
 * carries no native chain, so it needs no physical pair — the MediaTek case
 * where the images cannot supply one but the timer still derives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimerOnlyDocumentTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("timer-only", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
    }

    private fun bundled(): String =
        AssetConfigLoader(context).load("kernel_profiles/$release.conf")

    private fun mediatekController(): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            buildIdentity = "test-build",
            deviceCodename = "diting",
            socName = "MediaTek MT6897",
        )

    @Test
    fun `the projection keeps the release and the timer block only`() {
        val document = TimerOnlyDocument.from(bundled())
        assertTrue(document != null)
        val text = document!!
        val flat = text.replace(Regex("\\s+"), " ")
        assertTrue(text, text.contains("release = \"$release\""))
        assertTrue(text, text.contains("timer {"))
        assertTrue(flat, flat.contains("ghostlock = null"))
        assertTrue(flat, flat.contains("schema_version = 3"))
        assertFalse(text, text.contains("task_struct"))
        assertFalse(text, text.contains("kernel_phys_load"))
        assertFalse(text, text.contains("lock_anchor"))
    }

    @Test
    fun `a timer-only document imports on MediaTek without a pair`() = runBlocking {
        val controller = mediatekController()
        val text = TimerOnlyDocument.from(bundled()) ?: error("no projection")
        val result = controller.selectExternalProfile(external.add("content://timer", text), release)
        assertTrue(result is ProfileImportResult.Accepted)
        val model: ProfileConfig = controller.load(release)
        assertTrue(model.hasProfile)
        assertTrue("invalid fields ${model.invalidPaths}", model.invalidPaths.isEmpty())
        assertEquals(listOf("timer"), model.routeCandidates.map { it.name })
        assertEquals("timer", model.selectedRoute)
        assertEquals(null, model.route)
    }

    @Test
    fun `a native profile without the pair is still refused on MediaTek`() = runBlocking {
        val controller = mediatekController()
        val result = controller.selectExternalProfile(
            external.add("content://native", bundled()),
            release,
        )
        assertTrue(result is ProfileImportResult.Rejected)
        val errors = (result as ProfileImportResult.Rejected).errors
        assertTrue(errors.toString(), errors.any { it.startsWith("devices.<id>.phys.offset") })
    }
}
