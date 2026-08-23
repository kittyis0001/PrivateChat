// Firebase Admin is initialized ONLY from environment variables that
// live in Render's dashboard — the service account private key never
// touches the Android APK, the Git repo, or any file on disk here.
// This is the entire reason a backend exists instead of calling FCM
// straight from the app: the app has no safe place to hold this key.

const { initializeApp, cert } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");

const PROJECT_ID = process.env.PROJECT_ID;
const CLIENT_EMAIL = process.env.CLIENT_EMAIL;
// Render env var UIs typically store multi-line values with literal
// "\n" sequences instead of real newlines — normalize either way.
const PRIVATE_KEY = (process.env.PRIVATE_KEY || "").replace(/\\n/g, "\n");

// Matches the exact Realtime Database instance the Android app talks
// to (see ChatRepository.kt / LoginActivity.kt / google-services.json
// — project "private-chat-7a103", region asia-southeast1). Keeping
// this hardcoded (rather than another env var) means a misconfigured
// Render env can't accidentally point this backend at the wrong
// database.
const DATABASE_URL = "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app";

let initialized = false;

function ensureInitialized() {
  if (initialized) return;
  if (!PROJECT_ID || !CLIENT_EMAIL || !PRIVATE_KEY) {
    throw new Error(
      "Missing Firebase service account env vars — set PROJECT_ID, CLIENT_EMAIL and PRIVATE_KEY in Render's dashboard."
    );
  }
  initializeApp({
    credential: cert({
      projectId: PROJECT_ID,
      clientEmail: CLIENT_EMAIL,
      privateKey: PRIVATE_KEY,
    }),
    databaseURL: DATABASE_URL,
  });
  initialized = true;
}

function db() {
  ensureInitialized();
  return getDatabase();
}

async function getFcmToken(username) {
  const snap = await db().ref(`fcmTokens/${username}`).once("value");
  return snap.val();
}

async function removeStaleToken(username) {
  await db().ref(`fcmTokens/${username}`).remove();
}

function messaging() {
  ensureInitialized();
  return getMessaging();
}

module.exports = { getFcmToken, removeStaleToken, messaging };
