package com.sesame.app.data.profile

import com.sesame.app.data.ValueMap
import com.sesame.app.data.getLongAt
import com.sesame.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredOverlayTest {
    private fun document(stride: Long?): ValueMap = valueMapOf(
        "schema_version" to 3L,
        "release" to "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284",
        "devices" to null,
        "kernel" to valueMapOf(
            "heap" to valueMapOf("stride" to stride, "collisions" to 8L),
        ),
        "ghostlock" to null,
        "timer" to null,
    )

    private fun fact(value: Long, path: String = "kernel.heap.stride") =
        OverlayFact(path = path, value = value, source = "root-slabinfo")

    @Test
    fun `a null document value takes the measurement`() {
        val result = MeasuredOverlay.apply(document(null), listOf(fact(960)))

        assertEquals(960L, result.document.getLongAt("kernel.heap.stride"))
        val record = result.records.single()
        assertEquals("kernel.heap.stride", record.path)
        assertNull(record.documentValue)
        assertEquals(960L, record.effectiveValue)
        assertEquals("root-slabinfo", record.source)
        assertFalse(record.disagrees)
    }

    @Test
    fun `a different document value is replaced and marked as a disagreement`() {
        val result = MeasuredOverlay.apply(document(1024), listOf(fact(960)))

        assertEquals(960L, result.document.getLongAt("kernel.heap.stride"))
        val record = result.records.single()
        assertEquals(1024L, record.documentValue)
        assertEquals(960L, record.effectiveValue)
        assertTrue(record.disagrees)
    }

    @Test
    fun `an equal value is recorded without a disagreement`() {
        val result = MeasuredOverlay.apply(document(960), listOf(fact(960)))

        assertEquals(960L, result.document.getLongAt("kernel.heap.stride"))
        assertFalse(result.records.single().disagrees)
    }

    @Test
    fun `a path outside the measured set is ignored`() {
        val result = MeasuredOverlay.apply(
            document(null),
            listOf(
                fact(960),
                fact(4, path = "kernel.heap.collisions"),
                fact(1, path = "ghostlock.syscall"),
            ),
        )

        assertEquals(960L, result.document.getLongAt("kernel.heap.stride"))
        assertEquals(8L, result.document.getLongAt("kernel.heap.collisions"))
        assertEquals(1, result.records.size)
    }

    @Test
    fun `the source document is not mutated`() {
        val source = document(null)

        MeasuredOverlay.apply(source, listOf(fact(960)))

        assertNull(source.getLongAt("kernel.heap.stride"))
    }

    @Test
    fun `no facts leaves the document unchanged`() {
        val source = document(1024)
        val result = MeasuredOverlay.apply(source, emptyList())

        assertEquals(1024L, result.document.getLongAt("kernel.heap.stride"))
        assertTrue(result.records.isEmpty())
        assertEquals(1024L, source.getLongAt("kernel.heap.stride"))
    }
}
