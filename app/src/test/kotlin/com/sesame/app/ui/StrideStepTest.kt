package com.sesame.app.ui

import com.sesame.app.domain.model.StrideCandidateOption
import com.sesame.app.domain.model.StrideProbe
import com.sesame.app.domain.model.StrideProbeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-probe decision: a probe that ran and failed must end on the failed
 * step, never the save step (a profile without its measured stride cannot be
 * imported or run). Only a probe that did not run (null) saves.
 */
class StrideStepTest {
    private val probe = StrideProbe(
        candidates = listOf(StrideCandidateOption(960, 2, 2, 34)),
        rounds = 2,
        samples = 2,
        gcd = 0x500,
        decided = true,
    )

    @Test
    fun `a failed probe never reaches the save step`() {
        val step = strideStep(StrideProbeOutcome.Failed("the probe was killed (SIGKILL)"))
        assertTrue(step is StrideStep.Fail)
        assertEquals("the probe was killed (SIGKILL)", (step as StrideStep.Fail).reason)
    }

    @Test
    fun `a probed table opens the picker`() {
        val step = strideStep(StrideProbeOutcome.Probed(probe))
        assertTrue(step is StrideStep.Picker)
        assertEquals(probe, (step as StrideStep.Picker).probe)
    }

    @Test
    fun `a skipped probe saves`() {
        assertEquals(StrideStep.Save, strideStep(null))
    }
}
