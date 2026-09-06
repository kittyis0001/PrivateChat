// Music search/trending/recommend for the story music feature.
//
// Two independent sources, combined:
//   - YouTube Data API v3 — set YOUTUBE_API_KEY once you have one.
//   - Jamendo API v3.0 — set JAMENDO_CLIENT_ID. Unlike YouTube's key,
//     Jamendo's is free and issued instantly, no approval wait: sign
//     up at https://devportal.jamendo.com, create an app, and the
//     Client ID is right there. This is the source the reference
//     screenshots' "For You"/"Trending" results actually came from —
//     Jamendo tracks stream directly (real audioUrl, no extra
//     lookup), so it's also the more reliable source for playback.
//
// Node 18+'s built-in fetch is used directly, no extra HTTP client
// dependency needed. Each source degrades independently: if only one
// key is set, you still get that source's results instead of an
// empty list; if neither is set, every function returns an empty
// list rather than throwing.

const YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3";
const JAMENDO_API_BASE = "https://api.jamendo.com/v3.0";
const ytdl = require("@distube/ytdl-core");

// Curated mood -> search-query mapping for the "For You" tab. The
// reference web app calls Gemini to analyze the caption/image and
// infer a mood; this backend doesn't have an AI text/image analysis
// key configured, so this is a deliberately simpler, honest
// substitute: match a few keywords in the caption to a mood, or fall
// back to a default, then search for that mood directly.
const MOOD_KEYWORDS = [
  { mood: "Romantic", vibe: "love songs", match: ["love", "miss you", "heart", "baby", "❤️", "😍"], query: "romantic love songs" },
  { mood: "Happy", vibe: "feel-good", match: ["happy", "fun", "party", "celebrat", "🎉", "😂", "😄"], query: "happy feel good songs" },
  { mood: "Sad", vibe: "emotional", match: ["sad", "miss", "alone", "cry", "😢", "💔"], query: "sad emotional songs" },
  { mood: "Energetic", vibe: "upbeat", match: ["gym", "workout", "run", "energy", "🔥", "💪"], query: "energetic upbeat songs" },
  { mood: "Chill", vibe: "relaxed", match: ["chill", "relax", "calm", "sleep", "🌙", "☕"], query: "chill lofi songs" },
];

function youtubeConfigured() {
  return Boolean(process.env.YOUTUBE_API_KEY);
}

function jamendoConfigured() {
  return Boolean(process.env.JAMENDO_CLIENT_ID);
}

function normalizeYouTubeItem(item) {
  const id = item.id?.videoId || item.id;
  const snippet = item.snippet;
  if (!id || !snippet) return null;
  return {
    videoId: id,
    jamendoId: null,
    title: snippet.title,
    artist: snippet.channelTitle || "",
    thumbnail: snippet.thumbnails?.medium?.url || snippet.thumbnails?.default?.url || "",
    audioUrl: null,
    source: "youtube",
  };
}

function normalizeJamendoItem(track) {
  if (!track?.id || !track?.audio) return null;
  return {
    videoId: null,
    jamendoId: String(track.id),
    title: track.name || "Untitled",
    artist: track.artist_name || "",
    thumbnail: track.image || track.album_image || "",
    audioUrl: track.audio,
    source: "jamendo",
  };
}

async function searchYouTube(query, maxResults = 15) {
  if (!youtubeConfigured()) return [];
  const url = new URL(`${YOUTUBE_API_BASE}/search`);
  url.searchParams.set("part", "snippet");
  url.searchParams.set("type", "video");
  url.searchParams.set("videoCategoryId", "10"); // Music
  url.searchParams.set("maxResults", String(maxResults));
  url.searchParams.set("q", query);
  url.searchParams.set("key", process.env.YOUTUBE_API_KEY);

  const res = await fetch(url.toString());
  if (!res.ok) {
    console.error(`[music] YouTube search failed: ${res.status}`);
    return [];
  }
  const data = await res.json();
  return (data.items || []).map(normalizeYouTubeItem).filter(Boolean);
}

async function trendingYouTube(maxResults = 15) {
  if (!youtubeConfigured()) return [];
  const url = new URL(`${YOUTUBE_API_BASE}/videos`);
  url.searchParams.set("part", "snippet");
  url.searchParams.set("chart", "mostPopular");
  url.searchParams.set("videoCategoryId", "10"); // Music
  url.searchParams.set("maxResults", String(maxResults));
  url.searchParams.set("key", process.env.YOUTUBE_API_KEY);

  const res = await fetch(url.toString());
  if (!res.ok) {
    console.error(`[music] YouTube trending failed: ${res.status}`);
    return [];
  }
  const data = await res.json();
  return (data.items || []).map(normalizeYouTubeItem).filter(Boolean);
}

async function searchJamendo(query, maxResults = 15) {
  if (!jamendoConfigured()) return [];
  const url = new URL(`${JAMENDO_API_BASE}/tracks`);
  url.searchParams.set("client_id", process.env.JAMENDO_CLIENT_ID);
  url.searchParams.set("format", "json");
  url.searchParams.set("limit", String(maxResults));
  url.searchParams.set("search", query);
  url.searchParams.set("audioformat", "mp32");

  const res = await fetch(url.toString());
  if (!res.ok) {
    console.error(`[music] Jamendo search failed: ${res.status}`);
    return [];
  }
  const data = await res.json();
  return (data.results || []).map(normalizeJamendoItem).filter(Boolean);
}

async function trendingJamendo(maxResults = 15) {
  if (!jamendoConfigured()) return [];
  // Jamendo's own "popular this month" ordering — a genuine
  // international trending list, not scoped to any one region.
  const url = new URL(`${JAMENDO_API_BASE}/tracks`);
  url.searchParams.set("client_id", process.env.JAMENDO_CLIENT_ID);
  url.searchParams.set("format", "json");
  url.searchParams.set("limit", String(maxResults));
  url.searchParams.set("order", "popularity_month");
  url.searchParams.set("audioformat", "mp32");

  const res = await fetch(url.toString());
  if (!res.ok) {
    console.error(`[music] Jamendo trending failed: ${res.status}`);
    return [];
  }
  const data = await res.json();
  return (data.results || []).map(normalizeJamendoItem).filter(Boolean);
}

async function search(query) {
  const [youtube, jamendo] = await Promise.all([searchYouTube(query), searchJamendo(query)]);
  return [...jamendo, ...youtube];
}

async function trending() {
  const [youtube, jamendo] = await Promise.all([trendingYouTube(), trendingJamendo()]);
  return [...jamendo, ...youtube];
}

function detectMood(caption) {
  const text = (caption || "").toLowerCase();
  for (const entry of MOOD_KEYWORDS) {
    if (entry.match.some((kw) => text.includes(kw))) return entry;
  }
  return MOOD_KEYWORDS[4]; // Chill — a safe, generic default
}

async function recommendByCaption(caption) {
  const moodEntry = detectMood(caption);
  const songs = await search(moodEntry.query);
  return { songs, mood: moodEntry.mood, vibe: moodEntry.vibe };
}

/**
 * Resolves a YouTube video ID to a direct, streamable audio URL —
 * used instead of embedding the actual YouTube player, because a
 * hidden WebView running the IFrame Player API turned out to be too
 * unreliable for background/silent autoplay in practice (Android
 * WebViews apply extra scrutiny to autoplay-with-sound that a real
 * browser doesn't hit). With a real audio URL, the Android app plays
 * a YouTube song exactly the same simple, reliable way it already
 * plays a Jamendo one — a plain MediaPlayer, no embedded page at all.
 *
 * Important trade-off to know about, not just a "set once" fix:
 *   - This extracts a direct stream URL rather than using YouTube's
 *     official playback embed, which isn't something YouTube's terms
 *     officially sanction — a well-established pattern for personal/
 *     hobby projects (this is what the popular open-source ytdl-core
 *     library and tools like it exist for), but worth knowing.
 *   - YouTube periodically changes its systems specifically to break
 *     this kind of extraction, so this can occasionally stop working
 *     until @distube/ytdl-core (a well-maintained fork with frequent
 *     updates for exactly this reason) is updated — `npm update
 *     @distube/ytdl-core` in backend/ if this endpoint starts failing.
 *   - Some videos (age-restricted, region-locked, etc.) may still fail
 *     to resolve even with an up-to-date library.
 * Jamendo has none of these caveats, since it's a real public API
 * intended for exactly this kind of direct playback.
 */
async function resolveYoutubeAudioUrl(videoId) {
  const info = await ytdl.getInfo(`https://www.youtube.com/watch?v=${videoId}`);
  const audioFormats = ytdl.filterFormats(info.formats, "audioonly");
  if (audioFormats.length === 0) return null;
  const best = audioFormats.sort((a, b) => (b.audioBitrate || 0) - (a.audioBitrate || 0))[0];
  return best.url;
}

module.exports = {
  isConfigured: () => youtubeConfigured() || jamendoConfigured(),
  search,
  trending,
  recommendByCaption,
  resolveYoutubeAudioUrl,
};
