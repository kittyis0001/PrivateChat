const express = require("express");
const { sendMessageNotification, sendCallNotification } = require("../services/notify");

const router = express.Router();

// Matches Session.kt's fixed two-user pair on the Android side exactly
// — this app was never designed for more than two accounts, and
// accepting anything else here would be accepting requests the real
// app could never legitimately send.
const KNOWN_USERS = new Set(["katis1", "kittyis0001"]);

const MAX_PREVIEW_LENGTH = 200;
const MAX_NAME_LENGTH = 60;

function isNonEmptyString(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

router.post("/notify", async (req, res) => {
  const { senderId, receiverId, senderName, preview } = req.body || {};

  // Temporary diagnostic logging — confirms the request actually
  // reached this route at all, since nothing else in this file logs
  // on the success path. Safe to remove once notifications are
  // confirmed working end-to-end.
  console.log(`[notify] incoming request: senderId=${senderId} receiverId=${receiverId}`);

  if (!KNOWN_USERS.has(senderId) || !KNOWN_USERS.has(receiverId)) {
    console.log(`[notify] rejected: invalid_user (senderId=${senderId}, receiverId=${receiverId})`);
    return res.status(400).json({ error: "invalid_user" });
  }
  if (senderId === receiverId) {
    console.log("[notify] rejected: sender_equals_receiver");
    return res.status(400).json({ error: "sender_equals_receiver" });
  }
  if (!isNonEmptyString(senderName, MAX_NAME_LENGTH)) {
    console.log("[notify] rejected: invalid_sender_name");
    return res.status(400).json({ error: "invalid_sender_name" });
  }
  if (typeof preview !== "string" || preview.length === 0) {
    console.log("[notify] rejected: invalid_preview");
    return res.status(400).json({ error: "invalid_preview" });
  }

  const truncatedPreview = preview.slice(0, MAX_PREVIEW_LENGTH);

  try {
    const result = await sendMessageNotification({
      senderId,
      receiverId,
      senderName: senderName.trim(),
      preview: truncatedPreview,
    });

    if (result.sent) {
      console.log(`[notify] sent OK to ${receiverId}`);
      return res.status(200).json({ success: true });
    }
    // "No token on file" / "stale token just cleared" are both normal,
    // expected outcomes (recipient never logged in yet, or reinstalled
    // the app) — not server errors, so they stay a 200 with a reason
    // rather than a 4xx/5xx that would make the Android WorkManager
    // retry pointlessly.
    console.log(`[notify] not sent, reason=${result.reason} (receiverId=${receiverId})`);
    return res.status(200).json({ success: false, reason: result.reason });
  } catch (err) {
    console.error("[notify] failed:", err.message);
    return res.status(502).json({ error: "notification_failed" });
  }
});

router.post("/notify-call", async (req, res) => {
  const { callerId, calleeId, callerName } = req.body || {};

  console.log(`[notify-call] incoming request: callerId=${callerId} calleeId=${calleeId}`);

  if (!KNOWN_USERS.has(callerId) || !KNOWN_USERS.has(calleeId)) {
    console.log(`[notify-call] rejected: invalid_user (callerId=${callerId}, calleeId=${calleeId})`);
    return res.status(400).json({ error: "invalid_user" });
  }
  if (callerId === calleeId) {
    console.log("[notify-call] rejected: caller_equals_callee");
    return res.status(400).json({ error: "caller_equals_callee" });
  }
  if (!isNonEmptyString(callerName, MAX_NAME_LENGTH)) {
    console.log("[notify-call] rejected: invalid_caller_name");
    return res.status(400).json({ error: "invalid_caller_name" });
  }

  try {
    const result = await sendCallNotification({
      callerId,
      calleeId,
      callerName: callerName.trim(),
    });

    if (result.sent) {
      console.log(`[notify-call] sent OK to ${calleeId}`);
      return res.status(200).json({ success: true });
    }
    console.log(`[notify-call] not sent, reason=${result.reason} (calleeId=${calleeId})`);
    return res.status(200).json({ success: false, reason: result.reason });
  } catch (err) {
    console.error("[notify-call] failed:", err.message);
    return res.status(502).json({ error: "notification_failed" });
  }
});

module.exports = router;
