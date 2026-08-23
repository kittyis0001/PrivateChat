package com.privatechat.app.data

/**
 * Display-name resolution for the app's two fixed users. `defaultFor`
 * is the original hardcoded Kat/Kitty mapping (kept as the fallback);
 * `resolve` layers a live custom nickname (from ChatRepository's
 * nicknames/ node) on top of it, so callers never need to duplicate
 * the "katis1 -> Kat" branch themselves.
 */
object Nicknames {
    fun defaultFor(username: String): String = if (username == "katis1") "Kat" else "Kitty"

    fun resolve(username: String, custom: Map<String, String>): String =
        custom[username]?.takeIf { it.isNotBlank() } ?: defaultFor(username)
}
