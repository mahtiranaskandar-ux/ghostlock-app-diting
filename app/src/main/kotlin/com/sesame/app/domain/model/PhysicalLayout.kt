package com.sesame.app.domain.model

import java.util.Locale

/** True when the runtime SoC string names a MediaTek part. */
internal fun isMediaTekSoc(socName: String): Boolean {
    val soc = socName.lowercase(Locale.ROOT)
    return soc.contains("mediatek") || soc.contains("mtk") ||
        /* ro.soc.model / ro.board.platform are `MTxxxx` on MediaTek; mirror the
         * native detector's prefix rule. */
        soc.startsWith("mt") ||
        soc.contains("dimensity") || soc.contains("helio")
}

/** True when the runtime SoC string names a Qualcomm part. */
internal fun isQualcommSoc(socName: String): Boolean {
    val soc = socName.lowercase(Locale.ROOT)
    return soc.contains("qualcomm") || soc.contains("qti") || soc.contains("snapdragon")
}

/**
 * Where one half of the kernel's physical layout comes from.
 *
 * The native's `ResolvedAddresses::init_for_soc` is the authority: it refuses a
 * MediaTek profile with no physical load outright, and every other family
 * falls back to a compiled default. The app must reach the same verdict before
 * a profile is written or imported — a document that claims a GhostLock route
 * whose address translation cannot be resolved is a run that fails at W1.
 */
internal enum class PhysSource {
    /** The native resolves this half from a compiled default; the image only
     *  calibrates it. A null in the document is valid. */
    Defaulted,

    /** No compiled default: the image is the only source. Without it the
     *  GhostLock routes cannot be derived, so generation stops and import
     *  rejects the document. */
    Required,
}

/** One platform's requirement for each half of the pair. */
internal data class PhysRule(val load: PhysSource, val offset: PhysSource) {
    /** True when a missing half is a platform-satisfied default, not a hole. */
    val satisfiedByDefaults: Boolean
        get() = load == PhysSource.Defaulted && offset == PhysSource.Defaulted
}

/**
 * The one physical-layout rule, shared by generation and import verification.
 *
 * - MediaTek has no reliable compiled default (the sampled fleet uses
 *   0x40000000, 0x40080000/+0x40000000 and 0x80000000), and the native refuses
 *   an MTK profile without the load, so **both halves are required** and they
 *   come from vendor_boot/dtbo.
 * - Every other family resolves both halves from a compiled default
 *   (Qualcomm: `P0_KERNEL_PHYS_LOAD` / `QC_GKI_6_12_PHYS_LOAD` and
 *   `P0_PHYS_OFFSET`; Tensor and Xring have their own load constants). The
 *   native detector falls back to Qcom for an unrecognised SoC, so the app
 *   treats every non-MediaTek family the same way: **both halves are
 *   defaulted**, and the sidecar is a calibration, not a prerequisite.
 */
internal fun physRule(socName: String): PhysRule = when {
    isMediaTekSoc(socName) ->
        PhysRule(load = PhysSource.Required, offset = PhysSource.Required)

    else ->
        PhysRule(load = PhysSource.Defaulted, offset = PhysSource.Defaulted)
}

/**
 * Whether the parse flow can finish with the physical layout the images
 * supplied. Uses [physRule], the same rule [physPairErrors] applies to an
 * imported document, so generation and import cannot drift apart.
 *
 * [needsW1] is the document's own chain selection: a profile that selects only
 * the timer (or the module chain) translates no image address, so the pair is
 * irrelevant to it and must not gate generation.
 */
internal fun generationPhysComplete(
    socName: String,
    loadPresent: Boolean,
    offsetPresent: Boolean,
    needsW1: Boolean = true,
): Boolean {
    if (!needsW1) return true
    val rule = physRule(socName)
    return (rule.load == PhysSource.Defaulted || loadPresent) &&
        (rule.offset == PhysSource.Defaulted || offsetPresent)
}

/**
 * The pair errors that make an imported profile unrunnable on this device.
 * Verification rejects only what the runtime would refuse: a half that is
 * [PhysSource.Required] and absent. Everything else is the compiled default,
 * and the bundled profiles for those devices carry nulls. A profile that
 * selects no W1 chain (timer-only) never reads the pair and is exempt.
 */
internal fun physPairErrors(
    socName: String,
    loadPresent: Boolean,
    offsetPresent: Boolean,
    needsW1: Boolean = true,
): List<String> {
    if (!needsW1) return emptyList()
    val rule = physRule(socName)
    return buildList {
        if (rule.load == PhysSource.Required && !loadPresent) {
            add("devices.<id>.phys.load: missing for this device (no compiled default on this platform)")
        }
        if (rule.offset == PhysSource.Required && !offsetPresent) {
            add("devices.<id>.phys.offset: missing for this device (no compiled default on this platform)")
        }
    }
}
