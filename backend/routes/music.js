const express = require("express");
const { search, trending, recommendByCaption, resolveYoutubeAudioUrl } = require("../services/music");

const router = express.Router();

const MAX_QUERY_LENGTH = 100;
const MAX_CAPTION_LENGTH = 300;

function isNonEmptyString(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

router.get("/music/search", async (req, res) => {
  const q = req.query.q;
  if (!isNonEmptyString(q, MAX_QUERY_LENGTH)) {
    return res.status(400).json({ error: "invalid_query" });
  }
  try {
    const songs = await search(q.trim());
    return res.status(200).json({ songs });
  } catch (err) {
    console.error("[music/search] failed:", err.message);
    return res.status(502).json({ error: "search_failed" });
  }
});

router.get("/music/trending", async (_req, res) => {
  try {
    const songs = await trending();
    return res.status(200).json({ songs });
  } catch (err) {
    console.error("[music/trending] failed:", err.message);
    return res.status(502).json({ error: "trending_failed" });
  }
});

// "For You" tab — see services/music.js's own comment: this is a
// simpler keyword-based mood match, not an AI (Gemini) analysis like
// the reference, since there's no AI API key configured here. caption
// is optional; an empty one just falls back to a generic chill mood.
router.post("/music/recommend", async (req, res) => {
  const caption = req.body?.caption;
  if (caption !== undefined && caption !== null && !isNonEmptyString(String(caption), MAX_CAPTION_LENGTH)) {
    return res.status(400).json({ error: "invalid_caption" });
  }
  try {
    const result = await recommendByCaption(caption ? String(caption) : "");
    return res.status(200).json(result);
  } catch (err) {
    console.error("[music/recommend] failed:", err.message);
    return res.status(502).json({ error: "recommend_failed" });
  }
});

// Basic YouTube video ID shape check (11 chars, URL-safe base64-ish
// alphabet) — cheap guard against obviously-invalid input before
// spending a request on ytdl-core.
const VIDEO_ID_PATTERN = /^[\w-]{6,15}$/;

router.get("/music/youtube-audio", async (req, res) => {
  const videoId = req.query.videoId;
  if (typeof videoId !== "string" || !VIDEO_ID_PATTERN.test(videoId)) {
    return res.status(400).json({ error: "invalid_video_id" });
  }
  try {
    const audioUrl = await resolveYoutubeAudioUrl(videoId);
    if (!audioUrl) {
      return res.status(200).json({ audioUrl: null });
    }
    return res.status(200).json({ audioUrl });
  } catch (err) {
    console.error("[music/youtube-audio] failed:", err.message);
    return res.status(502).json({ error: "resolve_failed" });
  }
});

module.exports = router;
