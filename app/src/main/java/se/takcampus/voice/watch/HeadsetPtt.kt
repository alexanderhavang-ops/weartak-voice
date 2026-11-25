package se.takcampus.voice.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import se.takcampus.voice.watch.VoiceRuntime.Focus

/** Android media-button PTT. Vendor BLE/SPP protocols require their own device adapter. */
class HeadsetPtt(private val context: Context, private val press: (Boolean) -> Unit, private val cancel: () -> Unit, private val repair: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var mode = "off"
    private var buttons = se.takcampus.voice.HeadsetButton(false)
    private var eventCount = 0
    private var requestedDevice: AudioDeviceInfo? = null
    private var voiceActive = false
    private var registered = false
    private var closed = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(::onFocusChange, main).build()

    private fun onFocusChange(change: Int) {
        if (closed) return
        VoiceRuntime.focus = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> Focus.GRANTED
            AudioManager.AUDIOFOCUS_LOSS -> Focus.LOST
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Focus.INTERRUPTED
            else -> return
        }
        if (!VoiceRuntime.audioFocus) cancel()
        VoiceRuntime.changed()
    }

    fun requestFocus(): Boolean {
        if (closed) return false
        if (VoiceRuntime.audioFocus) return true
        if (VoiceRuntime.focus == Focus.QUEUED) return false
        VoiceRuntime.focus = when (manager.requestAudioFocus(focus)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Focus.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Focus.QUEUED
            else -> Focus.DENIED
        }
        VoiceRuntime.changed()
        return VoiceRuntime.audioFocus
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == requestedDevice?.id }) {
                reset(); cancel()
                VoiceRuntime.audioRoute = "Headset removed — select audio route again"
                VoiceRuntime.detail = "Headset disconnected — PTT stopped"; VoiceRuntime.changed()
            }
        }
    }
    private fun isHeadset(device: AudioDeviceInfo) = device.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    private fun reset() { buttons.reset(); press(false) }
    fun resetInput() { buttons.reset() }
    fun beginVoiceRoute() {
        if (voiceActive || closed) return
        voiceActive = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private var routeCheck: Runnable? = null

    // All route operations run on the main looper. Releasing PTT invalidates a pending check.
    fun openRoute(headsetRequired: Boolean, ready: () -> Unit, failed: (String) -> Unit) {
        routeCheck?.let(main::removeCallbacks)
        var strict = headsetRequired
        val check = object : Runnable {
            var attempts = 0
            var deadline = 0L
            var accepted = false
            var reason = "Headset unavailable"

            override fun run() {
                if (closed || routeCheck !== this) return
                val now = SystemClock.elapsedRealtime()
                try {
                    beginVoiceRoute()
                    if (attempts == 0 || now >= deadline) {
                        // Check for a late confirmation before clearing an accepted request.
                        if (accepted && routeConfirmed()) { complete(); return }
                        if (attempts == 2) { fail(); return }
                        clearDevice()
                        attempts++
                        val device = headsetDevice()
                        if (device == null && !strict) { complete(); return }
                        if (device == null) {
                            accepted = false
                            reason = "No headset voice device available"
                        } else {
                            strict = true
                            requestedDevice = device
                            accepted = manager.setCommunicationDevice(device)
                            reason = if (accepted) "Wear OS did not select ${device.productName}" else "Wear OS declined ${device.productName}"
                        }
                        deadline = now + if (!accepted) 600 else if (attempts == 1) 5_000 else 10_000
                        VoiceRuntime.audioRoute = "Opening headset voice route · attempt $attempts/2"
                        Log.i("WearTAKBuds", "Route request $attempts accepted=$accepted target=${device?.id} active=${activeDevice()?.id}")
                        VoiceRuntime.changed()
                    }
                    if (accepted && routeConfirmed()) complete()
                    else main.postDelayed(this, 100)
                } catch (e: RuntimeException) {
                    reason = "Headset route error (${e.javaClass.simpleName})"
                    fail()
                }
            }

            private fun complete() {
                routeCheck = null
                requestedDevice?.let { VoiceRuntime.audioRoute = "Headset: ${it.productName}" }
                Log.i("WearTAKBuds", "Voice route ready; headsetId=${requestedDevice?.id}")
                ready()
            }

            private fun fail() {
                routeCheck = null
                val current = runCatching { activeDevice()?.productName }.getOrNull() ?: "unknown"
                val message = "$reason · current output: $current"
                Log.w("WearTAKBuds", message)
                failed(message)
            }
        }
        routeCheck = check
        check.run()
    }

    private fun activeDevice() = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice else null
    private fun routeConfirmed() = requestedDevice != null && activeDevice()?.id == requestedDevice?.id
    private fun headsetDevice(): AudioDeviceInfo? {
        if (mode == "off" || Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return activeDevice()?.takeIf(::isHeadset) ?: manager.availableCommunicationDevices.firstOrNull(::isHeadset)
    }

    private fun clearDevice() {
        val previous = requestedDevice
        requestedDevice = null
        if (Build.VERSION.SDK_INT >= 31 && previous != null) manager.clearCommunicationDevice()
    }

    fun endVoiceRoute() {
        routeCheck?.let(main::removeCallbacks)
        routeCheck = null
        if (!voiceActive) return
        voiceActive = false
        runCatching { clearDevice() }.onFailure { Log.w("WearTAKBuds", "Error clearing communication device", it) }
        runCatching { manager.mode = AudioManager.MODE_NORMAL }.onFailure { Log.w("WearTAKBuds", "Error releasing communication mode", it) }
        val fdCount = runCatching { java.io.File("/proc/self/fd").list()?.size }.getOrNull()
        Log.i("WearTAKBuds", "Voice route released; process FDs=$fdCount")
        VoiceRuntime.audioRoute = "Voice audio idle"
        VoiceRuntime.changed()
    }

    fun configure() {
        if (closed) return
        VoiceRuntime.retryBudControls = null
        if (VoiceRuntime.transmitting || VoiceRuntime.repairingBuds) cancel()
        reset(); session?.release(); session = null
        VoiceRuntime.audioRoute = "Voice audio idle"
        if (!registered) { manager.registerAudioDeviceCallback(devices, main); registered = true }
        VoiceRuntime.reclaimFocus = ::requestFocus
        requestFocus()
        mode = context.getSharedPreferences("ptt", Context.MODE_PRIVATE).getString("headset", "off") ?: "off"
        endVoiceRoute()
        buttons = se.takcampus.voice.HeadsetButton(mode == "toggle")
        eventCount = 0
        VoiceRuntime.headsetEvents = if (mode == "off") "" else "Bud input: waiting"
        if (mode == "off") return
        registerSession()
        VoiceRuntime.retryBudControls = repair
    }

    fun registerSession() {
        val old = session
        session = null
        old?.release()
        buttons.reset()
        val fresh = MediaSession(context, "WearTAK Voice PTT")
        try {
            fresh.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION") val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (closed || session !== fresh) return false
                    if (!se.takcampus.voice.HeadsetButton.handles(event.keyCode)) return false
                    val edge = when (event.action) {
                        KeyEvent.ACTION_DOWN -> "down"
                        KeyEvent.ACTION_UP -> "up"
                        else -> return false
                    }
                    if (event.repeatCount == 0) {
                        eventCount++
                        Log.i("WearTAKBuds", "Input $eventCount: ${KeyEvent.keyCodeToString(event.keyCode)} $edge")
                        val key = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").removePrefix("MEDIA_")
                        VoiceRuntime.headsetEvents = "Bud input $eventCount: $key $edge"
                    }
                    buttons.event(event.action, event.repeatCount, event.isCanceled)?.let(press)
                    if (event.isCanceled) cancel()
                    VoiceRuntime.changed()
                    return true
                }
                override fun onStop() { if (session === fresh) { reset(); cancel() } }
                override fun onPause() { if (session === fresh) { reset(); cancel() } }
            }, main)
            fresh.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
            fresh.isActive = true
            session = fresh
        } catch (e: RuntimeException) {
            fresh.release()
            throw e
        }
    }
    fun close() {
        closed = true
        VoiceRuntime.retryBudControls = null
        VoiceRuntime.reclaimFocus = null
        VoiceRuntime.focus = Focus.IDLE
        reset(); session?.release(); session = null
        if (registered) manager.unregisterAudioDeviceCallback(devices)
        manager.abandonAudioFocusRequest(focus)
        endVoiceRoute()
    }
}
