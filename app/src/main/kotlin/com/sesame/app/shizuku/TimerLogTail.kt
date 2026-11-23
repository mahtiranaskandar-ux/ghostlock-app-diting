package com.sesame.app.shizuku

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Incremental reader for the timer tool's log file. The tool's output is a
 * file (never a pipe into binder, which would apply backpressure inside the
 * trigger window). The running tailer reads complete lines; after the process
 * exits, the final drain reads the rest, including a trailing partial line —
 * a probe exits in ~100 ms and its last lines must not be lost.
 */
internal object TimerLogTail {
    /**
     * Reads from [offset] to EOF, emitting complete lines. With
     * [includeTrailingPartial], the bytes after the last newline are emitted
     * as the final line and the offset advances past them; otherwise they stay
     * unread so the next call re-reads the partial line once it completes.
     */
    fun read(
        logFile: File,
        offset: AtomicLong,
        includeTrailingPartial: Boolean,
        emit: (String) -> Unit,
    ) {
        if (!logFile.isFile) return
        val pending = StringBuilder()
        runCatching {
            RandomAccessFile(logFile, "r").use { handle ->
                if (offset.get() > handle.length()) offset.set(0)
                handle.seek(offset.get())
                while (true) {
                    val byte = handle.read()
                    if (byte == -1) break
                    if (byte == '\n'.code) {
                        val line = pending.toString()
                        pending.clear()
                        offset.set(handle.filePointer)
                        if (line.isNotEmpty()) emit(line)
                    } else {
                        pending.append(byte.toChar())
                    }
                }
                if (includeTrailingPartial && pending.isNotEmpty()) {
                    offset.set(handle.filePointer)
                    emit(pending.toString())
                }
            }
        }
    }
}
