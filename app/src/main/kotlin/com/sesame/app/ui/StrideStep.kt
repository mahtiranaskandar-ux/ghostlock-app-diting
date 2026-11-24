package com.sesame.app.ui

import com.sesame.app.domain.model.StrideProbe
import com.sesame.app.domain.model.StrideProbeOutcome

/** What the generation flow does once the stride probe returns. */
internal sealed interface StrideStep {
    /** The probe produced a table: show the picker. */
    data class Picker(val probe: StrideProbe) : StrideStep

    /** The probe was not applicable (the profile targets another kernel): save. */
    data object Save : StrideStep

    /** The probe ran and failed: end on the failed step, never the save step. */
    data class Fail(val reason: String) : StrideStep
}

/**
 * A probe that was attempted (the document targets the running kernel) and
 * failed must not be treated like one that was skipped — a profile saved
 * without its measured stride cannot be imported or run. Only a null outcome
 * (probe not applicable) reaches [StrideStep.Save].
 */
internal fun strideStep(outcome: StrideProbeOutcome?): StrideStep = when (outcome) {
    null -> StrideStep.Save
    is StrideProbeOutcome.Probed -> StrideStep.Picker(outcome.probe)
    is StrideProbeOutcome.Failed -> StrideStep.Fail(outcome.reason)
}
