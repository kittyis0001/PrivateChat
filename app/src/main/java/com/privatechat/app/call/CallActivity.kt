package com.privatechat.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.privatechat.app.R
import com.privatechat.app.data.Nicknames
import com.privatechat.app.databinding.ActivityCallBinding
import com.privatechat.app.utils.NotificationAvatarFactory
import java.util.Locale

/**
 * Full-screen, WhatsApp-style voice call UI. This Activity is only a
 * renderer: it binds to [CallManager.snapshot] (process scope) and
 * forwards every user action (accept / decline / end / mute / speaker)
 * into CallManager, so rotating the device, pressing Home, locking the
 * screen, or the OS destroying/recreating this screen never affects the
 * actual call — the call keeps running in CallManager + the foreground
 * service, and this screen just re-attaches to it.
 *
 * It also owns the one thing only a visible Activity can do: request
 * RECORD_AUDIO (for outgoing dialing and for accepting an incoming
 * call, including when Accept was tapped on the notification).
 */
class CallActivity : AppCompatActivity(), CallManager.Listener {

    private lateinit var binding: ActivityCallBinding

    private var remoteUser = ""
    private var isOutgoing = false
    private var photoUrl: String? = null
    private var autoAccept = false

    private enum class PermissionAction { NONE, START_OUTGOING, ACCEPT }
    private var pendingPermissionAction = PermissionAction.NONE

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onMicPermissionGranted() else onMicPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remoteUser = intent.getStringExtra(EXTRA_REMOTE_USER).orEmpty()
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)
        photoUrl = intent.getStringExtra(EXTRA_REMOTE_PHOTO_URL)
        autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)

        if (remoteUser.isEmpty()) {
            finish()
            return
        }

        wireButtons()

        if (isOutgoing) {
            startOutgoingIfPossible()
        } else {
            // Incoming: CallManager is normally already INCOMING (from
            // the FCM push or the live DB watcher), but this screen can
            // also be re-opened (ongoing-notification tap) while the
            // call is CONNECTING/ACTIVE/ENDED — only a fully-idle
            // manager means the call is gone and this launch is stale.
            val current = CallManager.currentPhase()
            if (current == CallManager.Phase.IDLE) {
                finish()
                return
            }
            if (autoAccept && current == CallManager.Phase.INCOMING) {
                acceptAfterPermission()
            }
        }

        CallManager.addListener(this)
        render(CallManager.snapshot())
    }

    private fun wireButtons() {
        binding.callEndButton.setOnClickListener { CallManager.endCall(this) }
        binding.callDeclineButton.setOnClickListener { CallManager.declineCall(this) }
        binding.callAcceptButton.setOnClickListener { acceptAfterPermission() }
        binding.callMuteButton.setOnClickListener { CallManager.toggleMute() }
        binding.callSpeakerButton.setOnClickListener { CallManager.toggleSpeaker() }
    }

    private fun startOutgoingIfPossible() {
        if (CallManager.isBusy()) return // returning to an existing call
        pendingPermissionAction = PermissionAction.START_OUTGOING
        if (hasMicPermission()) {
            if (!CallManager.startOutgoingCall(this, remoteUser, photoUrl)) finish()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun acceptAfterPermission() {
        pendingPermissionAction = PermissionAction.ACCEPT
        if (hasMicPermission()) {
            CallManager.acceptCall(this)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onMicPermissionGranted() {
        when (pendingPermissionAction) {
            PermissionAction.START_OUTGOING -> {
                if (!CallManager.startOutgoingCall(this, remoteUser, photoUrl)) finish()
            }
            PermissionAction.ACCEPT -> CallManager.acceptCall(this)
            PermissionAction.NONE -> {}
        }
        pendingPermissionAction = PermissionAction.NONE
    }

    private fun onMicPermissionDenied() {
        when (pendingPermissionAction) {
            PermissionAction.START_OUTGOING -> finish()
            PermissionAction.ACCEPT -> {
                // Can't answer without the mic — decline so the caller
                // isn't left ringing forever.
                CallManager.declineCall(this)
                finish()
            }
            PermissionAction.NONE -> finish()
        }
        pendingPermissionAction = PermissionAction.NONE
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ── Rendering ──────────────────────────────────────────────────

    override fun onCallStateChanged(snapshot: CallManager.CallSnapshot) {
        runOnUiThread { render(snapshot) }
    }

    private fun render(s: CallManager.CallSnapshot) {
        val name = s.remoteName.ifBlank { Nicknames.defaultFor(s.remoteUser) }
        binding.callName.text = name
        loadAvatar(name, s.photoUrl)

        binding.callMuteButton.setImageResource(if (s.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        binding.callMuteButton.alpha = if (s.muted) 0.55f else 1f
        binding.callSpeakerButton.setImageResource(R.drawable.ic_speaker)
        binding.callSpeakerButton.alpha = if (s.speakerOn) 1f else 0.55f

        when (s.phase) {
            CallManager.Phase.DIALING -> {
                binding.callStatus.text = "Calling…"
                binding.callIncomingControls.visibility = View.GONE
                binding.callInProgressControls.visibility = View.GONE
                binding.callEndSection.visibility = View.VISIBLE
                stopTimer()
            }
            CallManager.Phase.INCOMING -> {
                binding.callStatus.text = "Incoming voice call…"
                binding.callIncomingControls.visibility = View.VISIBLE
                binding.callInProgressControls.visibility = View.GONE
                binding.callEndSection.visibility = View.GONE
                stopTimer()
            }
            CallManager.Phase.CONNECTING -> {
                binding.callStatus.text = s.statusMessage.ifBlank { "Connecting…" }
                binding.callIncomingControls.visibility = View.GONE
                binding.callInProgressControls.visibility = View.GONE
                binding.callEndSection.visibility = View.VISIBLE
                stopTimer()
            }
            CallManager.Phase.ACTIVE -> {
                val reconnecting = s.statusMessage.isNotBlank()
                binding.callStatus.text = if (reconnecting) s.statusMessage else formatElapsed(CallManager.elapsedSeconds())
                binding.callIncomingControls.visibility = View.GONE
                binding.callInProgressControls.visibility = View.VISIBLE
                binding.callEndSection.visibility = View.VISIBLE
                startTimer()
            }
            CallManager.Phase.ENDED -> showEnded(s)
            CallManager.Phase.IDLE -> finish()
        }
    }

    private fun showEnded(s: CallManager.CallSnapshot) {
        stopTimer()
        binding.callIncomingControls.visibility = View.GONE
        binding.callInProgressControls.visibility = View.GONE
        binding.callEndSection.visibility = View.GONE
        binding.callStatus.text = when {
            s.missed -> "Missed call"
            s.endReason == CallManager.EndReason.DECLINED -> "Call declined"
            s.endReason == CallManager.EndReason.BUSY -> "Line busy"
            s.endReason == CallManager.EndReason.NO_ANSWER -> "No answer"
            s.endReason == CallManager.EndReason.FAILED -> "Call failed"
            else -> "Call ended"
        }
        binding.root.postDelayed({ if (!isFinishing) finish() }, ENDED_FINISH_DELAY_MS)
    }

    private fun startTimer() {
        stopTimer()
        val runnable = object : Runnable {
            override fun run() {
                val snap = CallManager.snapshot()
                if (snap.phase == CallManager.Phase.ACTIVE) {
                    // Leave "Reconnecting…" untouched while it is shown.
                    if (snap.statusMessage.isBlank()) {
                        binding.callStatus.text = formatElapsed(CallManager.elapsedSeconds())
                    }
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerRunnable = runnable
        timerHandler.post(runnable)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun formatElapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun loadAvatar(displayName: String, url: String?) {
        val color = resources.getColor(R.color.primary, theme)
        val fallback = NotificationAvatarFactory.create(
            resources.displayMetrics.density, displayName.firstOrNull() ?: '?', color
        )
        if (!url.isNullOrBlank()) {
            Glide.with(this)
                .load(url)
                .transform(CircleCrop())
                .placeholder(BitmapDrawable(resources, fallback))
                .into(binding.callAvatar)
        } else {
            binding.callAvatar.setImageBitmap(fallback)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        remoteUser = intent.getStringExtra(EXTRA_REMOTE_USER).orEmpty()
        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)
        photoUrl = intent.getStringExtra(EXTRA_REMOTE_PHOTO_URL)
        autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)

        val s = CallManager.snapshot()
        if (isOutgoing && !CallManager.isBusy()) {
            startOutgoingIfPossible()
            return
        }
        if (!isOutgoing && autoAccept) {
            acceptAfterPermission()
            return
        }
        render(s)
        if (s.phase == CallManager.Phase.IDLE || s.phase == CallManager.Phase.ENDED) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        if (CallManager.currentPhase() == CallManager.Phase.ACTIVE) startTimer()
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        stopTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        CallManager.removeListener(this)
        // Deliberately does NOT end the call — pressing Back / Home
        // during a call keeps it alive in CallManager + the foreground
        // service, exactly like WhatsApp.
    }

    companion object {
        const val EXTRA_REMOTE_USER = "remote_user"
        const val EXTRA_IS_OUTGOING = "is_outgoing"
        const val EXTRA_REMOTE_PHOTO_URL = "remote_photo_url"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"
        private const val ENDED_FINISH_DELAY_MS = 900L

        /** Read by the messaging service to suppress a duplicate full-screen notification. */
        @Volatile
        var isForeground = false
    }
}
