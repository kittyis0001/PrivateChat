const rateLimit = require("express-rate-limit");

// This app has exactly two users and a human sending pace, so 60
// requests/minute per IP is generous headroom, not a real ceiling —
// it's here purely to blunt abuse (a leaked API secret, a buggy retry
// loop) rather than to constrain normal use.
const notifyRateLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "rate_limited" },
});

module.exports = { notifyRateLimiter };
