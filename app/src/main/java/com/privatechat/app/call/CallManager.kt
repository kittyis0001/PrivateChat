package com.privatechat.app.call

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.database.FirebaseDatabase
import com.privatechat.app.App
import com.privatechat.app.R
import com.privatechat.app.data.Nicknames
import com.privatechat.app.data.Session
import com.privatechat.app.utils.NotificationAvatarFactory
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-scoped owner of the voice-call lifecycle — the single
 * source of truth that survives Activity rotation/destroy, app
 * minimize, and lock-screen, because it lives in the process (not in
 * any screen). CallActivity is only a thin renderer of [snapshot];
 * CallForegroundService only keeps the process alive; the notification
 * action receiver only forwards taps here. Exactly one call can exist
 * at a time (guarded by [isBusy] plus the atomic session claim in
 * CallSignalingRepository.startCall), so there can never be duplicate
 * call screens, duplicate notifications, or two simultaneous calls.
 *
 * All state mutations happen on the main thread; WebRTC/audio work is
 * marshalled here from its own threads via [mainHandler].
 */
object CallManager {

    enum class Phase { IDLE, DIALING, INCOMING, CONNECTING, ACTIVE, ENDED }

    enum class EndReason { LOCAL_HANGUP, REMOTE_ENDED, DECLINED, BUSY, NO_ANSWER, FAILED }

    data class CallSnapshot(
        val phase: Phase = Phase.IDLE,
        val remoteUser: String = "",
        val remoteName: String = "",
        val photoUrl: String? = null,
        val isOutgoing: Boolean = false,
        val muted: Boolean = false,
        val speakerOn: Boolean = false,
        val connectedAtMs: Long = 0L,
        val statusMessage: String = "",
        val endReason: EndReason? = null,
        val missed: Boolean = false
    )

    interface Listener {
        fun onCallStateChanged(snapshot: CallSnapshot)
    }

    // ── Call state (main-thread only) ──────────────────────────────

    private var appContext: Context? = null
    private var signaling: CallSignalingRepository? = null
    private var webRtcClient: WebRtcClient? = null
    private var ringback: ToneGenerator? = null

    private var phase = Phase.IDLE
    private var remoteUser = ""
    private var remoteName = ""
    private var photoUrl: String? = null
    private var isOutgoing = false
    private var muted = false
    private var speakerOn = false
    private var connectedAtMs = 0L
    private var statusMessage = ""
    private var endReason: EndReason? = null
    private var missed = false

    private var userWantsToAnswer = false
    private var awaitingAnswer = false
    private var lastSeenOffer: String? = null
    private var lastSeenAnswer: String? = null
    private val pendingCandidates = mutableListOf<IceCandidateData>()
    private var cleanupDone = false

    // Timers / reconnect state.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialTimeoutRunnable: Runnable? = null
    private var ringTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectDeadlineAt = 0L
    private var reconnectPending = false

    private val listeners = CopyOnWriteArrayList<Listener>()

    // ── Public API ─────────────────────────────────────────────────

    /** True while a call is dialing, ringing, connecting, or active. */
    fun isBusy(): Boolean =
        phase == Phase.DIALING || phase == Phase.INCOMING ||
            phase == Phase.CONNECTING || phase == Phase.ACTIVE

    fun currentPhase(): Phase = phase

    fun snapshot(): CallSnapshot = buildSnapshot()

    fun elapsedSeconds(): Long =
        if (phase == Phase.ACTIVE && connectedAtMs > 0) (System.currentTimeMillis() - connectedAtMs) / 1000 else 0L

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Starts an outgoing call. Returns false only when another call is
     * already in progress or the session/user is unavailable.
     */
    fun startOutgoingCall(context: Context, remoteUserId: String, remotePhotoUrl: String?): Boolean {
        if (isBusy()) return false
        val currentUser = Session.currentUser() ?: return false

        appContext = context.applicationContext
        remoteUser = remoteUserId
        remoteName = Nicknames.defaultFor(remoteUserId)
        photoUrl = remotePhotoUrl
        isOutgoing = true
        muted = false
        speakerOn = false
        connectedAtMs = 0L
        statusMessage = ""
        endReason = null
        missed = false
        userWantsToAnswer = false
        awaitingAnswer = false
        lastSeenOffer = null
        lastSeenAnswer = null
        pendingCandidates.clear()
        cleanupDone = false
        reconnectPending = false
        phase = Phase.DIALING

        signaling = CallSignalingRepository(currentUser)
        attachSignalingListeners()

        publish()

        signaling!!.startCall(remoteUserId) { committed ->
            // runTransaction's onComplete may run on a background thread;
            // all state + notification/audio work is main-thread only.
            runOnMain {
                if (cleanupDone || phase != Phase.DIALING) return@runOnMain
                if (!committed) {
                    // Another call already owns the session -> busy.
                    finishCall(EndReason.BUSY, logMissed = false)
                } else {
                    createWebRtcClient()
                    webRtcClient?.createOffer(iceRestart = false) { sdp ->
                        runOnMain { if (!cleanupDone) { awaitingAnswer = true; signaling?.setOffer(sdp) } }
                    }
                    CallForegroundService.start(
                        appContext!!, remoteName, "Calling…", remoteUser, isOutgoing = true
                    )
                    startRingback()
                    startDialTimeout()
                }
            }
        }
        return true
    }

    /**
     * Handles an incoming call (from the live DB watcher while the app
     * is foreground, or from the FCM data push while backgrounded or
     * killed). [launchUi] should be true only when the app is already
     * visible (so the call screen opens immediately); otherwise the
     * full-screen notification takes care of surfacing it.
     *
     * Returns false when another call is already in progress.
     */
    fun onIncomingCall(context: Context, callerId: String, remotePhotoUrl: String?, launchUi: Boolean): Boolean {
        if (isBusy()) return false
        val currentUser = Session.currentUser() ?: return false

        appContext = context.applicationContext
        remoteUser = callerId
        remoteName = Nicknames.defaultFor(callerId)
        photoUrl = remotePhotoUrl
        isOutgoing = false
        muted = false
        speakerOn = false
        connectedAtMs = 0L
        statusMessage = ""
        endReason = null
        missed = false
        userWantsToAnswer = false
        awaitingAnswer = false
        lastSeenOffer = null
        lastSeenAnswer = null
        pendingCandidates.clear()
        cleanupDone = false
        reconnectPending = false
        phase = Phase.INCOMING

        signaling = CallSignalingRepository(currentUser)
        attachSignalingListeners()

        // Resolve a missing photo lazily from the shared photos/ node.
        if (photoUrl.isNullOrBlank()) fetchRemotePhoto(callerId)

        showIncomingNotification()
        if (launchUi) launchCallActivity(autoAccept = false)
        startRingTimeout()
        publish()
        return true
    }

    /** Called from CallActivity once RECORD_AUDIO is granted. */
    fun acceptCall(context: Context) {
        if (phase != Phase.INCOMING) return
        cancelIncomingNotification()
        userWantsToAnswer = true
        phase = Phase.CONNECTING
        statusMessage = "Connecting…"
        publish()
        answerIfReady(context)
    }

    /** Called from the incoming-call UI Decline button. */
    fun declineCall(context: Context) {
        if (phase != Phase.INCOMING) return
        cancelIncomingNotification()
        signaling?.setStatus("declined")
        signaling?.endCall()
        finishCall(EndReason.DECLINED, logMissed = false)
    }

    /** Called from the call screen End button, or the ongoing notification's Hang up. */
    fun endCall(context: Context) {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return
        if (phase == Phase.INCOMING) {
            // Callee rejecting while still ringing = decline.
            declineCall(context)
            return
        }
        signaling?.setStatus("ended")
        signaling?.endCall()
        finishCall(EndReason.LOCAL_HANGUP, logMissed = false)
    }

    /** Notification action receiver entry points. */
    fun declineFromNotification(context: Context) {
        if (phase != Phase.INCOMING) {
            cancelIncomingNotification()
            return
        }
        declineCall(context)
    }

    fun endFromNotification(context: Context) {
        endCall(context)
    }

    fun toggleMute(): Boolean {
        muted = !muted
        webRtcClient?.setMuted(muted)
        publish()
        return muted
    }

    fun toggleSpeaker(): Boolean {
        speakerOn = !speakerOn
        webRtcClient?.setSpeakerphoneOn(speakerOn)
        publish()
        return speakerOn
    }

    // ── Internals ──────────────────────────────────────────────────

    private fun createWebRtcClient() {
        if (webRtcClient != null) return
        val client = WebRtcClient(appContext!!, webrtcListener)
        webRtcClient = client
        client.start()
        flushPendingCandidates()
    }

    private fun answerIfReady(context: Context) {
        if (!userWantsToAnswer || webRtcClient != null) return
        val offer = lastSeenOffer ?: return
        createWebRtcClient()
        webRtcClient?.setRemoteOffer(offer)
        webRtcClient?.createAnswer { sdp ->
            runOnMain {
                if (cleanupDone) return@runOnMain
                signaling?.setAnswer(sdp)
                signaling?.setStatus("accepted")
            }
        }
        CallForegroundService.start(appContext!!, remoteName, "Connecting…", remoteUser, isOutgoing = false)
    }

    private fun flushPendingCandidates() {
        val client = webRtcClient ?: return
        pendingCandidates.forEach { c -> client.addRemoteIceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate) }
        pendingCandidates.clear()
    }

    private fun attachSignalingListeners() {
        val sig = signaling ?: return
        sig.onSessionChanged = { session -> runOnMain { handleSession(session) } }
        sig.onRemoteCandidate = { candidate ->
            runOnMain {
                val client = webRtcClient
                if (client != null) {
                    client.addRemoteIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
                } else {
                    pendingCandidates.add(candidate)
                }
            }
        }
        sig.attachSessionListener()
        sig.attachRemoteCandidateListener(remoteUser)
    }

    private fun handleSession(session: CallSession?) {
        if (cleanupDone) return
        if (session == null) {
            // The remote side (or a stale cleanup) cleared the session.
            when (phase) {
                Phase.INCOMING -> finishCall(EndReason.REMOTE_ENDED, logMissed = true)
                Phase.DIALING, Phase.CONNECTING, Phase.ACTIVE -> finishCall(EndReason.REMOTE_ENDED, logMissed = false)
                else -> {}
            }
            return
        }

        val offer = session.offerSdp
        val answer = session.answerSdp

        // Answer SDP (initial outgoing, or an ICE-restart re-answer).
        if (answer != null && answer != lastSeenAnswer) {
            lastSeenAnswer = answer
            if (awaitingAnswer) {
                awaitingAnswer = false
                stopRingback()
                webRtcClient?.setRemoteAnswer(answer)
                if (phase == Phase.DIALING) {
                    phase = Phase.CONNECTING
                    statusMessage = "Connecting…"
                    publish()
                }
            }
        }

        // Offer SDP (initial incoming offer, or an ICE-restart re-offer).
        if (offer != null && offer != lastSeenOffer) {
            lastSeenOffer = offer
            if (awaitingAnswer) {
                // Echo of our own (re)offer — wait for the answer.
            } else if (webRtcClient != null && (phase == Phase.CONNECTING || phase == Phase.ACTIVE)) {
                // Remote wants to renegotiate (e.g. ICE restart after a
                // network handover) — answer the fresh offer.
                webRtcClient?.setRemoteOffer(offer)
                webRtcClient?.createAnswer { sdp ->
                    runOnMain { if (!cleanupDone) signaling?.setAnswer(sdp) }
                }
            } else if (!isOutgoing && webRtcClient == null) {
                // Initial offer for an incoming call; answer it if the
                // user already accepted (offer arrived slightly later
                // than the Accept tap).
                if (userWantsToAnswer) answerIfReady(appContext!!)
            }
        }

        when (session.status) {
            "declined" -> {
                if (isOutgoing && phase == Phase.DIALING) finishCall(EndReason.DECLINED, logMissed = false)
            }
            "busy" -> {
                if (isOutgoing && phase == Phase.DIALING) finishCall(EndReason.BUSY, logMissed = false)
            }
            "ended" -> {
                when (phase) {
                    Phase.INCOMING -> finishCall(EndReason.REMOTE_ENDED, logMissed = true)
                    Phase.DIALING, Phase.CONNECTING, Phase.ACTIVE ->
                        finishCall(EndReason.REMOTE_ENDED, logMissed = false)
                    else -> {}
                }
            }
            "accepted" -> {
                // Answer handling happens via answerSdp above; nothing to do here.
            }
            "ringing" -> {
                // A fresh ringing session addressed to me while I'm
                // already in a call -> the caller's transaction must
                // have been aborted, but answer busy defensively.
                if (isBusy() && session.callee == Session.currentUser() && session.caller != remoteUser) {
                    replyBusy()
                }
            }
        }
    }

    private fun replyBusy() {
        val currentUser = Session.currentUser() ?: return
        val repo = CallSignalingRepository(currentUser)
        repo.setStatus("busy")
        repo.endCall()
    }

    private fun handleIceState(state: PeerConnection.IceConnectionState) {
        if (cleanupDone) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                stopRingback()
                val wasReconnecting = reconnectPending
                cancelReconnect()
                if (phase == Phase.DIALING || phase == Phase.CONNECTING) {
                    phase = Phase.ACTIVE
                    connectedAtMs = System.currentTimeMillis()
                    statusMessage = ""
                    cancelDialTimeout()
                    updateOngoingNotification("Ongoing call")
                    publish()
                } else if (phase == Phase.ACTIVE && wasReconnecting) {
                    statusMessage = ""
                    updateOngoingNotification("Ongoing call")
                    publish()
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                if (phase == Phase.ACTIVE || phase == Phase.CONNECTING) {
                    if (!reconnectPending) {
                        reconnectPending = true
                        statusMessage = "Reconnecting…"
                        reconnectDeadlineAt = System.currentTimeMillis() + RECONNECT_BUDGET_MS
                        updateOngoingNotification("Reconnecting…")
                        publish()
                        scheduleReconnectAttempt()
                    }
                }
            }
            PeerConnection.IceConnectionState.FAILED -> {
                if (phase == Phase.ACTIVE || phase == Phase.CONNECTING || phase == Phase.DIALING) {
                    finishCall(EndReason.FAILED, logMissed = false)
                }
            }
            else -> {}
        }
    }

    private fun scheduleReconnectAttempt() {
        cancelReconnectRunnable()
        reconnectRunnable = Runnable { attemptReconnect() }
        mainHandler.postDelayed(reconnectRunnable!!, RECONNECT_ATTEMPT_DELAY_MS)
    }

    private fun attemptReconnect() {
        reconnectRunnable = null
        if (cleanupDone || (phase != Phase.ACTIVE && phase != Phase.CONNECTING)) return
        if (System.currentTimeMillis() > reconnectDeadlineAt) {
            finishCall(EndReason.FAILED, logMissed = false)
            return
        }
        // Re-gather ICE candidates on the current network and re-offer.
        webRtcClient?.createOffer(iceRestart = true) { sdp ->
            runOnMain {
                if (!cleanupDone) {
                    awaitingAnswer = true
                    signaling?.setOffer(sdp)
                }
            }
        }
        scheduleReconnectAttempt()
    }

    private fun cancelReconnect() {
        cancelReconnectRunnable()
        reconnectPending = false
    }

    private fun cancelReconnectRunnable() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // ── Timeouts ───────────────────────────────────────────────────

    private fun startDialTimeout() {
        cancelDialTimeout()
        dialTimeoutRunnable = Runnable {
            dialTimeoutRunnable = null
            if (phase == Phase.DIALING) {
                signaling?.setStatus("ended")
                signaling?.endCall()
                finishCall(EndReason.NO_ANSWER, logMissed = false)
            }
        }
        mainHandler.postDelayed(dialTimeoutRunnable!!, DIAL_TIMEOUT_MS)
    }

    private fun cancelDialTimeout() {
        dialTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        dialTimeoutRunnable = null
    }

    private fun startRingTimeout() {
        cancelRingTimeout()
        ringTimeoutRunnable = Runnable {
            ringTimeoutRunnable = null
            if (phase == Phase.INCOMING) {
                finishCall(EndReason.NO_ANSWER, logMissed = true)
            }
        }
        mainHandler.postDelayed(ringTimeoutRunnable!!, RING_TIMEOUT_MS)
    }

    private fun cancelRingTimeout() {
        ringTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        ringTimeoutRunnable = null
    }

    // ── Ringback (outgoing) ────────────────────────────────────────

    private fun startRingback() {
        stopRingback()
        try {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME)
            ringback?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 0)
        } catch (_: Exception) {
            // Ringback is cosmetic — the call proceeds without it.
        }
    }

    private fun stopRingback() {
        try { ringback?.stopTone() } catch (_: Exception) {}
        try { ringback?.release() } catch (_: Exception) {}
        ringback = null
    }

    // ── Teardown ───────────────────────────────────────────────────

    private fun finishCall(reason: EndReason, logMissed: Boolean) {
        if (cleanupDone) return
        cleanupDone = true

        cancelDialTimeout()
        cancelRingTimeout()
        cancelReconnect()
        stopRingback()
        cancelIncomingNotification()

        endReason = reason
        missed = logMissed
        phase = Phase.ENDED
        statusMessage = ""

        if (logMissed) logMissedCall()

        // Release WebRTC + audio off the main thread so the UI never
        // stutters during teardown; the shared factory survives for the
        // next call.
        val client = webRtcClient
        webRtcClient = null
        if (client != null) {
            Thread { client.close() }.start()
        }

        signaling?.detachAll(remoteUser)
        signaling = null
        CallForegroundService.stop(appContext!!)

        publish()

        // Allow a fresh call as soon as the screen shows "ended".
        mainHandler.postDelayed({ if (phase == Phase.ENDED) phase = Phase.IDLE }, ENDED_RESET_DELAY_MS)
    }

    private fun logMissedCall() {
        try {
            FirebaseDatabase.getInstance(CallSignalingRepository.DB_URL)
                .getReference("messages")
                .push()
                .setValue(
                    mapOf(
                        "name" to remoteUser,
                        "text" to "Missed voice call",
                        "time" to System.currentTimeMillis(),
                        "seen" to true,
                        "type" to "system"
                    )
                )
        } catch (_: Exception) {
            // Never let call logging take the call screen down.
        }
    }

    private fun fetchRemotePhoto(userId: String) {
        try {
            FirebaseDatabase.getInstance(CallSignalingRepository.DB_URL)
                .getReference("photos")
                .child(userId)
                .get()
                .addOnSuccessListener { snap ->
                    val url = snap.getValue(String::class.java)
                    if (!url.isNullOrBlank() && remoteUser == userId) {
                        photoUrl = url
                        publish()
                    }
                }
        } catch (_: Exception) {
        }
    }

    // ── Notifications ──────────────────────────────────────────────

    private fun showIncomingNotification() {
        val context = appContext ?: return

        val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
            putExtra(CallActivity.EXTRA_REMOTE_PHOTO_URL, photoUrl)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
            putExtra(CallActivity.EXTRA_REMOTE_PHOTO_URL, photoUrl)
            putExtra(CallActivity.EXTRA_AUTO_ACCEPT, true)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context, 1, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE_CALL
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 2, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val avatar = NotificationAvatarFactory.create(
            context.resources.displayMetrics.density,
            remoteName.firstOrNull() ?: '?',
            Color.parseColor("#B09EF5")
        )

        val notification = NotificationCompat.Builder(context, App.CALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#7ec8f7"))
            .setLargeIcon(avatar)
            .setContentTitle(remoteName)
            .setContentText("Incoming voice call…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            // Safety net: if the process is killed while ringing (so the
            // in-app ring-timeout can't run), the OS dismisses the stale
            // notification on its own instead of ringing forever.
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, highPriority = true)
            .addAction(R.drawable.ic_call_end, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_call_accept, "Accept", acceptPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the in-app UI still rings.
        }
    }

    private fun cancelIncomingNotification() {
        val context = appContext ?: return
        try {
            NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun updateOngoingNotification(statusText: String) {
        val context = appContext ?: return
        CallForegroundService.update(context, remoteName, statusText, remoteUser, isOutgoing)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun launchCallActivity(autoAccept: Boolean) {
        val context = appContext ?: return
        val intent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_REMOTE_USER, remoteUser)
            putExtra(CallActivity.EXTRA_IS_OUTGOING, false)
            putExtra(CallActivity.EXTRA_REMOTE_PHOTO_URL, photoUrl)
            putExtra(CallActivity.EXTRA_AUTO_ACCEPT, autoAccept)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Background-activity-launch restriction — the full-screen
            // notification is the fallback surface.
        }
    }

    private fun buildSnapshot() = CallSnapshot(
        phase = phase,
        remoteUser = remoteUser,
        remoteName = remoteName,
        photoUrl = photoUrl,
        isOutgoing = isOutgoing,
        muted = muted,
        speakerOn = speakerOn,
        connectedAtMs = connectedAtMs,
        statusMessage = statusMessage,
        endReason = endReason,
        missed = missed
    )

    private fun publish() {
        val snap = buildSnapshot()
        listeners.forEach { it.onCallStateChanged(snap) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private val webrtcListener = object : WebRtcClient.Listener {
        override fun onLocalIceCandidate(candidate: IceCandidate) {
            val fromUser = Session.currentUser() ?: return
            runOnMain {
                if (!cleanupDone) {
                    signaling?.sendIceCandidate(
                        fromUser,
                        IceCandidateData(candidate.sdpMid ?: "", candidate.sdpMLineIndex, candidate.sdp)
                    )
                }
            }
        }

        override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
            runOnMain { handleIceState(state) }
        }

        override fun onError(message: String) {
            // Surface nothing here; ICE/network state drives the UI.
        }
    }

    // ── Constants ──────────────────────────────────────────────────

    const val INCOMING_CALL_NOTIFICATION_ID = 1002

    private const val DIAL_TIMEOUT_MS = 45_000L
    private const val RING_TIMEOUT_MS = 60_000L
    private const val RECONNECT_ATTEMPT_DELAY_MS = 8_000L
    private const val RECONNECT_BUDGET_MS = 45_000L
    private const val ENDED_RESET_DELAY_MS = 900L
    private const val RINGBACK_VOLUME = 80
}
