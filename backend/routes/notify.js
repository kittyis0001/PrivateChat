const express = require("express");
const { sendMessageNotification } = require("../services/notify");

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

  if (!KNOWN_USERS.has(senderId) || !KNOWN_USERS.has(receiverId)) {
    return res.status(400).json({ error: "invalid_user" });
  }
  if (senderId === receiverId) {
    return res.status(400).json({ error: "sender_equals_receiver" });
  }
  if (!isNonEmptyString(senderName, MAX_NAME_LENGTH)) {
    return res.status(400).json({ error: "invalid_sender_name" });
  }
  if (typeof preview !== "string" || preview.length === 0) {
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
      return res.status(200).json({ success: true });
    }
    // "No token on file" / "stale token just cleared" are both normal,
    // expected outcomes (recipient never logged in yet, or reinstalled
    // the app) — not server errors, so they stay a 200 with a reason
    // rather than a 4xx/5xx that would make the Android WorkManager
    // retry pointlessly.
    return res.status(200).json({ success: false, reason: result.reason });
  } catch (err) {
    console.error("notify failed:", err.message);
    return res.status(502).json({ error: "notification_failed" });
  }
});

module.exports = router;
