package com.galaxy.assettrail.core.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS).
 *
 * Waypoint payloads are hashed in this form so that anyone (an auditor's own verifier, in any
 * language) re-creates the exact bytes behind the on-chain MEMO_HASH (spec §4.2, principle 4).
 */
object CanonicalJson {

    fun encode(element: JsonElement): String = StringBuilder().apply { write(element) }.toString()

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            JsonNull -> append("null")
            is JsonObject -> {
                append('{')
                // String.compareTo orders by UTF-16 code units, which is what JCS requires.
                element.entries.sortedWith { a, b -> a.key.compareTo(b.key) }.forEachIndexed { i, (key, value) ->
                    if (i > 0) append(',')
                    writeString(key)
                    append(':')
                    write(value)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) append(',')
                    write(value)
                }
                append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(element.content)
                element.booleanOrNull != null -> append(element.content)
                else -> append(formatNumber(element.content.toDouble()))
            }
        }
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> appendUnicodeEscape(c)
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> appendUnicodeEscape(c) // lone surrogate, as ES2019 JSON.stringify does
                else -> append(c)
            }
            i++
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(c: Char) {
        append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }

    /** ECMAScript Number::toString, which JCS prescribes for numbers. */
    fun formatNumber(value: Double): String {
        require(value.isFinite()) { "JSON can't carry $value" }
        if (value == 0.0) return "0" // also -0
        if (value < 0) return "-" + formatNumber(-value)

        val (digits, n) = shortestDigits(value)
        val k = digits.length
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val sign = if (exponent >= 0) "+" else "-"
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + sign + kotlin.math.abs(exponent)
            }
        }
    }

    /**
     * The fewest significant digits s (and decimal exponent n, value = 0.s × 10^n) that read back as
     * exactly [value]; among equally short candidates, the one nearest [value].
     */
    private fun shortestDigits(value: Double): Pair<String, Int> {
        val exact = BigDecimal(value)
        for (precision in 1..17) {
            val best = sequenceOf(RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING)
                .map { exact.round(MathContext(precision, it)) }
                .filter { it.toDouble() == value }
                .minByOrNull { it.subtract(exact).abs() }
                ?: continue
            val stripped = best.stripTrailingZeros()
            val digits = stripped.unscaledValue().toString()
            return digits to (digits.length - stripped.scale())
        }
        error("No round-trip representation for $value")
    }
}
