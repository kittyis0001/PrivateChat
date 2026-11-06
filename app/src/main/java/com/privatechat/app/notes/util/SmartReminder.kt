package com.privatechat.app.notes.util

import java.util.Calendar

/**
 * Detects a date+time written inside a note (Bengali or English) and
 * offers to set a reminder for it. Only fires when an hour is present
 * AND there is a time context (day-part word, am/pm, ":" or a
 * meeting-like word) — so "কাল ৫টা বই কিনবো" (5 books) is NOT
 * mistaken for 5 o'clock. The UI only ever *offers*; the user
 * confirms or dismisses.
 */
object SmartReminderDetector {

    data class DetectedReminder(val triggerMillis: Long, val label: String)

    private val BN_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    private val BN_OUT_DIGITS = mapOf(
        '0' to '০', '1' to '১', '2' to '২', '3' to '৩', '4' to '৪',
        '5' to '৫', '6' to '৬', '7' to '৭', '8' to '৮', '9' to '৯'
    )

    fun toAsciiDigits(s: String): String =
        s.map { BN_DIGITS[it] ?: it }.joinToString("")

    fun toBengaliDigits(s: String): String =
        s.map { BN_OUT_DIGITS[it] ?: it }.joinToString("")

    fun detect(text: String, nowMillis: Long): DetectedReminder? {
        val normalized = toAsciiDigits(text)
        val lower = normalized.lowercase()
        // Day offset.
        val (dayOffset, dayWord) = when {
            normalized.contains("পরশু") -> 2 to "পরশু"
            normalized.contains("কাল") || lower.contains("tomorrow") -> 1 to "কাল"
            normalized.contains("আজ") || lower.contains("today") -> 0 to "আজ"
            else -> return null
        }
        // Hour: "10টা", "10:30", "10 am", "10 pm", "10:30টা".
        val hourRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(টা|am|pm|AM|PM)?""")
        val match = hourRegex.find(normalized) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val suffix = match.groupValues[3]
        val hasColon = match.groupValues[2].isNotEmpty()
        val hasAmPm = suffix.equals("am", ignoreCase = true) || suffix.equals("pm", ignoreCase = true)
        val hasDayPart = listOf("সকাল", "দুপুর", "বিকেল", "সন্ধ্যা", "রাত")
            .any { lower.contains(it) }
        val hasEventWord = listOf(
            "মিটিং", "meeting", "ক্লাস", "class", "পরীক্ষা", "exam",
            "ডাক্তার", "doctor", "অফিস", "office"
        ).any { lower.contains(it) }
        // Without any time context this is probably a count, not a time.
        if (!hasColon && !hasAmPm && !hasDayPart && !hasEventWord) return null

        val isPm = hasAmPm && suffix.equals("pm", ignoreCase = true) ||
            lower.contains("রাত") || lower.contains("সন্ধ্যা") || lower.contains("বিকেল")
        if (suffix.isBlank()) {
            when {
                lower.contains("দুপুর") -> hour += 12
                lower.contains("বিকেল") -> hour += 12
                lower.contains("সন্ধ্যা") || lower.contains("রাত") -> hour += 12
            }
        } else if (isPm && hour < 12) {
            hour += 12
        } else if (suffix.equals("am", ignoreCase = true) && hour == 12) {
            hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val trigger = cal.timeInMillis
        if (trigger <= nowMillis) return null
        val label = "$dayWord, ${toBengaliDigits(hour.toString())}:${toBengaliDigits(minute.toString().padStart(2, '0'))}"
        return DetectedReminder(trigger, label)
    }
}
