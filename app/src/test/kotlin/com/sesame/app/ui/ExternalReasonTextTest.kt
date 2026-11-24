package com.sesame.app.ui

import android.app.Application
import com.sesame.app.R
import com.sesame.app.data.profile.ProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The one schema-refusal sentence must reach the localized resource. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalReasonTextTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `the canonical schema refusal is localized`() {
        assertEquals(
            context.getString(R.string.external_reason_schema, "2", "3"),
            externalReasonText(ProfileResolver.schemaErrorMessage(2), context),
        )
    }

    @Test
    fun `an absent schema is localized`() {
        assertEquals(
            context.getString(R.string.external_reason_schema, "absent", "3"),
            externalReasonText(ProfileResolver.schemaErrorMessage(null), context),
        )
    }
}
