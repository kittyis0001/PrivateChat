package com.privatechat.app.notes.calculator

/**
 * Expression evaluator for the calculator: + - × ÷ with the usual
 * precedence, decimals, unary minus, and a postfix % (the number
 * before it is divided by 100, so 3290×15% = 493.5). Grouping
 * commas are display-only and skipped. Returns null for anything
 * that is not a complete, valid calculation: empty input, a stray %,
 * division by zero, or a non-finite result.
 */
object CalculatorEvaluator {

    fun evaluate(raw: String): Double? {
        val tokens = try {
            tokenize(raw)
        } catch (_: Exception) {
            return null
        }
        if (tokens.isEmpty()) return null
        return try {
            evalTokens(tokens)
        } catch (_: Exception) {
            null
        }
    }

    private sealed interface Tok
    private data class Num(val value: Double) : Tok
    private data class Op(val c: Char) : Tok

    private fun tokenize(raw: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        val n = raw.length
        // True at the start and right after an operator: a number (or a
        // unary minus) is expected next.
        var expectNumber = true
        while (i < n) {
            val c = raw[i]
            when {
                c == ',' || c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = 0
                    // Grouping commas are display-only: "5,000" reads as 5000.
                    while (i < n && (raw[i].isDigit() || raw[i] == '.' || raw[i] == ',')) {
                        if (raw[i] == '.') dots++
                        i++
                    }
                    if (dots > 1) throw IllegalArgumentException("bad number")
                    val value = raw.substring(start, i).replace(",", "").toDoubleOrNull()
                        ?: throw IllegalArgumentException("bad number")
                    // A postfix % divides the number just read by 100.
                    if (i < n && raw[i] == '%') {
                        out.add(Num(value / 100.0))
                        i++
                    } else {
                        out.add(Num(value))
                    }
                    expectNumber = false
                }
                c == '%' -> throw IllegalArgumentException("stray %")
                c == '+' || c == '−' || c == '-' || c == '×' || c == '÷' -> {
                    val op = if (c == '−') '-' else c
                    if (expectNumber) {
                        if (op == '-') {
                            // Unary minus (leading, or after another
                            // operator): attach it to the next number.
                            var j = i + 1
                            val start = j
                            var dots = 0
                            while (j < n && (raw[j].isDigit() || raw[j] == '.' || raw[j] == ',')) {
                                if (raw[j] == '.') dots++
                                j++
                            }
                            if (j == start || dots > 1) throw IllegalArgumentException("bad unary")
                            val value = raw.substring(start, j).replace(",", "").toDoubleOrNull()
                                ?: throw IllegalArgumentException("bad unary")
                            var v = -value
                            if (j < n && raw[j] == '%') {
                                v /= 100.0
                                j++
                            }
                            out.add(Num(v))
                            i = j
                            expectNumber = false
                        } else if (out.isEmpty()) {
                            // A leading '+' is a no-op.
                            i++
                        } else {
                            throw IllegalArgumentException("double operator")
                        }
                    } else {
                        out.add(Op(op))
                        i++
                        expectNumber = true
                    }
                }
                else -> throw IllegalArgumentException("unknown char")
            }
        }
        // A trailing operator: evaluate the complete part before it.
        if (expectNumber && out.isNotEmpty() && out.last() is Op) {
            out.removeAt(out.lastIndex)
        }
        return out
    }

    private fun evalTokens(tokens: List<Tok>): Double? {
        // Shunting-yard to RPN, then evaluate the RPN.
        val output = ArrayDeque<Tok>()
        val ops = ArrayDeque<Char>()
        fun prec(c: Char) = if (c == '×' || c == '÷') 2 else 1
        for (t in tokens) {
            when (t) {
                is Num -> output.addLast(t)
                is Op -> {
                    while (ops.isNotEmpty() && prec(ops.last()) >= prec(t.c)) {
                        output.addLast(Op(ops.removeLast()))
                    }
                    ops.addLast(t.c)
                }
            }
        }
        while (ops.isNotEmpty()) output.addLast(Op(ops.removeLast()))
        val stack = ArrayDeque<Double>()
        for (t in output) {
            when (t) {
                is Num -> stack.addLast(t.value)
                is Op -> {
                    if (stack.size < 2) return null
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    val r = when (t.c) {
                        '+' -> a + b
                        '-' -> a - b
                        '×' -> a * b
                        '÷' -> {
                            if (b == 0.0) return null
                            a / b
                        }
                        else -> return null
                    }
                    if (!r.isFinite()) return null
                    stack.addLast(r)
                }
            }
        }
        return if (stack.size == 1) stack.first() else null
    }
}
