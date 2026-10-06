const { getFcmToken, removeStaleToken, messaging } = require("./firebase");

const STALE_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/**
 * Sends a data-only, high-priority FCM push to `receiverId`.
 *
 * Data-only (no "notification" block) is deliberate: it means
 * ChatFirebaseMessagingService.onMessageReceived on the Android side
 * ALWAYS runs and fully controls rendering — sender name, avatar,
 * grouping, sound, tap target — consistently whether the app is
 * foregrounded, backgrounded, or fully killed. A "notification" block
 * would instead let the OS auto-display a generic system notification
 * while the app is backgrounded, bypassing the app's own WhatsApp-
 * style notification building.
 *
 * Returns { sent: true } | { sent: false, reason: "no_token" | "stale_token" | "error" }
 * — callers use this to decide the HTTP response, never to leak
 * internals back to the client.
 */
async function sendMessageNotification({ senderId, receiverId, senderName, preview }) {
  const token = await getFcmToken(receiverId);
  if (!token) {
    return { sent: false, reason: "no_token" };
  }

  const payload = {
    token,
    data: {
      senderId,
      senderName,
      preview,
    },
    android: {
      priority: "high",
    },
  };

  try {
    await messaging().send(payload);
    return { sent: true };
  } catch (err) {
    if (STALE_TOKEN_CODES.has(err.code)) {
      await removeStaleToken(receiverId);
      return { sent: false, reason: "stale_token" };
    }
    throw err;
  }
}

module.exports = { sendMessageNotification };
