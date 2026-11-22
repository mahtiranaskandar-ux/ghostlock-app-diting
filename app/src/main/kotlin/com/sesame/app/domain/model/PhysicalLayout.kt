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
 * Whether the parse flow can finish with the physical layout the images
 * supplied. The rule is the same one import verification applies:
 *
 * - MediaTek has no reliable compiled default (the sampled fleet uses
 *   0x40000000, 0x40080000/+0x40000000 and 0x80000000), so both halves must
 *   come from vendor_boot/dtbo, and the native refuses an MTK profile without
 *   the load outright.
 * - Every other platform resolves both halves from its compiled defaults
 *   (Qualcomm: `P0_KERNEL_PHYS_LOAD` / `QC_GKI_6_12_PHYS_LOAD` and
 *   `P0_PHYS_OFFSET`), and its sidecars only ever supply the load — the offset
 *   has no image source there at all. The sidecar advice stays on screen as a
 *   convenience; skipping it must not fail generation.
 */
internal fun generationPhysComplete(
    socName: String,
    loadPresent: Boolean,
    offsetPresent: Boolean,
): Boolean = when {
    isMediaTekSoc(socName) -> loadPresent && offsetPresent
    else -> true
}

/**
 * The pair errors that make an imported profile unrunnable or mis-resolved on
 * this device. Verification rejects only what the runtime would refuse:
 * MediaTek needs both halves (no compiled default exists there), while every
 * other family resolves a missing half from its compiled default, so a null
 * on those is a valid document — as the bundled profiles for those devices
 * demonstrate.
 */
internal fun physPairErrors(
    socName: String,
    loadPresent: Boolean,
    offsetPresent: Boolean,
): List<String> {
    if (!isMediaTekSoc(socName)) return emptyList()
    return buildList {
        if (!loadPresent) add("kernel_phys_load: missing for this device")
        if (!offsetPresent) add("kernel_phys_offset: missing for this device")
    }
}
