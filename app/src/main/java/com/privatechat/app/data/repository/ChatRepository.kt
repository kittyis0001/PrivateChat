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

    private var messagesListener: ChildEventListener? = null
    private var isListenerAttached = false

    var onMessageAdded: ((Message) -> Unit)? = null
    var onMessageChanged: ((Message) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onOtherUserPresence: ((PresenceStatus) -> Unit)? = null
    var onOtherUserTyping: ((Boolean) -> Unit)? = null

    init {
        // Keep the messages node synced to local disk cache even while
        // no listener is active — this is what actually gives us "no
        // message ever lost" for temporary offline periods, using the
        // Android SDK's built-in disk persistence rather than any
        // custom polling logic.
        db.setPersistenceEnabled(true)
        messagesRef.keepSynced(true)
    }

    // ── Lifecycle hooks ──────────────────────────────────────────
    // onStart/onStop (not onCreate/onDestroy) so the repository is
    // torn down and recreated exactly with the visible lifecycle of
    // the screen — never tied to a single Activity instance surviving
    // forever, per the architecture requirement.

    override fun onStart(owner: LifecycleOwner) {
        attachMessagesListener()
        attachConnectionMonitor()
        attachPresenceListener()
        attachTypingListener()
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

            override fun onChildRemoved(snapshot: DataSnapshot) {}
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

    fun sendMessage(text: String) {
        val data = mapOf(
            "name" to currentUser,
            "text" to text,
            "time" to System.currentTimeMillis(),
            "seen" to false
        )
        messagesRef.push().setValue(data)
            .addOnFailureListener { e -> Log.e(TAG, "send failed: ${e.message}") }
    }

    fun markSeen(message: Message) {
        if (message.name != currentUser && !message.seen && !message.deleted && message.type == null) {
            messagesRef.child(message.key).child("seen").setValue(true)
        }
    }

    fun deleteMessage(key: String) {
        messagesRef.child(key).updateChildren(mapOf("text" to "__deleted__", "deleted" to true))
    }

    fun deleteAllChat() {
        messagesRef.removeValue()
    }

    fun unreadCount(messages: List<Message>): Int =
        messages.count { it.name == otherUser && !it.seen && !it.deleted && it.type == null }

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
