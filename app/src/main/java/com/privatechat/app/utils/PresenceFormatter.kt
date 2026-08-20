package com.privatechat.app.utils

import java.text.SimpleDateFormat
import java.util.*

object PresenceFormatter {
    private const val ONLINE_THRESHOLD_MS = 12_000L

    fun format(last: Long): String {
        val diff = System.currentTimeMillis() - last
        if (diff < ONLINE_THRESHOLD_MS) return "Online"

        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = last }
        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(last))

        val isToday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

        return when {
            isToday -> "Last seen today at $timeStr"
            isYesterday -> "Last seen yesterday at $timeStr"
            else -> "Last seen " + SimpleDateFormat("d MMM 'at' h:mm a", Locale.getDefault()).format(Date(last))
        }
    }

    fun messageTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
}
