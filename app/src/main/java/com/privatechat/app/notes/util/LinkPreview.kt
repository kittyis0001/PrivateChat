package com.privatechat.app.notes.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches a link's title + preview image (og tags) for a link card. */
object LinkPreviewFetcher {

    data class LinkPreview(val url: String, val title: String, val imageUrl: String?)

    private val URL_REGEX = Regex("""https?://[^\s)\"'<>]+""")

    fun findFirstUrl(text: String): String? = URL_REGEX.find(text)?.value

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) KittyNotes/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                // Read at most the first 200 KB — enough for <head>.
                val html = response.body?.source()?.use { it.readUtf8(200 * 1024L) }
                    ?: return@runCatching null
                val title = Regex(
                    """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<title[^>]*>([^<]+)</title>""",
                        RegexOption.IGNORE_CASE
                    ).find(html)?.groupValues?.getOrNull(1)?.trim()
                    ?: return@runCatching null
                val image = Regex(
                    """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
                LinkPreview(url, title.trim().take(120), image?.takeIf { it.isNotBlank() })
            }
        }.getOrNull()
    }
}
