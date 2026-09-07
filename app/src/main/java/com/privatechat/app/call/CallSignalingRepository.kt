package com.privatechat.app.call

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

data class CallSession(
    val caller: String = "",
    val callee: String = "",
    val status: String = "", // "ringing" | "accepted" | "declined" | "busy" | "ended"
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val startedAt: Long = 0L
)

data class IceCandidateData(
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = ""
)

/**
 * Signaling only — never touches audio/video itself, just the SDP
 * offer/answer + ICE candidate exchange two WebRtcClient instances
 * need to find and connect to each other. One shared calls/session
 * node (not one-per-call-id) since this app has exactly two users and
 * at most one live call at a time.
 *
 * The single-node design makes "prevent two calls at the same time"
 * enforceable atomically: [startCall] runs a transaction that only
 * commits if no fresh session already exists, so two devices racing to
 * dial each other can never both win, and a second dial while a call
 * is live fails cleanly with a "busy" result instead of clobbering the
 * active call's session/candidates.
 */
class CallSignalingRepository(private val currentUser: String) {

    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(DB_URL)
    private val sessionRef: DatabaseReference = db.getReference("calls/session")
    private val candidatesRef: DatabaseReference = db.getReference("calls/candidates")

    private var sessionListener: ValueEventListener? = null
    private var remoteCandidatesListener: ChildEventListener? = null

    var onSessionChanged: ((CallSession?) -> Unit)? = null
    var onRemoteCandidate: ((IceCandidateData) -> Unit)? = null

    /**
     * Atomically claims the shared session slot for a new outgoing
     * call. Commits only when no other fresh call session exists (a
     * stale session older than [STALE_SESSION_MS] — e.g. left behind by
     * an app killed mid-call — is treated as dead and overwritten).
     *
     * @return true when this call won the slot, false when another
     *         call is already active (busy).
     */
    fun startCall(callee: String, onCommitted: (Boolean) -> Unit) {
        sessionRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                if (current.value != null) {
                    val startedAt = current.child("startedAt").getValue(Long::class.java) ?: 0L
                    val age = System.currentTimeMillis() - startedAt
                    if (age < STALE_SESSION_MS) {
                        // A fresh session exists -> another call is live.
                        return Transaction.abort()
                    }
                }
                current.value = mapOf(
                    "caller" to currentUser,
                    "callee" to callee,
                    "status" to "ringing",
                    "startedAt" to System.currentTimeMillis()
                )
                return Transaction.success(current)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (committed) {
                    // Clear any stale candidates left over from a
                    // previous (crashed) call before this one starts
                    // sending its own.
                    candidatesRef.removeValue()
                }
                onCommitted(committed)
            }
        })
    }

    fun setOffer(sdp: String) {
        sessionRef.child("offerSdp").setValue(sdp)
    }

    fun setAnswer(sdp: String) {
        sessionRef.child("answerSdp").setValue(sdp)
    }

    fun setStatus(status: String) {
        sessionRef.child("status").setValue(status)
    }

    fun endCall() {
        sessionRef.removeValue()
        candidatesRef.removeValue()
    }

    fun sendIceCandidate(fromUser: String, candidate: IceCandidateData) {
        candidatesRef.child(fromUser).push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.candidate
            )
        )
    }

    fun attachSessionListener() {
        if (sessionListener != null) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onSessionChanged?.invoke(null)
                    return
                }
                onSessionChanged?.invoke(
                    CallSession(
                        caller = snapshot.child("caller").getValue(String::class.java).orEmpty(),
                        callee = snapshot.child("callee").getValue(String::class.java).orEmpty(),
                        status = snapshot.child("status").getValue(String::class.java).orEmpty(),
                        offerSdp = snapshot.child("offerSdp").getValue(String::class.java),
                        answerSdp = snapshot.child("answerSdp").getValue(String::class.java),
                        startedAt = snapshot.child("startedAt").getValue(Long::class.java) ?: 0L
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        sessionListener = listener
        sessionRef.addValueEventListener(listener)
    }

    /** Listens for ICE candidates from [remoteUser] only — each side only ever needs the other's. */
    fun attachRemoteCandidateListener(remoteUser: String) {
        if (remoteCandidatesListener != null) return
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                val candidate = snapshot.child("candidate").getValue(String::class.java) ?: return
                onRemoteCandidate?.invoke(IceCandidateData(sdpMid, sdpMLineIndex, candidate))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        remoteCandidatesListener = listener
        candidatesRef.child(remoteUser).addChildEventListener(listener)
    }

    fun detachAll(remoteUser: String?) {
        sessionListener?.let { sessionRef.removeEventListener(it) }
        sessionListener = null
        if (remoteUser != null) {
            remoteCandidatesListener?.let { candidatesRef.child(remoteUser).removeEventListener(it) }
        }
        remoteCandidatesListener = null
    }

    companion object {
        const val DB_URL = "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
        private const val STALE_SESSION_MS = 90_000L
    }
}
