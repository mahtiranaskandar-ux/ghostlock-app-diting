package com.sesame.app.data.profile

/**
 * The canonical document writer: the resolved profile tree as self-contained
 * JSON — quoted keys and strings, numbers, objects and arrays, no
 * includes/comments/substitutions.
 *
 * Every backend reads this one document. Integers whose magnitude exceeds
 * 2^53 are emitted as `"0x..."` strings, because a double-based JSON parser
 * would silently corrupt them; kernel addresses are already quoted hex strings
 * in the profile and pass through.
 */
object ProfileDocument {
    private const val MAX_EXACT = 1L shl 53

    /** Serializes one value tree as the canonical document text. */
    fun renderJson(document: Map<String, Any?>): String {
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
