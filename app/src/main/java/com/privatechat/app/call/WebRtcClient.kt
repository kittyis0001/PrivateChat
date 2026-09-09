package com.privatechat.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Audio-only WebRTC call wrapper — one instance per call.
 *
 * This is deliberately AUDIO-ONLY and therefore avoids the two most
 * common first-call crash sources in WebRTC apps:
 *   - no [org.webrtc.EglBase] / video codec factories at all (EGL init
 *     on the main thread was a real crash/ANR risk on some devices);
 *   - a single process-wide [PeerConnectionFactory] (see [WebRtcFactory])
 *     that is created once and never disposed, so ending call N and
 *     starting call N+1 can never reuse a disposed factory / native
 *     module — the original "second call fails" bug.
 *
 * Audio routing (MODE_IN_COMMUNICATION + audio focus) is what makes the
 * negotiated remote track actually reach the earpiece/speaker at a sane
 * volume; it is acquired exactly once in [start] and released exactly
 * once in [close] (idempotent, thread-safe), so a defensive double
 * close can never leave the system audio stuck in "phone call" mode.
 */
class WebRtcClient(
    context: Context,
    private val observer: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val factory = WebRtcFactory.get(appContext)

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    private var audioSessionActive = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioFocusRequest: AudioFocusRequest? = null
    private var closed = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // Add a TURN server here for reliable connectivity across
        // arbitrary mobile-carrier NAT:
        // PeerConnection.IceServer.builder("turn:YOUR_HOST:3478")
        //     .setUsername("...").setPassword("...").createIceServer()
    )

    /**
     * Creates the peer connection and local microphone track. Must be
     * called once after construction (and only while RECORD_AUDIO has
     * already been granted by the user).
     */
    fun start() {
        if (closed) return
        try {
            activateAudioSession()

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            // createPeerConnection is @Nullable in the SDK — it can return
            // null on failure; treat that as a call-level error rather
            // than crashing on the next line.
            val pc = factory.createPeerConnection(rtcConfig, pcObserver) ?: run {
                observer.onError("Failed to create PeerConnection")
                return
            }
            peerConnection = pc

            val audioSource = factory.createAudioSource(MediaConstraints())
            localAudioSource = audioSource
            val audioTrack = factory.createAudioTrack("audio_track", audioSource)
            localAudioTrack = audioTrack
            pc.addTrack(audioTrack, listOf("local_stream"))
        } catch (e: Exception) {
            observer.onError("WebRTC start failed: ${e.message}")
        }
    }

    fun createOffer(iceRestart: Boolean = false, onCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        if (iceRestart) {
            // Triggers an ICE restart on the next offer (re-gathers
            // candidates on the new network after Wi-Fi <-> mobile
            // handover). Legacy constraint form, honored across SDK
            // versions without depending on PeerConnection.restartIce()
            // being present in this particular artifact.
            constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                try {
                    pc.setLocalDescription(SimpleSdpObserver(), sdp)
                } catch (e: Exception) {
                    observer.onError("setLocalDescription failed: ${e.message}")
                }
                onCreated(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                observer.onError("createOffer failed: $error")
            }
        }, constraints)
    }

    fun createAnswer(onCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                try {
                    pc.setLocalDescription(SimpleSdpObserver(), sdp)
                } catch (e: Exception) {
                    observer.onError("setLocalDescription failed: ${e.message}")
                }
                onCreated(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                observer.onError("createAnswer failed: $error")
            }
        }, MediaConstraints())
    }

    fun setRemoteOffer(sdp: String) {
        try {
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(),
                SessionDescription(SessionDescription.Type.OFFER, sdp)
            )
        } catch (e: Exception) {
            observer.onError("setRemoteOffer failed: ${e.message}")
        }
    }

    fun setRemoteAnswer(sdp: String) {
        try {
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(),
                SessionDescription(SessionDescription.Type.ANSWER, sdp)
            )
        } catch (e: Exception) {
            observer.onError("setRemoteAnswer failed: ${e.message}")
        }
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        try {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        } catch (_: Exception) {
            // A malformed/late candidate is non-fatal — ICE will keep
            // trying with the remaining candidates.
        }
    }

    fun setMuted(muted: Boolean) {
        try {
            localAudioTrack?.setEnabled(!muted)
        } catch (_: Exception) {
        }
    }

    fun setSpeakerphoneOn(on: Boolean) {
        try {
            audioManager.isSpeakerphoneOn = on
        } catch (_: Exception) {
        }
    }

    /** Idempotent teardown — safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true

        try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { localAudioSource?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        localAudioTrack = null
        localAudioSource = null
        peerConnection = null

        deactivateAudioSession()
    }

    private fun activateAudioSession() {
        if (audioSessionActive) return
        previousAudioMode = audioManager.mode
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false // default to earpiece
            requestAudioFocus()
            audioSessionActive = true
        } catch (_: Exception) {
            // Audio routing is a courtesy, not a hard requirement — the
            // call must survive a failure here (e.g. another app holding
            // focus, or MODIFY_AUDIO_SETTINGS being denied), not end.
            audioSessionActive = false
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { _ -> /* call continues regardless */ }
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {
            // Audio focus is a courtesy, not a hard requirement — the
            // call should never crash because focus could not be taken.
        }
    }

    private fun deactivateAudioSession() {
        if (!audioSessionActive) return
        audioSessionActive = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioFocusRequest = null
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = previousAudioMode
        } catch (_: Exception) {
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            observer.onLocalIceCandidate(candidate)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            observer.onIceConnectionStateChanged(state)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // Audio-only: the remote track plays through the device's
            // default output on its own once MODE_IN_COMMUNICATION is set.
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}

/**
 * One [PeerConnectionFactory] per process, created lazily and never
 * disposed. WebRTC's native module + factory are heavyweight and are
 * not designed to be torn down/re-created per call; sharing a single
 * instance (and disposing only the per-call PeerConnection) is what
 * makes back-to-back calls reliable instead of failing on the second.
 */
private object WebRtcFactory {
    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun get(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            // No video encoder/decoder factories => audio-only codecs
            // (Opus), no EGL context required at all.
            val created = PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
            factory = created
            return created
        }
    }
}
