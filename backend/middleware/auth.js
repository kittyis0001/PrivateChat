// Every request must carry the same secret configured in Render's
// API_SECRET env var, via the X-Api-Secret header. This is the whole
// app's auth model (two fixed users, no real accounts/tokens), so it
// intentionally stays this simple rather than adding a session system
// this app has no other use for.

function requireApiSecret(req, res, next) {
  const expected = process.env.API_SECRET;
  if (!expected) {
    // Fail closed: a backend deployed without API_SECRET set must
    // never silently accept every request.
    return res.status(500).json({ error: "server_misconfigured" });
  }

  const provided = req.header("X-Api-Secret");
  if (!provided || provided !== expected) {
    // Temporary diagnostic — doesn't leak either secret value, just
    // confirms whether a header showed up at all vs. matched wrong.
    console.log(`[auth] rejected request: header ${provided ? "present but mismatched" : "missing"}`);
    return res.status(401).json({ error: "unauthorized" });
  }

  next();
}

module.exports = { requireApiSecret };
