package se.takcampus.voice.watch

import android.app.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.media.ToneGenerator
import android.os.SystemClock
import se.takcampus.voice.PttTiming
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import se.takcampus.voice.PttSources
import se.takcampus.voice.MumbleAudioPacket
import se.takcampus.voice.MumbleConnection
import se.takcampus.voice.MulticastVoice
import se.takcampus.voice.VoiceProfile
import se.takcampus.voice.VxWire
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet

object VoiceRuntime {
    @Volatile var connection: MumbleConnection? = null
    @Volatile var reconnecting = false
    @Volatile var serviceRunning = false
    @Volatile var localReady = false
    @Volatile var participants = emptyList<String>()
    fun active() = state in setOf(MumbleConnection.State.CONNECTING, MumbleConnection.State.AUTHENTICATING,
        MumbleConnection.State.CONNECTED, MumbleConnection.State.JOINING, MumbleConnection.State.READY)
    fun canTalk() = localReady || connection?.canTransmit() == true
    @Volatile var state = MumbleConnection.State.CLOSED
    @Volatile var detail = "Disconnected"
    @Volatile var speaker = ""
    @Volatile var transmitting = false
    @Volatile var micReady = false
    enum class Focus { IDLE, GRANTED, INTERRUPTED, QUEUED, LOST, DENIED }
    @Volatile var focus = Focus.IDLE
    val audioFocus get() = focus == Focus.GRANTED
    @Volatile var reclaimFocus: (() -> Boolean)? = null
    @Volatile var retryBudControls: (() -> Unit)? = null
    @Volatile var stopTalking: (() -> Unit)? = null
    @Volatile var repairingBuds = false
    @Volatile var headsetEvents = ""
    @Volatile var networkStatus = ""
    @Volatile var audioRoute = "Watch/default audio"
    @Volatile var configRevision = 0L
    @Volatile var onboardingStatus = ""
    @Volatile var receiptSync: (() -> Unit)? = null
    @Volatile var ptt: ((Boolean) -> Unit)? = null
    @Volatile var input: ((String, Boolean) -> Unit)? = null
    fun press(source: String, down: Boolean) { input?.invoke(source, down) }
    val observers = CopyOnWriteArraySet<() -> Unit>()
    fun changed() { observers.forEach { it() } }
    fun stop(context: Context) { context.startService(Intent(context, VoiceService::class.java).setAction("STOP")) }
}

/** Visible foreground session with explicit stop; never restarts or resumes PTT automatically. */
class VoiceService : Service() {
    companion object {
        const val SESSION_PREFS = "voice_session_options"
        const val PHONE_CALL = "mumble_phone_call"
        private const val BASE_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        fun phoneCallRequested(context: Context) =
            context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).getBoolean(PHONE_CALL, false)
    }

    private lateinit var sessionNotification: Notification
    private var requestPhoneCall = false
    private var callTypeActive = false

    private fun recordLoginMode() {
        var failure: String? = null
        if (requestPhoneCall) {
            try { startForeground(1, sessionNotification, BASE_TYPES or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) }
            catch (error: RuntimeException) { failure = error.javaClass.simpleName }
        }
        callTypeActive = foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL != 0
        val effective = when {
            callTypeActive -> "Active"
            failure != null -> "Inactive ($failure)"
            requestPhoneCall -> "Inactive (type not granted)"
            else -> "Inactive"
        }
        val standby = if (Build.VERSION.SDK_INT < 33) "Unavailable" else runCatching {
            val power = getSystemService(PowerManager::class.java)
            when {
                !power.isLowPowerStandbyEnabled -> "Disabled"
                Build.VERSION.SDK_INT < 34 -> "Enabled"
                power.isAllowedInLowPowerStandby(PowerManager.LOW_POWER_STANDBY_ALLOWED_REASON_ONGOING_CALL) ->
                    "Enabled · ongoing-call exemption allowed"
                else -> "Enabled · ongoing-call exemption unavailable"
            }
        }.getOrDefault("Unknown")
        SocketHistory.authenticated(effective, standby)
    }

    private fun releaseCallType(): Boolean {
        if (!callTypeActive) return true
        try { startForeground(1, sessionNotification, BASE_TYPES) }
        catch (_: RuntimeException) { return false }
        callTypeActive = foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL != 0
        return !callTypeActive
    }

    private val main = Handler(Looper.getMainLooper())
    private var connection: MumbleConnection? = null
    private var local: MulticastVoice? = null
    private var localLock: WifiManager.MulticastLock? = null
    private var localNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var audio: VoiceAudio? = null
    private val pttSources = PttSources()
    private var headset: HeadsetPtt? = null
    private fun cancelPtt(timeout: Boolean = false) {
        if (VoiceRuntime.repairingBuds) finishBudRepair()
        statusTone?.stopTone()
        pttSources.cancel()
        headset?.resetInput()
        pttSources.update("headset", false)
        stopTransmission(timeout)
    }
    private var starting = false
    @Volatile private var generation = 0L
    private var authenticated = false
    private var confirmedRoom: MumbleConnection.Channel? = null
    private var retries = 0
    private var authenticatedAt = 0L
    private var nextAttempt: (() -> Unit)? = null
    private var retryPending = false
    private var networkLost = false
    private var transport = "Unknown"
    private val retry = Runnable {
        if (retryPending && !networkLost && !destroyed && finalMessage == null) {
            retryPending = false
            nextAttempt?.invoke()
        }
    }
    private val checkpoint = object : Runnable {
        override fun run() {
            if (connection == null || destroyed || finalMessage != null) return
            SocketHistory.checkpoint()
            main.postDelayed(this, 30_000)
        }
    }
    @Volatile private var destroyed = false
    private var defaultNetwork: Network? = null
    private var networkRegistered = false
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
    private var activeTiming: PttTiming? = null
    private var statusTone: ToneGenerator? = null
    private var reminderTone: ToneGenerator? = null

    private fun createTone(volume: Int): ToneGenerator? =
        try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume) }
        catch (_: RuntimeException) { null }

    private val checkTransmission = object : Runnable {
        override fun run() {
            val timing = activeTiming ?: return
            val now = SystemClock.elapsedRealtime()
            if (now >= timing.deadline()) {
                cancelPtt(timeout = true)
                VoiceRuntime.detail = if (timing.maximumFirst()) "PTT stopped after 60 seconds" else "PTT stopped after 5 seconds of silence"
                VoiceRuntime.changed()
                return
            }
            if (timing.takeReminder(now)) reminderTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
            main.postDelayed(this, (timing.nextCheck() - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }
    private fun microphoneReady(timing: PttTiming) {
        main.post {
            if (destroyed || activeTiming !== timing) return@post
            val now = SystemClock.elapsedRealtime()
            if (now < timing.deadline()) {
                timing.ready(now)
                VoiceRuntime.micReady = true
                VoiceRuntime.changed()
                statusTone?.startTone(ToneGenerator.TONE_PROP_ACK)
            }
            main.removeCallbacks(checkTransmission)
            checkTransmission.run()
        }
    }
    private var screenOffAt: Long? = null
    private var screenOnAt: Long? = null
    private var screenRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOffAt = now; screenOnAt = null }
                Intent.ACTION_SCREEN_ON -> screenOnAt = now
            }
        }
    }

    private fun sessionEnd(message: String) {
        val power = getSystemService(PowerManager::class.java)
        val now = SystemClock.elapsedRealtime()
        val off = screenOffAt
        val on = screenOnAt
        val display = when {
            !power.isInteractive && off != null && on == null -> "screen inactive for ${(now - off) / 1000}s"
            !power.isInteractive -> "screen inactive"
            on != null && off != null && on >= off -> "awake for ${(now - on) / 1000}s after ${(on - off) / 1000}s inactive"
            else -> "screen active"
        }
        val network = if (lastNetworkChange.isEmpty()) "" else " · $lastNetworkChange ${(now - networkChangeAt) / 1000}s earlier"
        VoiceHistory.end("$message · default $transport · $display · device idle: ${if (power.isDeviceIdleMode) "yes" else "no"}$network")
    }

    private var lastNetworkChange = ""
    private var networkChangeAt = 0L

    private fun noteNetworkChange(event: String) {
        lastNetworkChange = event
        networkChangeAt = SystemClock.elapsedRealtime()
        VoiceRuntime.networkStatus = event
        VoiceRuntime.changed()
    }

    // The default for new sockets can change while the existing TLS stream is still usable.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (destroyed) return
            val old = defaultNetwork
            defaultNetwork = network
            networkLost = false
            if (retryPending && old != network) { main.removeCallbacks(retry); main.post(retry) }
            if (old != null && old != network) noteNetworkChange("Default network switched")
            else if (old == null && lastNetworkChange.isNotEmpty()) noteNetworkChange("Default network became available")
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (destroyed || defaultNetwork != network) return
            transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Other"
            }
            val label = listOf("Default network · $transport", lastNetworkChange).filter { it.isNotBlank() }.joinToString("\n")
            if (VoiceRuntime.networkStatus != label) {
                VoiceRuntime.networkStatus = label
                VoiceRuntime.changed()
            }
        }
        override fun onLost(network: Network) {
            if (!destroyed && defaultNetwork == network) {
                defaultNetwork = null
                networkLost = true
                transport = "unavailable"
                noteNetworkChange("Default network lost")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        VoiceRuntime.serviceRunning = true
        VoiceHistory.initialize(this)
        SocketHistory.initialize(this)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { disconnect("Disconnect requested"); return START_NOT_STICKY }
        if (intent?.action == "PTT_CONFIG") { headset?.configure(); return START_NOT_STICKY }
        if (starting || VoiceRuntime.reconnecting || finalMessage != null || connection != null || local != null) return START_NOT_STICKY
        VoiceRuntime.networkStatus = ""
        VoiceHistory.begin()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenEvents, filter)
        screenRegistered = true
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            failed("Microphone permission is required")
            return START_NOT_STICKY
        }
        starting = true
        main.post(clearSpeaker)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("voice", "Voice connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        try {
            sessionNotification = Notification.Builder(this, "voice").setSmallIcon(se.takcampus.voice.watch.R.drawable.ic_voice)
                .setContentTitle("WearTAK Voice").setContentText("Voice session · tap to open")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
            startForeground(1, sessionNotification, BASE_TYPES)
        } catch (_: RuntimeException) {
            failed("Open Voice and allow microphone access before connecting.")
            return START_NOT_STICKY
        }
        VoiceRuntime.state = MumbleConnection.State.CONNECTING; VoiceRuntime.detail = "Preparing voice connection"; VoiceRuntime.changed()
        val localProfile = try { LocalVoiceSettings.load(this) }
        catch (_: Exception) { failed("Saved local channel is unavailable."); return START_NOT_STICKY }
        if (localProfile != null) {
            try { prepareAudio(); requestWifi(localProfile) }
            catch (_: RuntimeException) { failed("Could not start local voice. Check watch Wi-Fi.") }
            return START_NOT_STICKY
        }
        requestPhoneCall = phoneCallRequested(this)
        Thread {
            try {
                val settings = SettingsStore.load(this)
                val tls = settings.tls()
                main.post {
                    if (destroyed || finalMessage != null) return@post
                    try { prepareAudio() }
                    catch (_: RuntimeException) { failed("Watch audio is unavailable."); return@post }
                    try {
                        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback, main)
                        networkRegistered = true
                    } catch (_: Exception) { /* Socket outcomes still drive recovery. */ }
                    val name = settings.mumbleName(this)
                    nextAttempt = { connectMumble(settings, name, tls) }
                    nextAttempt?.invoke()
                }
            } catch (_: Exception) {
                main.post { failed("Check server settings, identity or CA file") }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun connectMumble(settings: VoiceSettings, name: String, tls: javax.net.ssl.SSLContext) {
        val attempt = ++generation
        lateinit var socket: MumbleConnection
        val listener = object : MumbleConnection.Listener {
            override fun state(state: MumbleConnection.State, detail: String) {
                // Capture membership at the event, before a later channel move or disconnect.
                val room = if (state == MumbleConnection.State.READY)
                    socket.channels().firstOrNull { it.id == socket.joinedChannel() } else null
                main.post {
                    if (attempt != generation || destroyed || finalMessage != null) return@post
                    if (state == MumbleConnection.State.ERROR || state == MumbleConnection.State.CLOSED) {
                        SocketHistory.end(detail)
                        main.removeCallbacks(checkpoint)
                        if (authenticated && socket.isRetryableFailure) {
                            if (releaseCallType()) scheduleReconnect(detail)
                            else failed("Could not release phone call mode after disconnect")
                        } else failed(detail)
                        return@post
                    }
                    val firstLogin = state == MumbleConnection.State.CONNECTED && !loginHandled
                    if (firstLogin) {
                        loginHandled = true
                        loginSinceLastDrop = true
                        authenticated = true
                        authenticatedAt = SystemClock.elapsedRealtime()
                        VoiceRuntime.reconnecting = false
                        recordLoginMode()
                    }
                    if (room != null) confirmedRoom = room
                    VoiceRuntime.state = state
                    VoiceRuntime.detail = detail
                    if (state != MumbleConnection.State.READY || !socket.canTransmit()) cancelPtt()
                    if (firstLogin) {
                        val id = confirmedRoom?.id ?: settings.defaultChannelId
                        val expected = confirmedRoom?.name ?: settings.defaultChannelName.takeIf { it.isNotEmpty() }
                        if (id >= 0) try { socket.join(id, expected) }
                        catch (_: Exception) { VoiceRuntime.detail = "Saved channel changed. Choose a channel." }
                    }
                    VoiceRuntime.changed()
                }
            }
            private var loginHandled = false
            override fun channels(channels: List<MumbleConnection.Channel>) {
                if (!destroyed && attempt == generation) VoiceRuntime.changed()
            }
            override fun voice(session: Int, name: String, packet: MumbleAudioPacket) {
                if (destroyed || attempt != generation) return
                audio?.receive(session, packet)
                val speaking = if (packet.last) "" else name
                speakerAt = System.nanoTime()
                if (VoiceRuntime.speaker != speaking) { VoiceRuntime.speaker = speaking; VoiceRuntime.changed() }
            }
        }
        try {
            socket = MumbleConnection(settings.host, settings.port, name, settings.password, tls, listener)
            connection = socket
            VoiceRuntime.connection = socket
            SocketHistory.begin(socket, settings.host, settings.port, requestPhoneCall)
            main.postDelayed(checkpoint, 30_000)
            socket.start()
        } catch (_: Exception) { failed("Could not start Mumble. Check the server settings.") }
        starting = false
    }

    private fun scheduleReconnect(reason: String) {
        ++generation
        connection = null
        VoiceRuntime.connection = null
        VoiceRuntime.reconnecting = true
        VoiceRuntime.speaker = ""
        VoiceRuntime.state = MumbleConnection.State.CONNECTING
        cancelPtt()
        if (authenticatedAt != 0L && SystemClock.elapsedRealtime() - authenticatedAt >= 30_000) retries = 0
        authenticatedAt = 0L
        val delay = minOf(30, 2 shl minOf(retries, 4))
        retries = minOf(retries + 1, 4)
        if (retries == 1 || loginSinceLastDrop) {
            sessionEnd(reason)
            VoiceHistory.begin()
        }
        loginSinceLastDrop = false
        VoiceRuntime.detail = if (networkLost) "Waiting for network" else "Retry in ${delay}s · $reason"
        VoiceRuntime.changed()
        retryPending = true
        if (!networkLost) main.postDelayed(retry, delay * 1000L)
    }
    private var loginSinceLastDrop = false

    private fun prepareAudio() {
        audio = VoiceAudio(applicationContext) { message ->
            main.post { if (!destroyed) { cancelPtt(); VoiceRuntime.detail = message; VoiceRuntime.changed() } }
        }
        VoiceRuntime.stopTalking = { cancelPtt() }
        VoiceRuntime.input = { source, down ->
            val update = { setPtt(pttSources.update(source, down)) }
            if (Looper.myLooper() == Looper.getMainLooper()) update() else main.post { if (!destroyed) update() }
        }
        VoiceRuntime.ptt = { down -> VoiceRuntime.press("screen", down) }
        // Existing audio-focus owner. Screen PTT works with the default headset binding off.
        headset = HeadsetPtt(this, { down -> VoiceRuntime.press("headset", down) }, { cancelPtt() }, ::retryBuds).also { it.configure() }
    }

    private fun requestWifi(profile: VoiceProfile) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        VoiceRuntime.detail = "Waiting for watch Wi-Fi…"
        VoiceRuntime.changed()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var selected: Network? = null
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val interfaceName = properties.interfaceName ?: return
                if (destroyed || finalMessage != null || selected != null || !properties.linkAddresses.any { it.address is java.net.Inet4Address }) return
                selected = network
                Thread({ joinLocal(profile, interfaceName) }, "watch-voice-multicast").start()
            }
            override fun onLost(network: Network) {
                if (network == selected) failed("Watch Wi-Fi disconnected. Reconnect to resume local voice.")
            }
            override fun onUnavailable() { failed("Connect the watch to Wi-Fi, then retry.") }
        }
        localNetworkCallback = callback
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivity.requestNetwork(request, callback, main, 12_000)
    }

    private fun joinLocal(profile: VoiceProfile, interfaceName: String) {
        var lock: WifiManager.MulticastLock? = null
        var session: MulticastVoice? = null
        try {
            lock = applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("WearTAK Voice")
            lock.setReferenceCounted(false)
            lock.acquire()
            val target = audio ?: error("Voice stopped")
            session = MulticastVoice(profile, LocalVoiceSettings.uid(this), LocalVoiceSettings.callsign(this),
                NetworkInterface.getByName(interfaceName), object : MulticastVoice.Listener {
                    override fun audio(source: String, callsign: String, opus: ByteArray) {
                        target.receiveLocal(source, opus)
                        speakerAt = System.nanoTime()
                        main.post {
                            if (!destroyed && VoiceRuntime.localReady && VoiceRuntime.speaker != callsign) {
                                VoiceRuntime.speaker = callsign
                                VoiceRuntime.changed()
                            }
                        }
                    }
                    override fun participants(callsigns: List<String>) {
                        main.post { if (!destroyed) { VoiceRuntime.participants = callsigns; VoiceRuntime.changed() } }
                    }
                    override fun failure(message: String) { main.post { failed(message) } }
                })
            session.start()
            val started = session
            val acquired = lock
            main.post {
                if (destroyed || finalMessage != null) closeLocal(started, acquired)
                else {
                    local = started
                    localLock = acquired
                    starting = false
                    VoiceRuntime.localReady = true
                    VoiceRuntime.state = MumbleConnection.State.READY
                    VoiceRuntime.detail = profile.label()
                    VoiceRuntime.changed()
                }
            }
        } catch (_: Exception) {
            runCatching { session?.close() }
            runCatching { if (lock?.isHeld == true) lock.release() }
            main.post { failed("Could not join multicast on the watch’s Wi-Fi.") }
        }
    }

    private fun closeLocal(session: MulticastVoice?, lock: WifiManager.MulticastLock?) {
        if (session == null && lock == null) return
        Thread({
            runCatching { session?.close() }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }, "watch-voice-disconnect").start()
    }

    private fun failed(message: String) {
        if (destroyed || finalMessage != null) return
        finalMessage = message
        cancelReconnect(message)
        sessionEnd(message)
        VoiceRuntime.localReady = false
        cancelPtt()
        VoiceRuntime.state = MumbleConnection.State.ERROR
        VoiceRuntime.detail = message
        VoiceRuntime.changed()
        stopSelf()
    }
    private val releaseVoiceRoute = Runnable {
        statusTone?.release(); statusTone = null
        reminderTone?.release(); reminderTone = null
        headset?.endVoiceRoute()
    }

    private val endBudRepair = Runnable {
        finishBudRepair()
        VoiceRuntime.detail = "Headset test tone played · squeeze once and check Bud input"
        VoiceRuntime.changed()
    }

    private fun finishBudRepair() {
        main.removeCallbacks(endBudRepair)
        VoiceRuntime.repairingBuds = false
        releaseVoiceRoute.run()
    }

    private fun retryBuds() {
        val controls = headset ?: return
        if (destroyed || VoiceRuntime.transmitting || VoiceRuntime.repairingBuds || !VoiceRuntime.canTalk() || !controls.requestFocus()) return
        main.removeCallbacks(releaseVoiceRoute)
        releaseVoiceRoute.run()
        VoiceRuntime.repairingBuds = true
        VoiceRuntime.detail = "Testing headset voice route…"
        VoiceRuntime.changed()
        try {
            controls.registerSession()
            controls.openRoute(true, {
                statusTone = createTone(65)
                if (statusTone?.startTone(ToneGenerator.TONE_PROP_PROMPT) == true) main.postDelayed(endBudRepair, 400)
                else captureFailed("Headset selected, but the test tone could not start")
            }, ::captureFailed)
        } catch (_: RuntimeException) { captureFailed("Could not restart bud controls") }
    }

    private fun stopTransmission(timeout: Boolean = false) {
        if (activeTiming == null) return
        activeTiming = null
        main.removeCallbacks(checkTransmission)
        statusTone?.stopTone()
        reminderTone?.stopTone()
        connection?.endTransmission()
        audio?.stopCapture()
        val playClosing = timeout && VoiceRuntime.micReady && VoiceRuntime.audioFocus
        VoiceRuntime.transmitting = false
        VoiceRuntime.micReady = false
        if (playClosing && statusTone?.startTone(ToneGenerator.TONE_PROP_NACK, 250) == true) {
            main.postDelayed(releaseVoiceRoute, 350)
        } else releaseVoiceRoute.run()
        VoiceRuntime.changed()
    }

    private fun setPtt(down: Boolean) {
        if (!down) { stopTransmission(); return }
        if (VoiceRuntime.transmitting) return
        if (!VoiceRuntime.canTalk()) { cancelPtt(); return }
        if (!VoiceRuntime.audioFocus && headset?.requestFocus() != true) {
            cancelPtt()
            return
        }
        if (VoiceRuntime.repairingBuds) finishBudRepair()
        val now = SystemClock.elapsedRealtime()
        val timing = PttTiming(now)
        main.removeCallbacks(releaseVoiceRoute)
        activeTiming = timing
        VoiceRuntime.transmitting = true
        VoiceRuntime.micReady = false
        VoiceRuntime.detail = ""
        try {
            statusTone?.stopTone()
            VoiceRuntime.changed()
            val controls = headset ?: error("No audio controls")
            controls.openRoute(false, { startCaptureWhenRouted(timing) }, ::captureFailed)
        } catch (_: RuntimeException) { captureFailed("Voice audio route unavailable. Release and try again.") }
    }

    private fun captureFailed(message: String) {
        cancelPtt()
        VoiceRuntime.detail = message
        VoiceRuntime.changed()
    }

    private fun startCaptureWhenRouted(timing: PttTiming) {
        if (destroyed || activeTiming !== timing) return
        try {
            val engine = audio ?: error("No audio engine")
            if (statusTone == null) statusTone = createTone(65)
            if (reminderTone == null) reminderTone = createTone(18)
            val ready = { microphoneReady(timing) }
            val transport = local
            if (transport != null && VoiceRuntime.localReady) engine.startCapture(timing, ready, VxWire.SAMPLE_RATE, transport::send)
            else {
                val session = connection ?: error("No connection")
                val token = session.beginTransmission()
                engine.startCapture(timing, ready) { bytes -> session.sendOpus(token, bytes) }
            }
            checkTransmission.run()
        } catch (_: Exception) { captureFailed("Could not start PTT. Release and try again.") }
    }
    private fun disconnect(message: String) {
        if (destroyed || finalMessage != null) return
        finalMessage = message
        cancelReconnect(message)
        sessionEnd(message)
        cancelPtt()
        VoiceRuntime.detail = message
        VoiceRuntime.changed()
        stopSelf()
    }
    private fun cancelReconnect(message: String) {
        ++generation
        retryPending = false
        main.removeCallbacks(retry)
        main.removeCallbacks(checkpoint)
        nextAttempt = null
        VoiceRuntime.reconnecting = false
        SocketHistory.end(message)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { disconnect("Voice task removed from recents") }
    override fun onDestroy() {
        destroyed = true
        cancelReconnect(finalMessage ?: "Service stopped")
        sessionEnd(finalMessage ?: "Voice service stopped without a recorded reason")
        if (screenRegistered) unregisterReceiver(screenEvents)
        main.removeCallbacks(clearSpeaker)
        cancelPtt()
        main.removeCallbacks(releaseVoiceRoute)
        releaseVoiceRoute.run()
        headset?.close()
        headset = null
        val closingConnection = connection
        connection = null
        VoiceRuntime.connection = null
        if (closingConnection != null) {
            // TLS shutdown can write a close alert to the network.
            Thread({ closingConnection.close() }, "watch-mumble-disconnect").start()
        }
        audio?.close()
        audio = null
        closeLocal(local, localLock); local = null; localLock = null
        localNetworkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        VoiceRuntime.localReady = false; VoiceRuntime.participants = emptyList()
        VoiceRuntime.stopTalking = null; VoiceRuntime.headsetEvents = ""
        VoiceRuntime.ptt = null; VoiceRuntime.input = null; VoiceRuntime.speaker = ""
        if (VoiceRuntime.state != MumbleConnection.State.ERROR) {
            VoiceRuntime.state = MumbleConnection.State.CLOSED
            VoiceRuntime.detail = finalMessage ?: "Disconnected"
        }
        VoiceRuntime.serviceRunning = false
        VoiceRuntime.changed()
        if (networkRegistered) try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        super.onDestroy()
    }
}
