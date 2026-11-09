package com.privatechat.app.notes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * 7-day weather forecast for the phone's approximate location, found
 * from its IP address — no location permission, no API key, no account.
 * Two free public APIs: ipwho.is (IP -> city + coordinates) and
 * Open-Meteo (coordinates -> daily forecast). Both are plain HTTPS GETs.
 *
 * IP location is city-level and approximate; the dialog says so.
 */
object WeatherForecast {

    data class Day(
        val date: String,
        val maxC: Int,
        val minC: Int,
        val rainChance: Int,
        val code: Int
    )

    data class Result(val place: String, val days: List<Day>)

    private val http = OkHttpClient()

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val ip = JSONObject(get("https://ipwho.is/"))
        if (!ip.optBoolean("success", false)) throw IOException("IP location failed")
        val lat = ip.optDouble("latitude")
        val lon = ip.optDouble("longitude")
        val city = ip.optString("city").ifBlank { ip.optString("country") }
        if (lat == 0.0 && lon == 0.0) throw IOException("IP location failed")
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weathercode" +
            "&timezone=auto&forecast_days=7"
        val daily = JSONObject(get(url)).optJSONObject("daily")
            ?: throw IOException("Forecast failed")
        val times = daily.optJSONArray("time") ?: throw IOException("Forecast failed")
        val maxs = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rains = daily.optJSONArray("precipitation_probability_max")
        val codes = daily.optJSONArray("weathercode")
        val days = (0 until times.length()).map { i ->
            Day(
                date = times.optString(i),
                maxC = maxs?.optDouble(i)?.toInt() ?: 0,
                minC = mins?.optDouble(i)?.toInt() ?: 0,
                rainChance = rains?.optInt(i) ?: 0,
                code = codes?.optInt(i) ?: 0
            )
        }
        Result(place = city.ifBlank { "Nearby" }, days = days)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    /** WMO weather code -> emoji + short English word. */
    fun condition(code: Int): String = when (code) {
        0 -> "☀️ Clear"
        1 -> "🌤️ Mostly clear"
        2 -> "⛅ Partly cloudy"
        3 -> "☁️ Cloudy"
        45, 48 -> "🌫️ Foggy"
        51, 53, 55 -> "🌦️ Drizzle"
        56, 57 -> "🌧️ Freezing drizzle"
        61 -> "🌧️ Light rain"
        63 -> "🌧️ Rain"
        65 -> "🌧️ Heavy rain"
        66, 67 -> "🌧️ Freezing rain"
        71, 73, 75 -> "❄️ Snow"
        77 -> "❄️ Snow grains"
        80 -> "🌧️ Light showers"
        81 -> "🌧️ Showers"
        82 -> "🌧️ Heavy showers"
        85, 86 -> "❄️ Snow showers"
        95 -> "⛈️ Thunderstorm"
        96, 99 -> "⛈️ Storm with hail"
        else -> "🌡️"
    }
}
