package com.privatechat.app.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Audio-only WebRTC call wrapper — one instance per call. Handles the
 * PeerConnectionFactory/PeerConnection lifecycle, local mic track,
 * offer/answer creation, and ICE candidate exchange; the actual SDP/
 * candidate transport is CallSignalingRepository's job, not this
 * class's — this only knows about WebRTC, never about Firebase.
 *
 * STUN-only by default (Google's public servers, free, no signup) —
 * works on favorable networks (same wifi, many home connections) but
 * not reliably across arbitrary mobile-carrier NAT. A TURN server
 * (see CallActivity's own comment) is what closes that gap; add its
 * IceServer entry in [iceServers] once you have one.
 */
class WebRtcClient(
    context: Context,
    private val observer: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
    }

    private val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // Add a TURN server here for reliable connectivity across
        // arbitrary networks, e.g.:
        // PeerConnection.IceServer.builder("turn:YOUR_TURN_HOST:3478")
        //     .setUsername("...").setPassword("...").createIceServer()
    )

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, pcObserver)

        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioSource = audioSource
        val audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
        localAudioTrack = audioTrack
        peerConnection?.addTrack(audioTrack, listOf("local_stream"))
    }

    fun createOffer(onCreated: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                onCreated(sdp.description)
            }
        }, constraints)
    }

    fun createAnswer(onCreated: (String) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                onCreated(sdp.description)
            }
        }, constraints)
    }

    fun setRemoteOffer(sdp: String) {
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    fun setRemoteAnswer(sdp: String) {
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun close() {
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
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
            // Audio-only call: once WebRTC negotiates the remote track,
            // it plays through the device's default audio output on
            // its own — no manual renderer/attach step needed the way
            // a video track would require a SurfaceViewRenderer.
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
