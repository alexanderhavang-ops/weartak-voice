package se.takcampus.voice.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import se.takcampus.voice.MumbleAudioPacket
import se.takcampus.voice.MumbleConnection
import java.util.concurrent.CopyOnWriteArraySet

object VoiceRuntime {
    @Volatile var connection: MumbleConnection? = null
    @Volatile var state = MumbleConnection.State.CLOSED
    @Volatile var detail = "Disconnected"
    @Volatile var speaker = ""
    @Volatile var transmitting = false
    @Volatile var ptt: ((Boolean) -> Unit)? = null
    val observers = CopyOnWriteArraySet<() -> Unit>()
    fun changed() { observers.forEach { it() } }
    fun stop(context: Context) { context.startService(Intent(context, VoiceService::class.java).setAction("STOP")) }
}

/** Visible foreground session with explicit stop; never restarts or resumes PTT automatically. */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var connection: MumbleConnection? = null
    private var audio: VoiceAudio? = null
    private var starting = false
    private var destroyed = false
    private var defaultNetwork: Network? = null
    private var networkRegistered = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var ownsAudioMode = false
    private var finalMessage: String? = null
    @Volatile private var speakerAt = 0L
    private val clearSpeaker = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (VoiceRuntime.speaker.isNotEmpty() && System.nanoTime() - speakerAt > 1_500_000_000L) {
                VoiceRuntime.speaker = ""; VoiceRuntime.changed()
            }
            main.postDelayed(this, 1000)
        }
    }
    private val limit = Runnable { setPtt(false); VoiceRuntime.detail = "PTT stopped after 60 seconds"; VoiceRuntime.changed() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val old = defaultNetwork; defaultNetwork = network
            if (old != null && old != network) main.post { disconnect("Network changed — reconnect") }
        }
        override fun onLost(network: Network) {
            if (defaultNetwork == network) main.post { disconnect("Network lost — reconnect") }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (starting || connection != null) return START_NOT_STICKY
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoiceRuntime.state = MumbleConnection.State.ERROR; VoiceRuntime.detail = "Microphone permission is required"; VoiceRuntime.changed()
            stopSelf(); return START_NOT_STICKY
        }
        starting = true
        main.post(clearSpeaker)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("voice", "Voice connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "voice").setSmallIcon(se.takcampus.voice.watch.R.drawable.ic_voice)
            .setContentTitle("WearTAK Voice").setContentText("Mumble connection active · tap to open")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build())
        VoiceRuntime.state = MumbleConnection.State.CONNECTING; VoiceRuntime.detail = "Preparing voice connection"; VoiceRuntime.changed()
        Thread {
            try {
                val settings = SettingsStore.load(this)
                val tls = settings.tls()
                main.post {
                    if (destroyed) return@post
                    val manager = getSystemService(AudioManager::class.java)
                    previousAudioMode = manager.mode; manager.mode = AudioManager.MODE_IN_COMMUNICATION; ownsAudioMode = true
                    audio = VoiceAudio(applicationContext) { message -> main.post { setPtt(false); VoiceRuntime.detail = message; VoiceRuntime.changed() } }
                    var attemptedDefault = false
                    val listener = object : MumbleConnection.Listener {
                        override fun state(state: MumbleConnection.State, detail: String) {
                            main.post {
                                if (destroyed) return@post
                                VoiceRuntime.state = state; VoiceRuntime.detail = finalMessage ?: detail
                                if (state != MumbleConnection.State.READY || connection?.canTransmit() != true) setPtt(false)
                                VoiceRuntime.changed()
                                if (state == MumbleConnection.State.CONNECTED && settings.defaultChannelId >= 0 && !attemptedDefault) {
                                    attemptedDefault = true
                                    try { connection?.join(settings.defaultChannelId, settings.defaultChannelName) }
                                    catch (_: Exception) { VoiceRuntime.detail = "Saved channel changed. Choose a channel."; VoiceRuntime.changed() }
                                }
                                if (state == MumbleConnection.State.ERROR || state == MumbleConnection.State.CLOSED) stopSelf()
                            }
                        }
                        override fun channels(channels: List<MumbleConnection.Channel>) { VoiceRuntime.changed() }
                        override fun voice(session: Int, name: String, packet: MumbleAudioPacket) {
                            if (destroyed) return
                            audio?.receive(session, packet)
                            val speaking = if (packet.last) "" else name
                            speakerAt = System.nanoTime()
                            if (VoiceRuntime.speaker != speaking) { VoiceRuntime.speaker = speaking; VoiceRuntime.changed() }
                        }
                    }
                    connection = MumbleConnection(settings.host, settings.port, settings.username, settings.password, tls, listener)
                    VoiceRuntime.connection = connection
                    VoiceRuntime.ptt = { down -> if (Looper.myLooper() == Looper.getMainLooper()) setPtt(down) else main.post { setPtt(down) } }
                    try {
                        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
                        networkRegistered = true
                    } catch (_: Exception) { /* Socket errors still stop the session. */ }
                    connection!!.start(); starting = false
                }
            } catch (_: Exception) {
                main.post { if (!destroyed) { VoiceRuntime.state = MumbleConnection.State.ERROR; VoiceRuntime.detail = "Check server settings, identity or CA file"; VoiceRuntime.changed(); stopSelf() } }
            }
        }.start()
        return START_NOT_STICKY
    }
    private fun setPtt(down: Boolean) {
        main.removeCallbacks(limit)
        if (!down) {
            connection?.endTransmission(); audio?.stopCapture()
            VoiceRuntime.transmitting = false; VoiceRuntime.changed(); return
        }
        if (VoiceRuntime.transmitting) { main.postDelayed(limit, 60000); return }
        try {
            val session = connection ?: error("No connection")
            val token = session.beginTransmission()
            audio?.startCapture { bytes -> session.sendOpus(token, bytes) } ?: error("No audio engine")
            VoiceRuntime.transmitting = true; VoiceRuntime.changed(); main.postDelayed(limit, 60000)
        } catch (_: Exception) { setPtt(false); VoiceRuntime.detail = "Select a joined channel before talking"; VoiceRuntime.changed() }
    }
    private fun disconnect(message: String) {
        finalMessage = message
        setPtt(false); connection?.close(); VoiceRuntime.detail = message; VoiceRuntime.changed(); stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(clearSpeaker)
        setPtt(false); connection?.close(); connection = null; audio?.close(); audio = null
        VoiceRuntime.connection = null; VoiceRuntime.ptt = null; VoiceRuntime.speaker = ""
        if (VoiceRuntime.state != MumbleConnection.State.ERROR) VoiceRuntime.state = MumbleConnection.State.CLOSED
        VoiceRuntime.changed()
        if (networkRegistered) try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        if (ownsAudioMode) try { getSystemService(AudioManager::class.java).mode = previousAudioMode } catch (_: Exception) {}
        super.onDestroy()
    }
}
