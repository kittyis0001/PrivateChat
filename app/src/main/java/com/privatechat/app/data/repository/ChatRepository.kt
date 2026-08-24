package com.privatechat.app.data.repository

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.database.*
import com.privatechat.app.data.model.Message
import com.privatechat.app.data.model.PresenceStatus

/**
 * Owns all Firebase Realtime Database access for the chat between the
 * two fixed users. This is the piece that fixes the web app's core
 * bug: the Android Firebase SDK (unlike the JS web SDK in a
 * backgrounded Chrome tab) keeps its own persistent connection and
 * automatically resumes + replays missed data on reconnect — as long
 * as we correctly re-declare interest via keepSynced/listeners at the
 * right lifecycle points, which this class centralizes.
 *
 * Implements DefaultLifecycleObserver so ChatActivity just does:
 *   lifecycle.addObserver(chatRepository)
 * and reconnect-on-resume is handled here, not scattered through the
 * Activity.
 */
class ChatRepository(
    private val currentUser: String,
    private val otherUser: String
) : DefaultLifecycleObserver {

    private val db: FirebaseDatabase = FirebaseDatabase.getInstance("https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val messagesRef: DatabaseReference = db.getReference("messages")
    private val statusRef: DatabaseReference = db.getReference("status")
    private val typingRef: DatabaseReference = db.getReference("typing")
    private val connectedRef: DatabaseReference = db.getReference(".info/connected")
    private val blocksRef: DatabaseReference = db.getReference("blocks")
    private val nicknamesRef: DatabaseReference = db.getReference("nicknames")
    private val vanishModeRef: DatabaseReference = db.getReference("vanishMode")
    private val photosRef: DatabaseReference = db.getReference("photos")

    private var messagesListener: ChildEventListener? = null
    private var isListenerAttached = false
    private var isBlockListenerAttached = false
    private var isBlockedByOtherListenerAttached = false
    private var isNicknamesListenerAttached = false
    private var isVanishModeListenerAttached = false
    private var isPhotosListenerAttached = false

    var onMessageAdded: ((Message) -> Unit)? = null
    var onMessageChanged: ((Message) -> Unit)? = null
    var onMessageRemoved: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onOtherUserPresence: ((PresenceStatus) -> Unit)? = null
    var onOtherUserTyping: ((Boolean) -> Unit)? = null
    // (blocked, blockedAtMillis) — reflects whether *this* user has
    // blocked the other one, read from blocks/{currentUser}.
    var onBlockStateChanged: ((Boolean, Long) -> Unit)? = null
    // Whether the OTHER user has blocked THIS one — the piece that was
    // missing entirely: onBlockStateChanged only ever reflected
    // blocks/{currentUser} (have I blocked them), never
    // blocks/{otherUser} (have they blocked me), so a blocked user's
    // own device had no way to know and could still send freely.
    var onBlockedByOtherChanged: ((Boolean) -> Unit)? = null
    // Full nicknames/ node as a username -> nickname map, whichever
    // keys are actually set — fires instantly for BOTH users the
    // moment either one writes a nickname, since it's one shared node
    // rather than two independent per-user ones like blocks/.
    var onNicknamesChanged: ((Map<String, String>) -> Unit)? = null
    // Current vanish-mode duration in hours, or null if off — one
    // shared node (like nicknames/, not per-user like blocks/), so
    // whichever user sets it applies to both instantly.
    var onVanishModeChanged: ((Int?) -> Unit)? = null
    // Full photos/ node as a username -> Cloudinary URL map — same
    // shared-node pattern as nicknames/, so a Change DP on either
    // device reaches both instantly with no refresh/re-login needed.
    var onPhotosChanged: ((Map<String, String>) -> Unit)? = null

    init {
        // Keep the messages node synced to local disk cache even while
        // no listener is active — this is what actually gives us "no
        // message ever lost" for temporary offline periods, using the
        // Android SDK's built-in disk persistence rather than any
        // custom polling logic.
        messagesRef.keepSynced(true)
    }

    // ── Lifecycle hooks ──────────────────────────────────────────
    // onStart/onStop (not onCreate/onDestroy) so the repository is
    // torn down and recreated exactly with the visible lifecycle of
    // the screen — never tied to a single Activity instance surviving
    // forever, per the architecture requirement.

    override fun onStart(owner: LifecycleOwner) {
        if (!isListenerAttached) {
            messagesListener = null
            attachMessagesListener()
        }
        markOnline()
        attachMessagesListener()
        attachConnectionMonitor()
        attachPresenceListener()
        attachTypingListener()
        attachBlockListener()
        attachBlockedByOtherListener()
        attachNicknamesListener()
        attachVanishModeListener()
        attachPhotosListener()
        markOnline()
    }

    override fun onStop(owner: LifecycleOwner) {
        markOffline()
        // Listeners are intentionally left attached while merely
        // stopped (not destroyed) — the Android SDK will queue/replay
        // events for them once reconnected, which is the reliable
        // native behavior the web version couldn't get from a
        // backgrounded browser tab. detachAll() is only called from
        // onDestroy via the Activity, for a real teardown.
    }

    fun detachAll() {
        messagesListener?.let { messagesRef.removeEventListener(it) }
        messagesListener = null
        isListenerAttached = false
    }

    // ── Connection monitoring ────────────────────────────────────

    private fun attachConnectionMonitor() {
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "connected = $connected")
                onConnectionStateChanged?.invoke(connected)
                if (connected) {
                    // Re-assert presence the instant the connection is
                    // confirmed live — covers the case where onStart()
                    // fired before the socket was actually back up.
                    markOnline()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "connection monitor cancelled: ${error.message}")
            }
        })
    }

    // ── Messages ──────────────────────────────────────────────────

    private fun attachMessagesListener() {
        if (isListenerAttached) return // never double-attach across onStart calls
        isListenerAttached = true

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(Message::class.java) ?: return
                message.key = snapshot.key ?: return
                onMessageAdded?.invoke(message)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(Message::class.java) ?: return
                message.key = snapshot.key ?: return
                onMessageChanged?.invoke(message)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                onMessageRemoved?.invoke(key)
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "messages listener cancelled: ${error.message}")
            }
        }
        messagesListener = listener
        // Firebase's ChildEventListener automatically fires onChildAdded
        // for the FULL existing dataset on first attach, and resumes
        // firing for anything added while disconnected, once the SDK's
        // own reconnect completes — no manual catch-up fetch needed on
        // native, unlike the web workaround.
        messagesRef.addChildEventListener(listener)
    }

    fun sendMessage(
        text: String,
        replyTo: String? = null,
        replyText: String? = null,
        replySender: String? = null,
        onSent: (() -> Unit)? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "name" to currentUser,
            "text" to text,
            "time" to System.currentTimeMillis(),
            "seen" to false
        )
        // Only attach reply fields when actually replying, so ordinary
        // messages keep writing the exact same node shape as before.
        if (!replyTo.isNullOrEmpty()) {
            data["replyTo"] = replyTo
            data["replyText"] = replyText.orEmpty()
            data["replySender"] = replySender.orEmpty()
        }
        messagesRef.push().setValue(data)
            .addOnSuccessListener { onSent?.invoke() }
            .addOnFailureListener { e -> Log.e(TAG, "send failed: ${e.message}") }
    }

    fun markSeen(message: Message) {
        if (message.name != currentUser && !message.seen && !message.deleted && message.type == null) {
            messagesRef.child(message.key).child("seen").setValue(true)
        }
    }

    fun editMessage(key: String, newText: String) {
        messagesRef.child(key).updateChildren(mapOf("text" to newText, "edited" to true))
    }

    fun deleteMessage(key: String) {
        messagesRef.child(key).updateChildren(mapOf("text" to "__deleted__", "deleted" to true))
    }

    // Reactions live at messages/{key}/reactions/{uid} so any number of
    // users can react independently without clobbering each other.
    // Passing emoji = null removes the caller's reaction (tap-to-toggle).
    fun setReaction(key: String, emoji: String?) {
        val reactionRef = messagesRef.child(key).child("reactions").child(currentUser)
        if (emoji == null) reactionRef.removeValue() else reactionRef.setValue(emoji)
    }

    fun deleteAllChat() {
        messagesRef.removeValue()
    }

    fun unreadCount(messages: List<Message>): Int =
        messages.count { it.name == otherUser && !it.seen && !it.deleted && it.type == null }

    // ── Block / Unblock ──────────────────────────────────────────
    // Only this user's own block state (blocks/{currentUser}) is ever
    // written or read here — the two users' block states are
    // independent nodes, so this never touches the other person's data.

    fun setBlocked(blocked: Boolean) {
        blocksRef.child(currentUser).setValue(
            mapOf("blocked" to blocked, "timestamp" to System.currentTimeMillis())
        )
    }

    private fun attachBlockListener() {
        if (isBlockListenerAttached) return
        isBlockListenerAttached = true
        blocksRef.child(currentUser).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val blocked = snapshot.child("blocked").getValue(Boolean::class.java) ?: false
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                onBlockStateChanged?.invoke(blocked, timestamp)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun attachBlockedByOtherListener() {
        if (isBlockedByOtherListenerAttached) return
        isBlockedByOtherListenerAttached = true
        blocksRef.child(otherUser).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val blocked = snapshot.child("blocked").getValue(Boolean::class.java) ?: false
                onBlockedByOtherChanged?.invoke(blocked)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Nicknames ─────────────────────────────────────────────────
    // One shared node (not split per-user like blocks/) so either
    // participant can rename either person, and both devices update
    // instantly off the same listener — matching "নিজের এবং অপরজনের
    // যেন nickname চেঞ্জ করা যায়, instant update হয় দুজনের বেলায়".

    fun setNickname(username: String, nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) {
            // Empty input clears the custom nickname, reverting to the
            // built-in Kat/Kitty default rather than storing blank text.
            nicknamesRef.child(username).removeValue()
        } else {
            nicknamesRef.child(username).setValue(trimmed)
        }
    }

    private fun attachNicknamesListener() {
        if (isNicknamesListenerAttached) return
        isNicknamesListenerAttached = true
        nicknamesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nicknames = mutableMapOf<String, String>()
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val value = child.getValue(String::class.java)
                    if (!value.isNullOrBlank()) nicknames[key] = value
                }
                onNicknamesChanged?.invoke(nicknames)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Profile photos ────────────────────────────────────────────
    // Same shared-node pattern as nicknames/ — each user only ever
    // writes their own photos/{username} entry, but everyone reads the
    // whole node, so a Change DP on either device reaches both
    // instantly with no refresh/re-login needed.

    fun setPhotoUrl(username: String, url: String) {
        photosRef.child(username).setValue(url)
    }

    private fun attachPhotosListener() {
        if (isPhotosListenerAttached) return
        isPhotosListenerAttached = true
        photosRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val photos = mutableMapOf<String, String>()
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val value = child.getValue(String::class.java)
                    if (!value.isNullOrBlank()) photos[key] = value
                }
                onPhotosChanged?.invoke(photos)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Vanish Mode ───────────────────────────────────────────────
    // One shared node (like nicknames/) so whichever user picks a
    // duration applies to both — "যে কোনো একজন user সিলেক্ট করবে সেটা
    // ২ জনের জন্য কাজ করবে". Actual expiry/deletion of aged-out
    // messages happens in ChatActivity (it needs the live message
    // list + foreground lifecycle to enforce this without a server-
    // side job); this only tracks and announces the on/off state.

    fun setVanishMode(durationHours: Int?, actorDisplayName: String) {
        if (durationHours == null) {
            vanishModeRef.removeValue()
        } else {
            vanishModeRef.setValue(
                mapOf(
                    "durationHours" to durationHours,
                    "setBy" to currentUser,
                    "setAt" to System.currentTimeMillis()
                )
            )
        }
        // WhatsApp-style small system notice in the chat itself,
        // announcing who changed it and to what — reuses the existing
        // messages/ pipeline (type: "system") so it syncs to both
        // devices instantly via the same listener as everything else,
        // and MessageAdapter renders it as a centered light-gray line
        // rather than a bubble.
        val text = if (durationHours != null) {
            "$actorDisplayName turned on vanish mode ($durationHours hours)"
        } else {
            "$actorDisplayName turned off vanish mode"
        }
        val systemData = mapOf(
            "name" to currentUser,
            "text" to text,
            "time" to System.currentTimeMillis(),
            "seen" to true,
            "type" to "system"
        )
        messagesRef.push().setValue(systemData)
    }

    // Used by ChatActivity's expiry sweep once a message's age passes
    // the active vanish-mode duration. Reuses the same removeValue()
    // deleteAllChat() already uses — onMessageRemoved fires on both
    // devices off the existing listener, no extra plumbing needed for
    // the deletion to actually propagate.
    fun deleteExpiredMessage(key: String) {
        messagesRef.child(key).removeValue()
    }

    private fun attachVanishModeListener() {
        if (isVanishModeListenerAttached) return
        isVanishModeListenerAttached = true
        vanishModeRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hours = snapshot.child("durationHours").getValue(Int::class.java)
                onVanishModeChanged?.invoke(hours)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Presence ──────────────────────────────────────────────────

    private fun markOnline() {
        val myStatusRef = statusRef.child(currentUser)
        myStatusRef.setValue(mapOf("online" to true, "last" to System.currentTimeMillis()))
        // onDisconnect is honored by the Firebase server itself the
        // instant the socket drops — this is the reliable native
        // equivalent of the web app's onDisconnect() call, but backed
        // by a real persistent SDK connection instead of a browser tab.
        myStatusRef.onDisconnect().setValue(mapOf("online" to false, "last" to System.currentTimeMillis()))
    }

    private fun markOffline() {
        statusRef.child(currentUser).setValue(mapOf("online" to false, "last" to System.currentTimeMillis()))
    }

    private fun attachPresenceListener() {
        statusRef.child(otherUser).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(PresenceStatus::class.java) ?: PresenceStatus()
                onOtherUserPresence?.invoke(status)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Typing ────────────────────────────────────────────────────

    fun setTyping(isTyping: Boolean) {
        typingRef.child(currentUser).setValue(isTyping)
    }

    private fun attachTypingListener() {
        typingRef.child(otherUser).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onOtherUserTyping?.invoke(snapshot.getValue(Boolean::class.java) ?: false)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}
