package com.privatechat.app.notes.util

import java.util.Calendar

/**
 * Countdown note: a note with a target date. The card shows how many
 * whole calendar days are left — no alarm, no deletion, just the count.
 * (Self-destruct is the separate feature that moves a note to Trash.)
 * Bangladesh has no daylight saving, so a plain 24h division of two
 * local midnights is exact here.
 */
object Countdown {

    /** Local midnight of the given time. */
    fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Whole calendar days from today (local) to the target date. 0 = today, negative = past. */
    fun daysUntil(targetMs: Long): Int {
        val today = startOfDay(System.currentTimeMillis())
        val target = startOfDay(targetMs)
        return ((target - today) / 86_400_000L).toInt()
    }
}
