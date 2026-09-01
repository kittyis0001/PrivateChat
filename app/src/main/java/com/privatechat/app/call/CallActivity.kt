package com.privatechat.app.call

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.privatechat.app.data.Nicknames
import com.privatechat.app.data.Session
import com.privatechat.app.databinding.ActivityCallBinding
import com.privatechat.app.utils.NotificationAvatarFactory
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/**
 * "Professional, premium, bug-free, instant voice call feature" — the
 * one honest caveat that comes with that: this signals entirely
 * through the existing Firebase Realtime Database and connects
 * peer-to-peer over WebRTC with Google's free public STUN servers.
 * That combination genuinely works well on favorable networks (same
 * wifi, most home broadband) but isn't guaranteed to punch through
 * arbitrary mobile-carrier NAT without a TURN relay — see
 * WebRtcClient's own comment for where to add one once you have it.
 *
 * Incoming calls DO ring even while the app is backgrounded or fully
 * killed: starting a call also sends a data-only FCM push (see
 * NotificationRepository.notifyIncomingCall / ChatFirebaseMessagingService
 * .handleIncomingCallPush), which shows a full-screen incoming-call
 * notification with Accept/Decline actions and launches this exact
 * screen on tap — the actual signaling/WebRTC connection below is
 * unchanged either way, this only affects how the screen gets opened.
 */
class CallActivity : AppCompatActivity(), WebRtcClient.Listener {

    private lateinit var binding: ActivityCallBinding
    private lateinit var signaling: CallSignalingRepository
    private var webRtcClient: WebRtcClient? = null

    private lateinit var currentUser: String
    private lateinit var remoteUser: String
    private var isOutgoing = false
    private var remotePhotoUrl: String? = null
    private var autoAccept = false

    private var state = CallState.CONNECTING
    private var isMuted = false
    private var isSpeakerOn = false
    // Set the instant the user taps Accept, independent of whether the
    // caller's offer SDP has actually arrived via Firebase yet — see
    // tryAcceptIfReady()'s own comment for why this two-part check
    // exists instead of accepting directly inline.
    private var userWantsToAccept = false
    private var callStartElapsedMs = 0L
    private var durationHandler: Handler? = null
    private var durationRunnable: Runnable? = null
    private var ringtone: android.media.Ringtone? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) proceedAfterPermission() else finishCall()
        }

    private enum class CallState { OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = Session.currentUser()
        val other = intent.getStringExtra(EXTRA_REMOTE_USER)
        if (user == null || other == null) {
            finish()
            return
        }
        currentUser = user
        remoteUser = other
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)
        remotePhotoUrl = intent.getStringExtra(EXTRA_REMOTE_PHOTO_URL)
        autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)

        signaling = CallSignalingRepository(currentUser)

        val displayName = Nicknames.defaultFor(remoteUser)
        binding.callName.text = displayName
        loadAvatar(displayName, remotePhotoUrl)

        binding.callEndButton.setOnClickListener { userEndedCall() }
        binding.callDeclineButton.setOnClickListener { userDeclinedCall() }
        binding.callAcceptButton.setOnClickListener { userAcceptedCall() }
        binding.callMuteButton.setOnClickListener { toggleMute() }
        binding.callSpeakerButton.setOnClickListener { toggleSpeaker() }

        signaling.onSessionChanged = { session -> runOnUiThread { handleSessionChange(session) } }
        signaling.onRemoteCandidate = { candidate ->
            runOnUiThread {
                val client = webRtcClient
                if (client != null) {
                    client.addRemoteIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
                } else {
                    pendingRemoteCandidates.add(candidate)
                }
            }
        }
        signaling.attachSessionListener()
        signaling.attachRemoteCandidateListener(remoteUser)

        renderState(if (isOutgoing) CallState.OUTGOING_RINGING else CallState.INCOMING_RINGING)

        if (!isOutgoing) {
            startRingtone()
        }
        // Tapped Accept directly from the incoming-call notification —
        // skip straight to the accept flow (still goes through the same
        // mic-permission check userAcceptedCall() always does) instead
        // of making the user tap Accept a second time once this screen
        // is on top.
        if (!isOutgoing && autoAccept) {
            userAcceptedCall()
        }

        checkPermissionAndProceed()
    }

    private fun loadAvatar(displayName: String, photoUrl: String?) {
        val color = resources.getColor(com.privatechat.app.R.color.primary, theme)
        val fallback = NotificationAvatarFactory.create(
            resources.displayMetrics.density, displayName.firstOrNull() ?: '?', color
        )
        if (!photoUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(photoUrl)
                .transform(CircleCrop())
                .placeholder(android.graphics.drawable.BitmapDrawable(resources, fallback))
                .into(binding.callAvatar)
        } else {
            binding.callAvatar.setImageBitmap(fallback)
        }
    }

    private fun checkPermissionAndProceed() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            proceedAfterPermission()
        } else if (isOutgoing) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        // Incoming side: permission is only actually needed once the
        // user taps Accept — checked again there — so a missing
        // permission doesn't block the ringing UI itself from showing.
    }

    private fun proceedAfterPermission() {
        if (isOutgoing) {
            startOutgoingCall()
        } else {
            userWantsToAccept = true
            renderState(CallState.CONNECTING)
            tryAcceptIfReady()
        }
    }

    // ── Outgoing ──────────────────────────────────────────────────

    private fun startOutgoingCall() {
        signaling.startCall(remoteUser)
        val client = WebRtcClient(applicationContext, this)
        webRtcClient = client
        client.start()
        flushPendingCandidates()
        client.createOffer { sdp -> signaling.setOffer(sdp) }
    }

    // ── Incoming ──────────────────────────────────────────────────

    private fun userAcceptedCall() {
        stopRingtone()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        userWantsToAccept = true
        renderState(CallState.CONNECTING)
        tryAcceptIfReady()
    }

    private var lastKnownOfferSdp: String? = null
    // ICE candidates can (and often do) arrive from the other side
    // before this device's own WebRtcClient exists yet — e.g. every
    // candidate the caller sends while the callee simply hasn't
    // tapped Accept yet. Without buffering, onRemoteCandidate below
    // would silently drop them (webRtcClient?.addRemoteIceCandidate
    // is a no-op on null), and the call gets stuck forever in
    // "Connecting…" because ICE negotiation never receives enough
    // candidates to find a working path — this was the main cause of
    // calls not connecting/no audio. Flushed by flushPendingCandidates()
    // right after webRtcClient is actually created.
    private val pendingRemoteCandidates = mutableListOf<IceCandidateData>()

    // The caller's offer SDP arrives as a separate, slightly-later
    // Firebase write than the initial "ringing" session (see
    // CallSignalingRepository.startCall/setOffer) — so it's possible
    // for the user to tap Accept before it's actually landed here.
    // Called both right after Accept is tapped AND every time the
    // session updates, so whichever happens second is what actually
    // starts the answer — instead of a direct call that could
    // silently no-op if the offer wasn't there yet.
    private fun tryAcceptIfReady() {
        if (!userWantsToAccept || webRtcClient != null) return
        val offer = lastKnownOfferSdp ?: return
        acceptWithOffer(offer)
    }

    private fun acceptWithOffer(offerSdp: String) {
        val client = WebRtcClient(applicationContext, this)
        webRtcClient = client
        client.start()
        flushPendingCandidates()
        client.setRemoteOffer(offerSdp)
        client.createAnswer { sdp ->
            signaling.setAnswer(sdp)
            signaling.setStatus("accepted")
        }
    }

    private fun flushPendingCandidates() {
        val client = webRtcClient ?: return
        pendingRemoteCandidates.forEach { candidate ->
            client.addRemoteIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        }
        pendingRemoteCandidates.clear()
    }

    private fun userDeclinedCall() {
        stopRingtone()
        signaling.setStatus("declined")
        signaling.endCall()
        finishCall()
    }

    // ── Shared ────────────────────────────────────────────────────

    private fun userEndedCall() {
        stopRingtone()
        signaling.endCall()
        finishCall()
    }

    private fun handleSessionChange(session: CallSession?) {
        if (session == null) {
            // Other side ended/cleared the call, or it was declined.
            if (state != CallState.ENDED) finishCall()
            return
        }
        lastKnownOfferSdp = session.offerSdp
        tryAcceptIfReady()

        when (session.status) {
            "declined" -> if (isOutgoing) finishCall()
            "accepted" -> {
                if (isOutgoing && session.answerSdp != null && state != CallState.CONNECTED) {
                    webRtcClient?.setRemoteAnswer(session.answerSdp)
                    renderState(CallState.CONNECTING)
                }
            }
            "ended" -> finishCall()
        }
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        signaling.sendIceCandidate(
            currentUser,
            IceCandidateData(candidate.sdpMid ?: "", candidate.sdpMLineIndex, candidate.sdp)
        )
    }

    override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        runOnUiThread {
            when (state) {
                // COMPLETED is also a fully-working connection — some
                // networks/candidate pairs go straight from CHECKING to
                // COMPLETED without an intermediate CONNECTED event, and
                // only watching for CONNECTED left the UI stuck showing
                // "Connecting…" even once audio was already flowing.
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (this.state != CallState.CONNECTED) renderState(CallState.CONNECTED)
                }
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    if (this.state != CallState.ENDED) finishCall()
                }
                else -> {}
            }
        }
    }

    private fun renderState(newState: CallState) {
        state = newState
        when (newState) {
            CallState.OUTGOING_RINGING -> {
                binding.callStatus.text = "Calling…"
                binding.callIncomingControls.visibility = android.view.View.GONE
                binding.callInProgressControls.visibility = android.view.View.GONE
                binding.callEndButton.visibility = android.view.View.VISIBLE
            }
            CallState.INCOMING_RINGING -> {
                binding.callStatus.text = "Incoming call…"
                binding.callIncomingControls.visibility = android.view.View.VISIBLE
                binding.callInProgressControls.visibility = android.view.View.GONE
                binding.callEndButton.visibility = android.view.View.GONE
            }
            CallState.CONNECTING -> {
                binding.callStatus.text = "Connecting…"
                binding.callIncomingControls.visibility = android.view.View.GONE
                binding.callInProgressControls.visibility = android.view.View.GONE
                binding.callEndButton.visibility = android.view.View.VISIBLE
            }
            CallState.CONNECTED -> {
                binding.callIncomingControls.visibility = android.view.View.GONE
                binding.callInProgressControls.visibility = android.view.View.VISIBLE
                binding.callEndButton.visibility = android.view.View.VISIBLE
                startDurationTimer()
            }
            CallState.ENDED -> {
                binding.callStatus.text = "Call ended"
            }
        }
    }

    private fun startDurationTimer() {
        callStartElapsedMs = System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())
        durationHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = ((System.currentTimeMillis() - callStartElapsedMs) / 1000).toInt()
                binding.callStatus.text = String.format("%d:%02d", elapsed / 60, elapsed % 60)
                handler.postDelayed(this, 1000)
            }
        }
        durationRunnable = runnable
        handler.post(runnable)
    }

    private fun stopDurationTimer() {
        durationRunnable?.let { durationHandler?.removeCallbacks(it) }
        durationRunnable = null
    }

    private fun toggleMute() {
        isMuted = !isMuted
        webRtcClient?.setMuted(isMuted)
        binding.callMuteButton.setImageResource(
            if (isMuted) com.privatechat.app.R.drawable.ic_mic_off else com.privatechat.app.R.drawable.ic_mic
        )
        binding.callMuteButton.alpha = if (isMuted) 0.5f else 1f
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = isSpeakerOn
        binding.callSpeakerButton.alpha = if (isSpeakerOn) 1f else 0.5f
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
        } catch (e: Exception) {
            // No default ringtone available on this device/build —
            // the visual incoming-call UI still works without sound.
        }
    }

    private fun stopRingtone() {
        ringtone?.stop()
        ringtone = null
    }

    private fun finishCall() {
        if (state == CallState.ENDED) return
        renderState(CallState.ENDED)
        stopRingtone()
        stopDurationTimer()
        webRtcClient?.close()
        webRtcClient = null
        signaling.detachAll(remoteUser)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingtone()
        stopDurationTimer()
        webRtcClient?.close()
        signaling.detachAll(remoteUser)
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
    }

    companion object {
        const val EXTRA_REMOTE_USER = "remote_user"
        const val EXTRA_IS_OUTGOING = "is_outgoing"
        const val EXTRA_REMOTE_PHOTO_URL = "remote_photo_url"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"

        // Read by ChatFirebaseMessagingService to skip showing a
        // redundant full-screen incoming-call notification when this
        // screen is already open and ringing (call arrived while the
        // app was in the foreground, via the live Firebase listener in
        // ChatActivity) — same pattern as ChatActivity.isForeground.
        @Volatile
        var isForeground = false
    }
}
