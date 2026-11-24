package com.sesame.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sesame.app.domain.model.ProfileImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Import validation covers every route branch the document declares, not only
 * the one the resolver would activate. A branch missing a required field is a
 * document that silently loses that route (the resolver drops it from the
 * viable set and runs another one), which is exactly what import rejects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileBranchValidationTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    private lateinit var preferences: SharedPreferences
    private lateinit var external: FakeExternalProfileStore
    private lateinit var controller: AndroidProfileConfigController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("branch-validation", 0)
            .also { it.edit().clear().commit() }
        external = FakeExternalProfileStore()
        controller = AndroidProfileConfigController(
            context = context,
            externalProfiles = external,
            preferences = preferences,
            buildIdentity = "test-build",
            deviceCodename = "diting",
        )
    }

    private fun bundled(): String =
        AssetConfigLoader(context).load("kernel_profiles/$release.conf")

    @Test
    fun `the pristine bundled document imports`() = runBlocking {
        val uri = external.add("content://pristine", bundled())
        assertTrue(controller.selectExternalProfile(uri, release) is ProfileImportResult.Accepted)
    }

    @Test
    fun `a broken non-active branch is rejected and names its field`() = runBlocking {
        /* Removing waiter_shift makes select_stack non-viable; sigreturn stays
         * viable, so the old behaviour silently ran sigreturn instead. */
        val broken = bundled().replace("    waiter_shift = -2\n", "")
        assertNotEquals(bundled().length, broken.length)
        val result = controller.selectExternalProfile(external.add("content://broken", broken), release)
        assertTrue(result is ProfileImportResult.Rejected)
        val errors = (result as ProfileImportResult.Rejected).errors
        assertTrue(
            errors.toString(),
            errors.any { it.startsWith("ghostlock.syscalls.select_stack.native.waiter_shift") },
        )
    }
}
