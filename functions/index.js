/**
 * This is the fix for "notification permission granted but no
 * notification ever arrives": the Android app only ever RECEIVES and
 * displays FCM pushes (ChatFirebaseMessagingService); nothing in the
 * project was ever SENDING one. Sending a real push requires a
 * server-side credential (a Firebase service account), which must
 * never be embedded in the Android app itself — that's exactly what
 * this Cloud Function is for: it runs on Google's servers, watches
 * messages/{messageId} for new messages, and pushes to whichever of
 * the two fixed users didn't send it.
 *
 * Deploy with:
 *   cd functions && npm install
 *   firebase deploy --only functions
 *
 * (Requires the Firebase CLI logged in to this project — see the repo
 * root README/PR description for the one-time setup.)
 */

const { onValueCreated } = require("firebase-functions/v2/database");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getDatabaseWithUrl } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

// Matches the Realtime Database instance's own region
// (asia-southeast1, from the client's FirebaseDatabase.getInstance
// URL) so the function runs next to the data instead of incurring
// cross-region latency on every message.
setGlobalOptions({ region: "asia-southeast1" });

const DB_INSTANCE = "private-chat-7a103-default-rtdb";
const DB_URL = `https://${DB_INSTANCE}.asia-southeast1.firebasedatabase.app`;

// Exactly two fixed accounts in this app (see Session.kt on the
// Android side) — the recipient of any message is simply "whichever
// one didn't send it".
function otherUser(sender) {
  return sender === "katis1" ? "kittyis0001" : "katis1";
}

function displayName(username) {
  return username === "katis1" ? "Kat" : "Kitty";
}

// Mirrors MessageAdapter.previewText()'s special-casing on the
// Android side, so the notification body matches what the chat
// screen itself would show for the same message.
function previewText(message) {
  if (typeof message.text !== "string") return "New message";
  if (message.text.startsWith("__voice__")) return "\uD83C\uDFA4 Voice message";
  if (message.text.startsWith("__gif__")) return "\uD83C\uDFDE\uFE0F GIF";
  return message.text;
}

exports.sendMessageNotification = onValueCreated(
  { ref: "/messages/{messageId}", instance: DB_INSTANCE },
  async (event) => {
    const message = event.data.val();
    if (!message || !message.name) return;

    // System/presence-style entries (if any are ever written under
    // this path) never get a push — only genuine chat messages from
    // one of the two users do.
    if (message.type === "system") return;

    const recipient = otherUser(message.name);
    const db = getDatabaseWithUrl(DB_URL);

    const tokenSnap = await db.ref(`fcmTokens/${recipient}`).once("value");
    const token = tokenSnap.val();
    if (!token) {
      logger.info(`No FCM token on file for ${recipient} — skipping push.`);
      return;
    }

    // Data-only payload (no "notification" block) so the client's
    // onMessageReceived always runs and fully controls rendering,
    // consistently across foreground/background/killed states —
    // matching how WhatsApp/Messenger build their own notifications
    // client-side rather than relying on the OS's default handling.
    const payload = {
      token,
      data: {
        senderName: displayName(message.name),
        preview: previewText(message).slice(0, 200),
      },
      android: {
        priority: "high",
      },
    };

    try {
      await getMessaging().send(payload);
    } catch (err) {
      const staleTokenCodes = [
        "messaging/registration-token-not-registered",
        "messaging/invalid-registration-token",
      ];
      if (staleTokenCodes.includes(err.code)) {
        // Token is stale (app reinstalled / cleared data) — remove it
        // so we stop retrying against a dead token every message.
        await db.ref(`fcmTokens/${recipient}`).remove();
        logger.info(`Removed stale FCM token for ${recipient}.`);
      } else {
        logger.error("FCM send failed", err);
      }
    }
  }
);
