require("dotenv").config();

const express = require("express");
const cors = require("cors");
const helmet = require("helmet");

const { requireApiSecret } = require("./middleware/auth");
const { notifyRateLimiter } = require("./middleware/rateLimit");
const notifyRoute = require("./routes/notify");
const musicRoute = require("./routes/music");

const app = express();

app.use(helmet());
app.use(cors());
app.use(express.json({ limit: "10kb" }));

// Render's health check / a simple "is this deployed" check — no auth
// needed, and deliberately reveals nothing about the app's data.
app.get("/health", (_req, res) => {
  res.status(200).json({ status: "ok" });
});

app.use("/api", requireApiSecret, notifyRateLimiter, notifyRoute);
app.use("/api", requireApiSecret, notifyRateLimiter, musicRoute);

// Catch-all for anything else, kept generic on purpose.
app.use((_req, res) => {
  res.status(404).json({ error: "not_found" });
});

const PORT = process.env.PORT || 10000;
app.listen(PORT, () => {
  console.log(`privatechat-backend listening on port ${PORT}`);
});
