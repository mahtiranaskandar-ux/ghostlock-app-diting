package com.sesame.app.data.profile

/**
 * The canonical backend document (W2 §1.5): the merged, validated profile as
 * self-contained JSON — quoted keys and strings, numbers, objects and arrays,
 * no includes/comments/substitutions, stable (sorted) key order.
 *
 * The app hands this document to a backend; each backend reads its block. The
 * timer tool reads `release` and `timer_98260` and ignores `kernel`/`native`.
 * The `module` block is the app's own pair manifest: no external backend reads
 * it, but it is rendered so a round-trip preserves it.
 * The native exploit keeps its typed binary wire in W2 (`NativeProfileDocument`
 * is its own encoding of the same merged map); migrating the native to this
 * document is a separate, versioned workstream.
 *
 * Rendering rules: integers whose magnitude exceeds 2^53 are emitted as
 * `"0x..."` strings, because a double-based JSON parser would silently corrupt
 * them; everything else stays a JSON number. The timer block's 64-bit kernel
 * addresses are already quoted hex strings in the profile and pass through.
 */
object ProfileDocument {
    /** Shared geometry and facts the native and the timer tool both read. */
    private val KernelKeys = listOf(
        "offset", "task_struct", "cred", "kernelsnitch",
        "kernel_phys_load", "kernel_phys_offset",
        "waiter_layout", "selinux", "w1_scratch", "kdp",
    )

    /** The native chain's selection and tuning. */
    private val NativeKeys = listOf("route", "fallback", "pipeline", "execution")

    /** The timer route's capability block. */
    private val TimerKey = Timer98260.BLOCK_KEY

    /** The module chain's pair manifest (read by the app, not a native backend). */
    private val ModuleKey = ModuleBlock.BLOCK_KEY

    private const val MAX_EXACT = 1L shl 53

    fun render(merged: Map<String, Any?>): String {
        val kernel = linkedMapOf<String, Any?>()
        for (key in KernelKeys) merged[key]?.let { kernel[key] = it }
        val native = linkedMapOf<String, Any?>()
        for (key in NativeKeys) merged[key]?.let { native[key] = it }
        val document = linkedMapOf<String, Any?>(
            "release" to merged["release"],
            "schema_version" to merged["schema_version"],
            "kernel_major" to merged["kernel_major"],
            "kernel" to kernel,
            "native" to native,
            TimerKey to merged[TimerKey],
            ModuleKey to merged[ModuleKey],
        )
        val out = StringBuilder()
        writeValue(out, document, 0)
        out.append('\n')
        return out.toString()
    }

    private fun writeValue(out: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is Map<*, *> -> writeObject(out, value, depth)
            is List<*> -> writeArray(out, value, depth)
            is Boolean -> out.append(value.toString())
            is Number -> writeNumber(out, value)
            else -> writeString(out, value.toString())
        }
    }

    private fun writeObject(out: StringBuilder, value: Map<*, *>, depth: Int) {
        if (value.isEmpty()) {
            out.append("{}")
            return
        }
        val pad = "  ".repeat(depth + 1)
        out.append("{\n")
        val entries = value.entries
            .map { it.key.toString() to it.value }
            .sortedBy { it.first }
        entries.forEachIndexed { index, (key, item) ->
            out.append(pad)
            writeString(out, key)
            out.append(": ")
            writeValue(out, item, depth + 1)
            if (index != entries.lastIndex) out.append(',')
            out.append('\n')
        }
        out.append("  ".repeat(depth)).append('}')
    }

    private fun writeArray(out: StringBuilder, value: List<*>, depth: Int) {
        if (value.isEmpty()) {
            out.append("[]")
            return
        }
        val pad = "  ".repeat(depth + 1)
        out.append("[\n")
        value.forEachIndexed { index, item ->
            out.append(pad)
            writeValue(out, item, depth + 1)
            if (index != value.lastIndex) out.append(',')
            out.append('\n')
        }
        out.append("  ".repeat(depth)).append(']')
    }

    private fun writeNumber(out: StringBuilder, value: Number) {
        when (value) {
            is Int -> out.append(value)
            is Long -> writeLong(out, value)
            is Short -> out.append(value.toInt())
            is Byte -> out.append(value.toInt())
            is Double -> {
                require(value == Math.floor(value)) {
                    "ProfileDocument: non-integral number $value"
                }
                require(Math.abs(value) <= MAX_EXACT.toDouble()) {
                    "ProfileDocument: number $value cannot be represented exactly"
                }
                out.append(value.toLong())
            }
            is Float -> {
                require(value.toDouble() == Math.floor(value.toDouble())) {
                    "ProfileDocument: non-integral number $value"
                }
                out.append(value.toLong())
            }
            else -> out.append(value.toString())
        }
    }

    private fun writeLong(out: StringBuilder, value: Long) {
        if (value > MAX_EXACT || value < -MAX_EXACT) {
            writeString(out, "0x" + value.toULong().toString(16))
        } else {
            out.append(value)
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }
}
