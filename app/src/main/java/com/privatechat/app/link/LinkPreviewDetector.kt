package com.privatechat.app.link

/**
 * Matches a message's text against YouTube Shorts / Instagram Reel /
 * Facebook Reel-or-video URL shapes. Only fires when the ENTIRE
 * (trimmed) message is just the link — the normal "share a reel/short"
 * pattern (see the reference screenshot) — not a link buried inside a
 * longer sentence, so a casual "check this out: https://..." keeps
 * rendering as plain autoLink-ed text exactly as before.
 */
object LinkPreviewDetector {

    enum class Platform { YOUTUBE, INSTAGRAM, FACEBOOK }

    data class Match(val platform: Platform, val url: String)

    private val YOUTUBE_SHORTS = Regex(
        "^https?://(?:www\\.)?(?:youtube\\.com/shorts/[\\w-]+|youtu\\.be/[\\w-]+)(?:\\?\\S*)?$",
        RegexOption.IGNORE_CASE
    )
    private val INSTAGRAM_REEL = Regex(
        "^https?://(?:www\\.)?instagram\\.com/reels?/[\\w-]+/?(?:\\?\\S*)?$",
        RegexOption.IGNORE_CASE
    )
    private val FACEBOOK_VIDEO = Regex(
        "^https?://(?:www\\.)?(?:facebook\\.com/(?:reel/[\\w-]+|[\\w.-]+/videos/[\\w-]+)|fb\\.watch/[\\w-]+)/?(?:\\?\\S*)?$",
        RegexOption.IGNORE_CASE
    )

    fun detect(rawText: String): Match? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        return when {
            YOUTUBE_SHORTS.matches(text) -> Match(Platform.YOUTUBE, text)
            INSTAGRAM_REEL.matches(text) -> Match(Platform.INSTAGRAM, text)
            FACEBOOK_VIDEO.matches(text) -> Match(Platform.FACEBOOK, text)
            else -> null
        }
    }
}
