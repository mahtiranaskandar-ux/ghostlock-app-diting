package com.sesame.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The physical layout is a route prerequisite, and the one rule below is shared
 * by generation and import verification. The native's `init_for_soc` is the
 * authority: MediaTek refuses a profile with no load, every other family (and
 * an unrecognised SoC, which the native treats as Qcom) has a compiled default.
 */
class PhysicalLayoutTest {
    private val mediatek = "MediaTek MT6897"
    private val qualcomm = "QTI SM8475"
    private val tensor = "Google Tensor G4"
    private val unknown = "Exynos 2400"

    @Test
    fun `mediatek requires both halves`() {
        assertEquals(PhysSource.Required, physRule(mediatek).load)
        assertEquals(PhysSource.Required, physRule(mediatek).offset)
        assertTrue(generationPhysComplete(mediatek, loadPresent = true, offsetPresent = true))
        assertFalse(generationPhysComplete(mediatek, loadPresent = true, offsetPresent = false))
        assertFalse(generationPhysComplete(mediatek, loadPresent = false, offsetPresent = true))
        assertFalse(generationPhysComplete(mediatek, loadPresent = false, offsetPresent = false))
        assertEquals(2, physPairErrors(mediatek, false, false).size)
        assertEquals(1, physPairErrors(mediatek, loadPresent = true, offsetPresent = false).size)
        assertTrue(physPairErrors(mediatek, loadPresent = true, offsetPresent = true).isEmpty())
    }

    @Test
    fun `every other family resolves both halves from a compiled default`() {
        for (soc in listOf(qualcomm, tensor, unknown)) {
            assertEquals(soc, PhysSource.Defaulted, physRule(soc).load)
            assertEquals(soc, PhysSource.Defaulted, physRule(soc).offset)
            assertTrue(soc, generationPhysComplete(soc, loadPresent = false, offsetPresent = false))
            assertTrue(soc, physPairErrors(soc, loadPresent = false, offsetPresent = false).isEmpty())
        }
    }

    @Test
    fun `generation and import verification reach the same verdict`() {
        for (soc in listOf(mediatek, qualcomm, tensor, unknown)) {
            for (needsW1 in listOf(true, false)) {
                for (load in listOf(true, false)) {
                    for (offset in listOf(true, false)) {
                        assertEquals(
                            "$soc needsW1=$needsW1 load=$load offset=$offset",
                            generationPhysComplete(soc, load, offset, needsW1),
                            physPairErrors(soc, load, offset, needsW1).isEmpty(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a timer-only profile never needs the pair`() {
        for (soc in listOf(mediatek, qualcomm, tensor, unknown)) {
            assertTrue(soc, generationPhysComplete(soc, false, false, needsW1 = false))
            assertTrue(soc, physPairErrors(soc, false, false, needsW1 = false).isEmpty())
        }
    }
}
