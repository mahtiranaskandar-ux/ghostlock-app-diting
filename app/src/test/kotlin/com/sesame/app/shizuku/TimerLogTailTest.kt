package com.sesame.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * The tailer must never lose the tool's last lines: a probe exits in ~100 ms,
 * after printing its facts and the match line. Complete lines are read while
 * the process runs; the final drain reads the trailing partial line too.
 */
class TimerLogTailTest {
    private fun tempFile(text: String): File =
        Files.createTempFile("timer-log", ".log").toFile().apply { writeText(text) }

    @Test
    fun `the running tailer emits complete lines only`() {
        val file = tempFile("a\nb\npart")
        val offset = AtomicLong(0)
        val lines = mutableListOf<String>()
        TimerLogTail.read(file, offset, includeTrailingPartial = false) { lines += it }
        assertEquals(listOf("a", "b"), lines)
        assertEquals("a\nb\n".length.toLong(), offset.get())
    }

    @Test
    fun `the final drain emits the trailing partial line`() {
        val file = tempFile("a\nb\npart")
        val offset = AtomicLong("a\nb\n".length.toLong())
        val lines = mutableListOf<String>()
        TimerLogTail.read(file, offset, includeTrailingPartial = true) { lines += it }
        assertEquals(listOf("part"), lines)
        assertEquals(file.length(), offset.get())
    }

    @Test
    fun `a partial line completed later is emitted whole`() {
        val file = tempFile("a\npar")
        val offset = AtomicLong("a\n".length.toLong())
        val lines = mutableListOf<String>()
        TimerLogTail.read(file, offset, includeTrailingPartial = false) { lines += it }
        assertEquals(emptyList<String>(), lines)
        file.appendText("tial\n")
        TimerLogTail.read(file, offset, includeTrailingPartial = false) { lines += it }
        assertEquals(listOf("partial"), lines)
    }

    @Test
    fun `a truncated file restarts from zero`() {
        val file = tempFile("x\n")
        val offset = AtomicLong(100)
        val lines = mutableListOf<String>()
        TimerLogTail.read(file, offset, includeTrailingPartial = true) { lines += it }
        assertEquals(listOf("x"), lines)
    }
}
