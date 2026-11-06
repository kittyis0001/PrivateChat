package com.privatechat.app.notes.util

/** Auto title + expense total, pure Kotlin (no Android dependency). */
object NoteSmart {

    private val BN_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    fun toAsciiDigits(s: String): String =
        s.map { BN_DIGITS[it] ?: it }.joinToString("")

    /** First non-empty content line (max 40 chars), unless a title exists. */
    fun autoTitle(title: String, content: String): String {
        if (title.isNotBlank()) return title
        val first = content.lineSequence()
            .map {
                it.trim()
                    .replace("**", "")
                    .removePrefix("✓").removePrefix("☐")
                    .removePrefix("[x]").removePrefix("[ ]")
                    .trim()
            }
            .firstOrNull { it.isNotEmpty() } ?: return ""
        return first.take(40)
    }

    /**
     * Sums numbers in an expense list. A line counts only when it
     * looks like an expense: it has a currency marker (৳/Tk/টাকা) or
     * it is a short line ending with a number. Returns null when
     * fewer than 2 numbers are found, so ordinary notes never show
     * a bogus total.
     */
    fun calculateTotal(text: String): Double? {
        val numbers = mutableListOf<Double>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val ascii = toAsciiDigits(line)
            val hasCurrency = ascii.contains("৳") || ascii.contains("টাকা") ||
                ascii.contains("Tk", ignoreCase = true) ||
                ascii.contains("taka", ignoreCase = true)
            val endsWithNumber = line.length <= 40 &&
                Regex("""\d[\d,]*\.?\d*\s*$""").containsMatchIn(ascii)
            if (!hasCurrency && !endsWithNumber) return@forEach
            val m = Regex("""(\d[\d,]*\.?\d*)""").find(ascii) ?: return@forEach
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
            numbers.add(v)
        }
        return if (numbers.size >= 2) numbers.sum() else null
    }

    /** "500" for whole numbers, "500.50" otherwise. */
    fun formatTotal(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format("%.2f", value)
}
