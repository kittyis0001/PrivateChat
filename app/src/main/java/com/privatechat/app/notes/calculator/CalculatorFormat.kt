package com.privatechat.app.notes.calculator

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Display formatting for the calculator: thousand separators, at
 * most 10 decimals, no trailing zeros. Very large or tiny results
 * fall back to the plain Double string (scientific notation).
 */
object CalculatorFormat {

    /** Formats a finished result, e.g. 2418 -> "2,418", 0.3 -> "0.3". */
    fun formatResult(value: Double): String {
        if (!value.isFinite()) return "Error"
        val a = abs(value)
        if (a != 0.0 && (a >= 1e15 || a < 1e-10)) return value.toString()
        val scaled = BigDecimal.valueOf(value)
            .setScale(10, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        return groupIntegerPart(scaled.toPlainString())
    }

    /** Formats an in-progress expression for display (groups each number). */
    fun formatExpression(raw: String): String {
        val clean = raw.replace(",", "")
        val out = StringBuilder()
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < clean.length && (clean[i].isDigit() || clean[i] == '.')) i++
                out.append(groupIntegerPart(clean.substring(start, i)))
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Groups the integer part of a decimal string: 3290 -> "3,290". */
    fun groupIntegerPart(text: String): String {
        if (text.isEmpty()) return text
        val negative = text.startsWith("-")
        val body = if (negative) text.substring(1) else text
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val fracPart = if (dot >= 0) body.substring(dot) else ""
        if (intPart.length <= 3) return text
        val grouped = StringBuilder()
        var count = 0
        for (k in intPart.length - 1 downTo 0) {
            grouped.append(intPart[k])
            count++
            if (count == 3 && k > 0) {
                grouped.append(',')
                count = 0
            }
        }
        return (if (negative) "-" else "") + grouped.reverse().toString() + fracPart
    }
}
