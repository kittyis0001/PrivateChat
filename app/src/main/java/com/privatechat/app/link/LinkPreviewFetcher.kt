package com.privatechat.app.link

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Best-effort thumbnail lookup for a detected link preview card.
 * YouTube's public oEmbed endpoint is official and reliable (no API
 * key needed). Instagram/Facebook don't offer that without a Meta
 * access token, so those fall back to scraping the page's own
 * `og:image` meta tag — this works for a lot of public reels (Meta
 * serves crawler-friendly HTML so links unfurl on Messenger/Discord/
 * Twitter too), but not all, since some reel pages are gated behind a
 * login wall. Callers should always render a clean branded fallback
 * card first and treat this as a progressive upgrade, never a
 * blocking requirement — a failed/timed-out/absent result is normal,
 * not an error state.
 */
object LinkPreviewFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Small in-memory cache so re-binding the same message while
    // scrolling doesn't refetch every time. Not persisted — that's
    // fine, it's just a progressive-enhancement thumbnail.
    private val cache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean =
            size > 100
    }

    private val ogImagePattern = Regex(
        """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /**
     * [callback] is always invoked on the main thread with either a
     * thumbnail URL or null (no thumbnail available — render the
     * branded fallback and stop there).
     */
    fun fetchThumbnail(match: LinkPreviewDetector.Match, callback: (String?) -> Unit) {
        synchronized(cache) {
            if (cache.containsKey(match.url)) {
                val cached = cache[match.url]
                mainHandler.post { callback(cached) }
                return
            }
        }

        Thread {
            val thumbnail = try {
                when (match.platform) {
                    LinkPreviewDetector.Platform.YOUTUBE -> fetchYoutubeThumbnail(match.url)
                    else -> fetchOgImage(match.url)
                }
            } catch (e: Exception) {
                null
            }
            synchronized(cache) { cache[match.url] = thumbnail }
            mainHandler.post { callback(thumbnail) }
        }.start()
    }

    private fun fetchYoutubeThumbnail(url: String): String? {
        val oembedUrl = "https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(url, "UTF-8")}&format=json"
        val request = Request.Builder().url(oembedUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val match = Regex(""""thumbnail_url"\s*:\s*"([^"]+)"""").find(body) ?: return null
            return match.groupValues[1].replace("\\/", "/")
        }
    }

    private fun fetchOgImage(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (compatible; facebookexternalhit/1.1; +http://www.facebook.com/externalhit_uatext.php)"
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null
            val match = ogImagePattern.find(html) ?: return null
            return match.groupValues[1].replace("&amp;", "&")
        }
    }
}
