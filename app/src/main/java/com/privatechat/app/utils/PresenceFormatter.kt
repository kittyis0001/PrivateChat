package com.privatechat.app.utils

import com.privatechat.app.data.model.PresenceStatus
import java.text.SimpleDateFormat
import java.util.*

object PresenceFormatter {

    // Authoritative: "online" is written the instant markOnline()/
    // markOffline() run (Activity onStart/onStop) or the instant
    // onDisconnect() fires server-side, and the presence listener uses
    // addValueEventListener, so this reflects a state change the moment
    // Firebase delivers it — no polling window, no "still shows Online
    // for a few seconds after leaving" lag.
    fun format(status: PresenceStatus): String {
        if (status.online) return "Online"
        return formatLastSeen(status.last)
    }

    private fun formatLastSeen(last: Long): String {
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
