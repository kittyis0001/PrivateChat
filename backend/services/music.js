// Music search/trending/recommend for the story music feature.
//
// Uses the YouTube Data API v3 (set YOUTUBE_API_KEY in the environment
// once you have one — see backend/README.md). Node 18+'s built-in
// fetch is used directly, no extra HTTP client dependency needed.
//
// If YOUTUBE_API_KEY isn't set, every function here returns an empty
// song list rather than throwing — the Android app's music picker
// already shows a clean "no songs found" state for that, so a missing
// key degrades gracefully instead of breaking the story feature.

const YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3";

// Curated mood -> search-query mapping for the "For You" tab. The
// reference web app calls Gemini to analyze the caption/image and
// infer a mood; this backend doesn't have an AI text/image analysis
// key configured, so this is a deliberately simpler, honest
// substitute: match a few keywords in the caption to a mood, or fall
// back to a default, then search YouTube for that mood directly.
const MOOD_KEYWORDS = [
  { mood: "Romantic", vibe: "love songs", match: ["love", "miss you", "heart", "baby", "❤️", "😍"], query: "romantic love songs" },
  { mood: "Happy", vibe: "feel-good", match: ["happy", "fun", "party", "celebrat", "🎉", "😂", "😄"], query: "happy feel good songs" },
  { mood: "Sad", vibe: "emotional", match: ["sad", "miss", "alone", "cry", "😢", "💔"], query: "sad emotional songs" },
  { mood: "Energetic", vibe: "upbeat", match: ["gym", "workout", "run", "energy", "🔥", "💪"], query: "energetic upbeat songs" },
  { mood: "Chill", vibe: "relaxed", match: ["chill", "relax", "calm", "sleep", "🌙", "☕"], query: "chill lofi songs" },
];

function isConfigured() {
  return Boolean(process.env.YOUTUBE_API_KEY);
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

async function searchYouTube(query, maxResults = 20) {
  if (!isConfigured()) return [];
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

async function trendingYouTube(maxResults = 20) {
  if (!isConfigured()) return [];
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

function detectMood(caption) {
  const text = (caption || "").toLowerCase();
  for (const entry of MOOD_KEYWORDS) {
    if (entry.match.some((kw) => text.includes(kw))) return entry;
  }
  return MOOD_KEYWORDS[4]; // Chill — a safe, generic default
}

async function recommendByCaption(caption) {
  const moodEntry = detectMood(caption);
  const songs = await searchYouTube(moodEntry.query, 15);
  return { songs, mood: moodEntry.mood, vibe: moodEntry.vibe };
}

module.exports = { isConfigured, searchYouTube, trendingYouTube, recommendByCaption };
