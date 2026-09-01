package com.privatechat.app.call

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class CallSession(
    val caller: String = "",
    val callee: String = "",
    val status: String = "", // "ringing" | "accepted" | "declined" | "ended"
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
 * Signaling only — this never touches audio/video itself, just the
 * SDP offer/answer and ICE candidate exchange needed for two
 * WebRtcClient instances (one per device) to find and connect to each
 * other. One shared calls/session node (not one-per-call-id) since
 * this app only ever has two fixed users and one call can be active
 * at a time, mirroring how vanishMode/nicknames already use a single
 * shared node rather than per-user ones.
 *
 * Deliberately does NOT reuse ChatRepository — a call can be signaled
 * (and rung) whenever this device's process is alive, independent of
 * whether ChatActivity itself is currently open, so this gets its own
 * short-lived attach/detach pair instead of piggybacking on
 * ChatRepository's Activity-lifecycle-bound one.
 */
class CallSignalingRepository(private val currentUser: String) {

    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(
        "https://private-chat-7a103-default-rtdb.asia-southeast1.firebasedatabase.app"
    )
    private val sessionRef: DatabaseReference = db.getReference("calls/session")
    private val candidatesRef: DatabaseReference = db.getReference("calls/candidates")

    private var sessionListener: ValueEventListener? = null
    private var remoteCandidatesListener: ChildEventListener? = null

    var onSessionChanged: ((CallSession?) -> Unit)? = null
    var onRemoteCandidate: ((IceCandidateData) -> Unit)? = null

    fun startCall(callee: String) {
        // Defensive cleanup: if a previous call ended abnormally
        // (app killed mid-call, etc.) without endCall() running, stale
        // candidates could still be sitting here — attachRemoteCandidateListener
        // fires onChildAdded for every existing child immediately on
        // attach, so leftover ones would otherwise get fed into a
        // brand new call's WebRtcClient as if they were current.
        candidatesRef.removeValue()
        sessionRef.setValue(
            mapOf(
                "caller" to currentUser,
                "callee" to callee,
                "status" to "ringing",
                "startedAt" to System.currentTimeMillis()
            )
        )
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

    /** Ends the call and clears the whole session — ready for a fresh call afterward. */
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
}
