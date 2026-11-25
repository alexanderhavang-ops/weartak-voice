package se.takcampus.voice.watch

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import se.takcampus.voice.PttButton
import se.takcampus.voice.MumbleConnection
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import se.takcampus.voice.VoiceProfile
import se.takcampus.voice.WifiVoiceAdvertiser
import se.takcampus.voice.WifiVoiceTransfer
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import se.takcampus.voice.VoiceLogin
import java.util.UUID

/** Standalone watch UI. PTT is momentary and always released when this screen loses focus. */
class MainActivity : Activity() {
    private lateinit var face: TalkFace
    private var localLabel: String? = null
    private var endpoint = ""
    private var pendingSetup = false
    private var diagnostics: AlertDialog? = null
    private var wifiPairingDialog: AlertDialog? = null
    private val pttButton = PttButton()
    private var backToggle = false
    private var resumed = false
    private var backDownTime: Long? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var learningKey = -1
    private var learningButton = false
    private var learnDialog: AlertDialog? = null
    private var setup: AudioOnboarding? = null
    private fun releasePtt() { backDownTime = null; VoiceRuntime.press("back", false); pttButton.reset(); VoiceRuntime.press("watch", false); VoiceRuntime.ptt?.invoke(false) }
    private fun buttonLabel(key: Int): String = when (key) {
        KeyEvent.KEYCODE_BACK -> "Back button"
        KeyEvent.KEYCODE_STEM_1 -> "Side button 1"
        KeyEvent.KEYCODE_STEM_2 -> "Side button 2"
        KeyEvent.KEYCODE_STEM_3 -> "Side button 3"
        else -> "Screen button"
    }
    private fun savedButton(): Int = getPreferences(MODE_PRIVATE).getInt("pttButton", -1)
    private fun assignButton() {
        if (!idle()) return
        val labels = arrayOf("Back action · tap to start/stop", "Hold a side button", "Screen only")
        val selected = if (backToggle) 0 else if (savedButton() >= 0) 1 else 2
        val chooser = AlertDialog.Builder(this).setTitle("Choose watch PTT button")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                dialog.dismiss()
                when (index) {
                    0 -> {
                        saveWatchBinding(-1, true)
                        message("On the ready Voice screen, Back starts or stops PTT. A Back swipe can do this too.")
                    }
                    1 -> learnSideButton()
                    2 -> saveWatchBinding(-1, false)
                }
            }.setNegativeButton("Cancel", null).create()
        // Back is a possible binding here. Only explicit choices close the picker.
        chooser.setCancelable(false)
        chooser.show()
    }
    private fun saveWatchBinding(key: Int, toggle: Boolean) {
        releasePtt()
        backToggle = toggle
        updateBackCallback()
        pttButton.assign(key)
        getPreferences(MODE_PRIVATE).edit().putInt("pttButton", key)
            .putBoolean("backToggle", toggle).apply()
        render()
    }
    private fun learnSideButton() {
        releasePtt()
        learningButton = true
        learningKey = -1
        learnDialog = AlertDialog.Builder(this).setTitle("Hold a side button")
            .setMessage("Hold a side button for one second, then release.")
            .setNegativeButton("Cancel", null).create().also { dialog ->
                dialog.setCancelable(false)
                dialog.setOnDismissListener { learningButton = false }
                dialog.setOnKeyListener { _, key, event -> learnKey(key, event) }
                dialog.show()
            }
    }
    private fun ownsMainBack() = backToggle && resumed

    private fun toggleBackPtt() {
        if (!ownsMainBack() || !hasWindowFocus()) return
        if (VoiceRuntime.transmitting) VoiceRuntime.stopTalking?.invoke()
        else if (!VoiceRuntime.canTalk()) {
            Toast.makeText(this, pttUnavailableReason(), Toast.LENGTH_SHORT).show()
        } else {
            // An automatic cutoff requires a release before this source can start again.
            VoiceRuntime.press("back", false)
            VoiceRuntime.press("back", true)
        }
    }
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ownsMainBack()) {
            if (backCallback == null) {
                val callback = android.window.OnBackInvokedCallback { toggleBackPtt() }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                backCallback = callback
            }
        } else {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }
    @Deprecated("Legacy Back dispatch")
    override fun onBackPressed() {
        if (ownsMainBack()) toggleBackPtt() else super.onBackPressed()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !ownsMainBack()) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backDownTime = event.downTime
            KeyEvent.ACTION_UP -> {
                val paired = backDownTime == event.downTime
                backDownTime = null
                if (paired && !event.isCanceled &&
                    event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
                    toggleBackPtt()
                }
            }
        }
        return true
    }
    private fun learnKey(key: Int, event: KeyEvent): Boolean {
        if (!learningButton) return false
        val name = KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")
        val action = when (event.action) { KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP"; else -> "OTHER" }
        val reading = "$action $name ($key), scan ${event.scanCode}"
        if (!PttButton.assignable(key)) {
            learnDialog?.setMessage("$reading\nVoice received this key. This build does not assign it to PTT.")
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) learningKey = key
                learnDialog?.setMessage("$reading\nKeep holding for one second, then release.")
            }
            KeyEvent.ACTION_UP -> {
                val matched = key == learningKey
                learningKey = -1
                val elapsed = event.eventTime - event.downTime
                if (!matched || event.isCanceled || elapsed < 1000) {
                    learnDialog?.setMessage("$reading · ${elapsed} ms\nHold for one second and release with Voice still open.")
                } else {
                    saveWatchBinding(key, false)
                    learnDialog?.dismiss()
                    message("${buttonLabel(key)} assigned. Test hold and release with Voice open.")
                }
            }
        }
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (learnKey(keyCode, event)) return true
        if (pttButton.handles(keyCode) && hasWindowFocus()) {
            if (pttButton.down(keyCode, event.repeatCount, VoiceRuntime.canTalk())) VoiceRuntime.press("watch", true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (learnKey(keyCode, event)) return true
        if (pttButton.handles(keyCode)) { if (pttButton.up(keyCode)) VoiceRuntime.press("watch", false); return true }
        return super.onKeyUp(keyCode, event)
    }
    private fun listenForSetup() {
        if (!idle()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 12); return
        }
        setup?.close()
        setup = AudioOnboarding(this) { render(); reviewPending() }.also { it.start() }
    }
    private var displayedConfigRevision = -1L
    private val observer: () -> Unit = { runOnUiThread {
        if (!isFinishing && !isDestroyed && ::face.isInitialized) {
            if (displayedConfigRevision != VoiceRuntime.configRevision) render() else updateRuntime()
        }
    } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        VoiceHistory.initialize(this)
        SocketHistory.initialize(this)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        getExternalFilesDir("imports")?.mkdirs()
        backToggle = getPreferences(MODE_PRIVATE).getBoolean("backToggle", false)
        pttButton.assign(savedButton())
        pendingSetup = state?.getBoolean("pendingSetup") == true
        render()
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("notificationPrompted", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationPrompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        val incoming = intent.data ?: if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        incoming?.takeIf { it.scheme == "content" }?.let { importUri(it, 1) }
    }
    override fun onResume() { super.onResume(); resumed = true; updateBackCallback(); VoiceRuntime.observers.add(observer); observer() }
    override fun onPause() { resumed = false; updateBackCallback(); learningKey = -1; releasePtt(); setup?.close(); wifiPairingDialog?.dismiss(); super.onPause() }
    override fun onDestroy() { setup?.close(); super.onDestroy() }
    override fun onStop() { VoiceRuntime.observers.remove(observer); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) releasePtt() }
    private fun message(text: String) = AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    private fun idle(): Boolean {
        if (VoiceRuntime.active()) {
            message("Disconnect before changing configuration."); return false
        }
        return true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pendingSetup", pendingSetup)
    }

    private fun render() {
        displayedConfigRevision = VoiceRuntime.configRevision
        VoiceRuntime.ptt?.invoke(false)
        val local = runCatching { LocalVoiceSettings.load(this) }.getOrNull()
        localLabel = local?.label()
        endpoint = local?.endpoint() ?: runCatching { SettingsStore.load(this) }.getOrNull()
            ?.takeIf { it.host.isNotBlank() }?.let { "${it.host}:${it.port}" }.orEmpty()
        face = TalkFace(this)
        face.gear.setOnClickListener { gearMenu() }
        face.channel.setOnClickListener { showDiagnostics() }
        face.repair.setOnClickListener {
            if (VoiceRuntime.audioFocus) VoiceRuntime.retryBudControls?.invoke() else VoiceRuntime.reclaimFocus?.invoke()
        }
        bindPad()
        setContentView(face)
        updateRuntime()
    }

    private fun bindPad() {
        var touchingPtt = false
        face.pad.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchingPtt = VoiceRuntime.transmitting || VoiceRuntime.canTalk()
                    if (touchingPtt) {
                        view.isPressed = true
                        if (VoiceRuntime.transmitting) VoiceRuntime.stopTalking?.invoke()
                        else VoiceRuntime.ptt?.invoke(true)
                    }
                    touchingPtt
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchingPtt && view.isPressed && (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height)) {
                        view.isPressed = false
                        VoiceRuntime.ptt?.invoke(false)
                    }
                    touchingPtt
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!touchingPtt) false else {
                        view.isPressed = false
                        VoiceRuntime.ptt?.invoke(false)
                        touchingPtt = false
                        true
                    }
                }
                else -> touchingPtt
            }
        }
        // Click also handles keyboard and accessibility activation of the talk pad.
        face.pad.setOnClickListener {
            when {
                VoiceRuntime.transmitting -> VoiceRuntime.stopTalking?.invoke()
                VoiceRuntime.canTalk() -> {
                    VoiceRuntime.press("screen", false)
                    VoiceRuntime.press("screen", true)
                }
                !VoiceRuntime.active() -> connectVoice()
                VoiceRuntime.state == MumbleConnection.State.CONNECTED -> showChannels()
            }
        }
    }

    private fun gearMenu() {
        AlertDialog.Builder(this).setTitle("Settings").setItems(arrayOf("Channels", "Voice setup")) { _, which ->
            when (which) {
                0 -> showChannels()
                1 -> requestSetup()
            }
        }.show()
    }

    private fun requestSetup() {
        if (!VoiceRuntime.serviceRunning && !VoiceRuntime.active()) { setupMenu(); return }
        AlertDialog.Builder(this).setMessage("Disconnect to change voice setup?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect to continue") { _, _ ->
                pendingSetup = true
                if (VoiceRuntime.serviceRunning || VoiceRuntime.active()) VoiceRuntime.stop(this)
                else updateRuntime()
            }.show()
    }

    private fun joinedChannel(): String? {
        if (VoiceRuntime.localReady) return localLabel
        val connection = VoiceRuntime.connection ?: return null
        val id = connection.joinedChannel()
        return if (id < 0) null else connection.channels().find { it.id == id }?.name ?: "Channel #$id"
    }

    private fun showChannels() {
        val connection = VoiceRuntime.connection
        if (connection == null || !VoiceRuntime.active()) {
            if (VoiceRuntime.localReady) { message("Local Wi-Fi channel: ${localLabel.orEmpty()}"); return }
            val choices = mutableListOf<String>()
            if (runCatching { ChannelStore.pending(this) }.getOrNull() != null) choices.add("Review received channels")
            if (runCatching { ChannelStore.active(this) }.getOrNull() != null) choices.add("Choose saved channel")
            if (choices.isEmpty()) { message("Connect to see server channels. Use Voice setup to add a connection."); return }
            AlertDialog.Builder(this).setTitle("Channels").setItems(choices.toTypedArray()) { _, i ->
                if (choices[i] == "Review received channels") reviewPending() else chooseSavedChannel()
            }.setNegativeButton("Close", null).show()
            return
        }
        val rooms = connection.channels()
        if (rooms.isEmpty()) { message("Waiting for server channels."); return }
        var selected = rooms.indexOfFirst { it.id == connection.joinedChannel() }
        fun mayJoin(): Boolean {
            val room = rooms.getOrNull(selected) ?: return false
            return VoiceRuntime.connection === connection && !VoiceRuntime.transmitting &&
                (connection.state() == MumbleConnection.State.CONNECTED ||
                    (connection.state() == MumbleConnection.State.READY && room.id != connection.joinedChannel()))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Channels")
            .setSingleChoiceItems(rooms.map { "${it.name} (#${it.id})" }.toTypedArray(), selected) { dlg, i ->
                selected = i
                (dlg as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = mayJoin()
            }.setNegativeButton("Cancel", null).setPositiveButton("Join") { _, _ ->
                if (mayJoin()) {
                    val room = rooms[selected]
                    try {
                        val settings = SettingsStore.load(this)
                        val mission = ChannelStore.active(this)?.let { VoicePackageImporter.fromJson(it.toByteArray()) }
                        val expected = mission?.channels?.map { it.profile() }?.find {
                            !it.multicast() && it.host() == settings.host && it.port() == settings.port && it.roomId() == room.id
                        }?.roomName()?.takeIf { it.isNotEmpty() }
                        connection.join(room.id, expected ?: room.name)
                    } catch (_: Exception) { message("Channel unavailable or saved channel name no longer matches the server.") }
                }
            }.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = mayJoin()
    }

    private fun pttUnavailableReason(): String = when (VoiceRuntime.state) {
        MumbleConnection.State.CONNECTING, MumbleConnection.State.AUTHENTICATING -> "Waiting for connection"
        MumbleConnection.State.JOINING -> "Waiting for channel confirmation"
        MumbleConnection.State.CONNECTED -> "Choose a channel"
        MumbleConnection.State.READY -> "PTT restricted by server"
        else -> "Connect to talk"
    }

    private fun diagnosticsText(): String {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        return listOf(
            if (localLabel != null) "Local Wi-Fi · $endpoint" else "Server · ${endpoint.ifBlank { "Not configured" }}",
            "Joined channel · ${joinedChannel() ?: "None confirmed"}", VoiceRuntime.detail,
            "Audio focus · ${(VoiceRuntime.focus as Enum<*>).name.lowercase()}", VoiceRuntime.audioRoute,
            VoiceRuntime.networkStatus, VoiceRuntime.headsetEvents, if (backToggle) "Back PTT enabled" else "",
            VoiceRuntime.speaker.takeIf { it.isNotBlank() }?.let { "Speaking · $it" }.orEmpty(),
            VoiceRuntime.participants.joinToString(", "), VoiceRuntime.onboardingStatus,
            "WearTAK Voice $version", VoiceHistory.report()
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun showSocketHistory() {
        var displayed = SocketHistory.report()
        val dialog = AlertDialog.Builder(this).setTitle("Socket history")
            .setMessage(displayed).setPositiveButton("Close", null).show()
        val view = dialog.window?.decorView ?: return
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || isDestroyed) return
                val updated = SocketHistory.report()
                if (updated != displayed) { displayed = updated; dialog.setMessage(updated) }
                view.postDelayed(this, 1_000)
            }
        }
        view.postDelayed(refresh, 1_000)
        dialog.setOnDismissListener { view.removeCallbacks(refresh) }
    }

    private fun showDiagnostics() {
        val builder = AlertDialog.Builder(this).setTitle("Connection details")
            .setMessage(diagnosticsText()).setPositiveButton("Close", null)
            .setNeutralButton("Socket history") { _, _ -> showSocketHistory() }
        if (VoiceRuntime.active()) builder.setNegativeButton("Disconnect") { _, _ -> VoiceRuntime.stop(this) }
        diagnostics = builder.show().also { dialog ->
            dialog.setOnDismissListener { if (diagnostics === dialog) diagnostics = null }
        }
    }

    private fun updateRuntime() {
        val state = VoiceRuntime.state
        val online = VoiceRuntime.localReady || state == MumbleConnection.State.CONNECTED ||
            state == MumbleConnection.State.JOINING || state == MumbleConnection.State.READY
        val active = VoiceRuntime.active()
        val joined = joinedChannel()
        face.status.text = when {
            online -> "Online"
            VoiceRuntime.reconnecting -> "Reconnecting"
            state == MumbleConnection.State.AUTHENTICATING -> "Signing in"
            active -> "Connecting"
            else -> "Offline"
        }
        val channel = if (online) joined ?: "No channel" else "Details"
        face.channel.text = channel
        face.channel.contentDescription = "$channel. Connection details"
        val queued = VoiceRuntime.focus == VoiceRuntime.Focus.QUEUED
        face.repair.visibility = if (online && (VoiceRuntime.retryBudControls != null || !VoiceRuntime.audioFocus)) View.VISIBLE else View.GONE
        face.repair.isEnabled = !VoiceRuntime.transmitting && !queued && !VoiceRuntime.repairingBuds
        face.repair.text = if (VoiceRuntime.repairingBuds) "Testing" else "Repair"
        face.repair.contentDescription = if (VoiceRuntime.audioFocus) "Repair bud controls" else "Reclaim audio focus"
        val onAir = VoiceRuntime.transmitting && VoiceRuntime.micReady
        val canOpenChannels = state == MumbleConnection.State.CONNECTED && !VoiceRuntime.transmitting
        val enabled = !pendingSetup && (!active || VoiceRuntime.transmitting || canOpenChannels || (VoiceRuntime.canTalk() && !queued))
        val padLabel = when {
            pendingSetup -> "Closing…"
            onAir -> "On air"
            VoiceRuntime.transmitting -> "Opening…"
            !active -> "Connect"
            canOpenChannels -> "Channels"
            !online -> "Connecting…"
            else -> "PTT"
        }
        face.pad.text = padLabel
        face.pad.contentDescription = when {
            VoiceRuntime.transmitting -> "$padLabel. Tap to stop"
            VoiceRuntime.canTalk() -> "Push to talk"
            else -> padLabel
        }
        face.pad.isEnabled = enabled
        face.colors(online, onAir, enabled)
        val detail = VoiceRuntime.detail
        val note = when {
            VoiceRuntime.repairingBuds -> "Testing headset…"
            !active -> VoiceHistory.lastDisconnect.substringAfter(" · ").ifEmpty { VoiceRuntime.onboardingStatus }
            VoiceRuntime.speaker.isNotEmpty() -> VoiceRuntime.speaker
            queued || VoiceRuntime.focus == VoiceRuntime.Focus.INTERRUPTED -> "Waiting for audio"
            online && !VoiceRuntime.audioFocus -> "Audio focus lost · tap Repair"
            VoiceRuntime.reconnecting -> detail
            state == MumbleConnection.State.JOINING -> "Joining channel…"
            online && detail.isNotBlank() && detail != joined && detail != localLabel && detail != "Connected — choose a channel" -> detail
            online && !VoiceRuntime.canTalk() -> pttUnavailableReason()
            else -> ""
        }
        face.note.text = note
        face.note.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        diagnostics?.let { it.setMessage(diagnosticsText()); it.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = active }
        if (pendingSetup && !VoiceRuntime.serviceRunning && !active && resumed) {
            pendingSetup = false
            face.post { if (resumed && !isDestroyed && !isFinishing && !VoiceRuntime.active()) setupMenu() }
        }
    }

    private fun setupMenu() {
        val options = mutableListOf("Pair over Wi-Fi", "Listen for setup sound", "Enter channel manually", "Import a VX ZIP",
            "Certificates and trust", "Choose watch PTT button", "Headset PTT",
            "Mumble phone call mode · ${if (VoiceService.phoneCallRequested(this)) "On" else "Off"}")
        if (packageName == "se.takcampus.voice.standalone.dev") options.add("Link to WearTAK Dev")
        options.add("Clear all config")
        AlertDialog.Builder(this).setTitle("Voice setup")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    options.lastIndex -> confirmClearConfig()
                    0 -> pairOverWifi()
                    1 -> listenForSetup()
                    2 -> AlertDialog.Builder(this).setTitle("Connection type")
                        .setItems(arrayOf("Mumble", "Local Wi-Fi multicast")) { _, type ->
                            if (type == 0) serverDialog() else multicastDialog()
                        }.show()
                    3 -> picker("application/zip", 1)
                    4 -> identityMenu()
                    5 -> assignButton()
                    6 -> chooseHeadsetPtt()
                    7 -> AlertDialog.Builder(this).setTitle("Mumble phone call mode")
                        .setSingleChoiceItems(arrayOf("Off", "On"), if (VoiceService.phoneCallRequested(this)) 1 else 0) { dialog, index ->
                            getSharedPreferences(VoiceService.SESSION_PREFS, MODE_PRIVATE).edit()
                                .putBoolean(VoiceService.PHONE_CALL, index == 1).apply()
                            dialog.dismiss()
                        }.setNegativeButton("Cancel", null).show()
                    8 -> try {
                        startActivity(Intent().setComponent(android.content.ComponentName(
                            "com.aitech.weartak_civ.dev", "com.aitech.weartak_civ.debug.VoiceStandaloneConsentActivity")))
                    } catch (_: Exception) { message("Install the matching WearTAK Dev build to link it.") }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun confirmClearConfig() {
        AlertDialog.Builder(this).setTitle("Clear all config?")
            .setMessage("Remove this Voice app’s servers, logins, certificates, callsign, channels, PTT assignments and call-mode setting? Connection history is kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                if (VoiceRuntime.serviceRunning || VoiceRuntime.active()) {
                    message("Disconnect Voice before clearing configuration.")
                    return@setPositiveButton
                }
                setup?.close()
                wifiPairingDialog?.dismiss()
                releasePtt()
                val reset = runCatching { clearVoiceConfig(this, getPreferences(MODE_PRIVATE)) }
                backToggle = getPreferences(MODE_PRIVATE).getBoolean("backToggle", false)
                pttButton.assign(savedButton())
                updateBackCallback()
                VoiceRuntime.onboardingStatus = ""
                VoiceRuntime.configRevision++
                render()
                if (reset.isSuccess) {
                    Toast.makeText(this, "Voice configuration cleared", Toast.LENGTH_SHORT).show()
                    setupMenu()
                } else message("Could not finish clearing Voice configuration. Try Clear all config again.")
            }.show()
    }

    private fun chooseHeadsetPtt() {
        val prefs = getSharedPreferences("ptt", MODE_PRIVATE)
        val modes = listOf("off", "toggle", "hold")
        val labels = arrayOf("Off", "Click to start / stop", "Hold to talk")
        val selected = modes.indexOf(prefs.getString("headset", "off")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Headset PTT")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                prefs.edit().putString("headset", modes[index]).apply()
                dialog.dismiss()
                if (index != 0 && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 13)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pairOverWifi() {
        if (!idle()) return
        if (runCatching { ChannelStore.pending(this) }.getOrNull() != null) {
            reviewPending()
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Pair over Wi-Fi")
            .setMessage("Connecting the watch to Wi-Fi…").setNegativeButton("Cancel", null).create()
        val pairing = WifiVoiceAdvertiser(this, { envelope ->
            try {
                when (ChannelStore.stage(this, envelope)) {
                    "STAGED" -> WifiVoiceTransfer.Receipt.STAGED
                    else -> WifiVoiceTransfer.Receipt.REJECTED
                }
            } catch (_: PendingVoiceReviewException) {
                WifiVoiceTransfer.Receipt.BUSY
            } catch (_: Exception) {
                WifiVoiceTransfer.Receipt.REJECTED
            }
        }, object : WifiVoiceAdvertiser.Listener {
            override fun ready(name: String, code: String) {
                dialog.setMessage("$name\n\n${code.take(4)} ${code.takeLast(4)}\n\nOn the phone, tap Send over Wi-Fi and enter this code.")
            }
            override fun finished(message: String, staged: Boolean) {
                dialog.dismiss()
                if (isFinishing || isDestroyed) return
                if (staged) { render(); reviewPending() } else this@MainActivity.message(message)
            }
        })
        wifiPairingDialog = dialog
        dialog.setOnDismissListener { pairing.close(); wifiPairingDialog = null }
        dialog.show()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SECURE)
        pairing.start()
    }

    private fun reviewPending() {
        if (!idle()) return
        try {
            val incoming = ChannelStore.pending(this)?.let(VoiceHandoff::decode) ?: return
            val mission = VoicePackageImporter.fromJson(incoming.missionJson)
            val entries = mission.channels.map { it.profile() }
            val summary = entries.take(8).joinToString("\n") { "${it.label()} · ${it.endpoint()}" } +
                if (entries.size > 8) "\n+${entries.size - 8} more" else ""
            AlertDialog.Builder(this).setTitle("Review voice channels").setMessage(summary + if (mission.hasLogins()) "\nIncludes Mumble login for the listed server." else "")
                .setNeutralButton("Close", null)
                .setNegativeButton("Reject") { _, _ ->
                    runCatching { ChannelStore.review(this, incoming.transferId, false) }.onFailure { message("Could not reject the package.") }
                    render()
                }.setPositiveButton("Save") { _, _ ->
                    try {
                        ChannelStore.review(this, incoming.transferId, true)
                        VoiceRuntime.onboardingStatus = ""
                        render()
                        if (entries.size == 1) selectChannel(entries.single(), mission.login(entries.single())) else chooseSavedChannel()
                    } catch (_: Exception) { message("Could not save channels.") }
                }.show()
        } catch (_: Exception) { message("The incoming channel package could not be opened.") }
    }

    private fun chooseSavedChannel() {
        if (!idle()) return
        try {
            val mission = ChannelStore.active(this)?.let { VoicePackageImporter.fromJson(it.toByteArray()) } ?: return
            val options = mission.channels.map { it.profile() }
            AlertDialog.Builder(this).setTitle("Choose channel")
                .setItems(options.map { it.label() + "\n" + it.endpoint() }.toTypedArray()) { _, which -> selectChannel(options[which], mission.login(options[which])) }
                .setNegativeButton("Cancel", null).show()
        } catch (_: Exception) { message("Could not open saved channels.") }
    }

    private fun selectChannel(profile: VoiceProfile, login: se.takcampus.voice.VoiceLogin?) {
        try {
            if (profile.multicast()) LocalVoiceSettings.select(this, profile)
            else {
                val old = SettingsStore.load(this)
                val sameServer = profile.host().equals(old.host, true) && profile.port() == old.port
                val saved = if (sameServer) old else VoiceSettings()
                val target = saved.copy(host = profile.host(), port = profile.port(), defaultChannelId = profile.roomId(),
                    defaultChannelName = profile.roomName())
                val account = when (login?.mode) {
                    null -> target
                    VoiceLogin.ENROLL -> {
                        if (login.profileId.isEmpty() || target.enrollmentProfile != login.profileId || target.identity == null ||
                            runCatching { target.tls() }.isFailure) {
                            enrollVoice(target, login.profileId.ifBlank { null })
                            return
                        }
                        target
                    }
                    VoiceLogin.CERT -> target.copy(identity = java.util.Base64.getDecoder().decode(login.identity),
                        identityPassword = login.password, enrollmentProfile = "", username = "", password = "",
                        credentialExpiresAt = 0).also { it.tls() }
                    else -> target.copy(username = login.username, password = login.password, identity = null,
                        identityPassword = "", enrollmentProfile = "", credentialExpiresAt = 0)
                }
                SettingsStore.save(this, account)
                LocalVoiceSettings.select(this, null)
                if (!sameServer && login == null) {
                    render()
                    serverDialog()
                    return
                }
            }
            render()
        } catch (_: Exception) { message("Could not activate the selected channel.") }
    }

    private fun multicastDialog() {
        val current = runCatching { LocalVoiceSettings.load(this) }.getOrNull()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        fun field(parent: LinearLayout, label: String, value: String, type: Int): EditText {
            parent.addView(TextView(this).apply { text = label })
            return EditText(this).apply { inputType = type; isSingleLine = true; setText(value); parent.addView(this) }
        }
        val name = field(form, "Callsign", LocalVoiceSettings.callsign(this), InputType.TYPE_CLASS_TEXT)
        val addressType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val host = field(form, "Address", current?.host() ?: VoiceProfile.DEFAULT_VOICE_GROUP, addressType)
        val port = field(form, "Port", (current?.port() ?: VoiceProfile.DEFAULT_VOICE_PORT).toString(), InputType.TYPE_CLASS_NUMBER)
        form.addView(TextView(this).apply { text = "Protocol" })
        val protocol = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("UDP", "RTP"))
            setSelection(if (current?.mode() == VoiceProfile.Mode.VX_RTP) 1 else 0)
            form.addView(this)
        }
        val data = CheckBox(this).apply { text = "Configure data channel"; isChecked = current?.hasDataChannel() == true; form.addView(this) }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (data.isChecked) View.VISIBLE else View.GONE; form.addView(this) }
        val dataHost = field(details, "Data channel address", current?.dataHost()?.takeIf { it.isNotEmpty() } ?: VoiceProfile.DEFAULT_DATA_GROUP, addressType)
        val dataPort = field(details, "Data channel port", (current?.dataPort()?.takeIf { it > 0 } ?: VoiceProfile.DEFAULT_DATA_PORT).toString(), InputType.TYPE_CLASS_NUMBER)
        data.setOnCheckedChangeListener { _, checked -> details.visibility = if (checked) View.VISIBLE else View.GONE }
        val dialog = AlertDialog.Builder(this).setTitle("Local Wi-Fi voice")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val mode = if (protocol.selectedItemPosition == 0) VoiceProfile.Mode.VX_UDP else VoiceProfile.Mode.VX_RTP
                LocalVoiceSettings.select(this, VoiceProfile(current?.id() ?: UUID.randomUUID(), "Local voice", mode,
                    host.text.toString(), port.text.toString().toInt(), if (data.isChecked) dataHost.text.toString() else "",
                    if (data.isChecked) dataPort.text.toString().toInt() else 0, "", -1, ""), name.text.toString())
                dialog.dismiss()
                render()
            } catch (_: Exception) { message("Check the callsign, multicast addresses and ports.") }
        } }
        dialog.show()
    }
    private fun connectVoice() {
        try {
            val local = LocalVoiceSettings.load(this)
            val settings = if (local == null) SettingsStore.load(this) else VoiceSettings()
            if (local == null && settings.host.isBlank()) { setupMenu(); return }
            if (settings.credentialExpiresAt > 0 && settings.credentialExpiresAt <= System.currentTimeMillis() / 1000) {
                message("Watch login expired. Use Listen for setup and play a fresh audio code from your TAKS page."); return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
            }
            startForegroundService(Intent(this, VoiceService::class.java))
        } catch (_: Exception) { message("Could not start voice. Check permissions and saved settings.") }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) connectVoice()
        if (code == 13 && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("ptt", MODE_PRIVATE).edit().putString("headset", "off").apply()
            message("Headset PTT is off. Allow Nearby devices to use a Bluetooth headset.")
        }
        if (code == 12 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenForSetup()
    }
    private var certificateTarget: VoiceSettings? = null
    private var enrollmentInProgress = false

    private fun enrollVoice(target: VoiceSettings, requested: String? = null) {
        val link = VoiceEnrollment.current
        if (link == null) { message("Open Voice from an enrollment-capable WearTAK host."); return }
        val available = runCatching { link.profiles() }.getOrDefault(emptyList())
        val profiles = available.filter { requested == null || it.first == requested }
        if (profiles.isEmpty()) { message("Set up the selected auto-enrollment profile in WearTAK using Companion."); return }
        fun confirm(profile: Pair<String, String>) {
            AlertDialog.Builder(this).setTitle("Enroll Voice")
                .setMessage("Use ${profile.second} to issue a new Voice certificate for ${target.host}:${target.port}?")
                .setNegativeButton("Cancel", null).setPositiveButton("Enroll") { _, _ ->
                    if (enrollmentInProgress) return@setPositiveButton
                    enrollmentInProgress = true
                    val revision = SettingsStore.revision
                    Toast.makeText(this, "Requesting Voice certificate…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val result = runCatching { link.issue(target, profile.first, LocalVoiceSettings.uid(this)) }
                        runOnUiThread {
                            enrollmentInProgress = false
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (revision != SettingsStore.revision || !idle() || VoiceEnrollment.current !== link) {
                                message("Setup changed during enrollment. Select the profile again."); return@runOnUiThread
                            }
                            result.onSuccess { settings ->
                                runCatching { SettingsStore.save(this, settings); LocalVoiceSettings.select(this, null) }
                                    .onSuccess { render(); message("Voice certificate saved. Tap Connect to join Mumble.") }
                                    .onFailure { message("Could not save Voice certificate.") }
                            }.onFailure { message(it.message ?: "Voice enrollment failed.") }
                        }
                    }.start()
                }.show()
        }
        if (profiles.size == 1) confirm(profiles.single())
        else AlertDialog.Builder(this).setTitle("WearTAK enrollment profile")
            .setItems(profiles.map { it.second }.toTypedArray()) { _, i -> confirm(profiles[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("WearPasswordInput")
    private fun serverDialog() {
        val old = try { SettingsStore.load(this) } catch (_: Exception) { message("Saved credentials could not be decrypted."); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        fun field(label: String, value: String, type: Int): EditText {
            column.addView(TextView(this).apply { text = label })
            return EditText(this).apply { inputType = type; setText(value); isSingleLine = true; column.addView(this) }
        }
        val host = field("Mumble hostname", old.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Port", old.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val pass = field("Server or account password (optional)", old.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val user = field("Username (optional)", old.username, InputType.TYPE_CLASS_TEXT)
        user.hint = "Guest: " + LocalVoiceSettings.mumbleGuestName(this)
        val modes = listOf("Mumble server password", "TAK auto-enrollment", "Import client certificate (.p12)", "Mumble account login")
        val mode = android.widget.Spinner(this)
        mode.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        column.addView(mode, 4)
        val savedMode = when { old.enrollmentProfile.isNotEmpty() -> 1; old.identity != null -> 2; old.username.isNotEmpty() -> 3; else -> 0 }
        mode.setSelection(savedMode)
        var displayedMode = savedMode
        val passLabel = column.getChildAt(column.indexOfChild(pass) - 1) as TextView
        val userLabel = column.getChildAt(column.indexOfChild(user) - 1)
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != displayedMode) {
                    displayedMode = position
                    val restore = position == savedMode && host.text.toString().trim().equals(old.host, true) &&
                        port.text.toString().toIntOrNull() == old.port
                    user.setText(if (restore) old.username else "")
                    pass.setText(if (restore) old.password else "")
                }
                user.visibility = if (position == 3) View.VISIBLE else View.GONE
                userLabel.visibility = user.visibility
                pass.visibility = if (position == 0 || position == 3) View.VISIBLE else View.GONE
                passLabel.visibility = pass.visibility
                passLabel.text = if (position == 3) "Account password" else "Mumble server password (optional)"
            }
        }
        // Keep saved credentials tied to their endpoint. A password entered for a new
        // server may legitimately be identical to the previous server's password.
        var originalEndpoint = true
        val endpointWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val original = host.text.toString().trim().equals(old.host, true) &&
                    port.text.toString().toIntOrNull() == old.port
                if (original == originalEndpoint) return
                originalEndpoint = original
                for ((field, saved) in listOf(user to old.username, pass to old.password)) {
                    if (original && displayedMode == savedMode && field.text.isEmpty()) field.setText(saved)
                    if (!original && field.text.toString() == saved) field.setText("")
                }
            }
        }
        host.addTextChangedListener(endpointWatcher)
        port.addTextChangedListener(endpointWatcher)
        val dialog = AlertDialog.Builder(this).setTitle("Server & login").setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val hostname = host.text.toString().trim()
                val portNumber = port.text.toString().toInt()
                require(hostname.isNotEmpty() && !hostname.contains('/') && portNumber in 1..65535)
                val sameServer = hostname.equals(old.host, true) && portNumber == old.port
                val previous = if (sameServer) old else VoiceSettings()
                val choice = mode.selectedItemPosition
                val username = if (choice == 3) user.text.toString().trim() else ""
                val password = pass.text.toString()
                val destination = previous.copy(host = hostname, port = portNumber)
                if (choice == 1) { dialog.dismiss(); enrollVoice(destination); return@setOnClickListener }
                if (choice == 2) {
                    dialog.dismiss(); certificateTarget = destination
                    picker("application/x-pkcs12", 4); return@setOnClickListener
                }
                if (choice == 3) require(username.isNotBlank() && password.isNotEmpty())
                val settings = destination.copy(identity = null, identityPassword = "", enrollmentProfile = "",
                    username = username, password = password,
                    credentialExpiresAt = if (username == previous.username && password == previous.password)
                        previous.credentialExpiresAt else 0)
                SettingsStore.save(this, settings); LocalVoiceSettings.select(this, null)
                pass.setText(""); dialog.dismiss(); render()
            } catch (_: Exception) { Toast.makeText(this, "Check hostname, port and field lengths", Toast.LENGTH_SHORT).show() }
        } }
        dialog.show()
    }
    private fun identityMenu() {
        AlertDialog.Builder(this).setTitle("Client certificate / trust")
            .setItems(arrayOf("Import client identity (.p12)", "Import trusted private CA", "Remove client identity", "Use public system CAs")) { _, which ->
                when (which) {
                    0 -> picker("*/*", 2)
                    1 -> picker("*/*", 3)
                    else -> try {
                        val old = SettingsStore.load(this)
                        SettingsStore.save(this, if (which == 2) old.copy(identity = null, identityPassword = "", enrollmentProfile = "") else old.copy(ca = null))
                    } catch (_: Exception) { message("Could not update credentials.") }
                }
            }.show()
    }
    private fun picker(mime: String, code: Int) {
        val staged = getExternalFilesDir("imports")?.listFiles()?.filter { file ->
            file.isFile && file.length() <= 262144 && when (code) {
                1 -> file.name.endsWith(".zip", true)
                2, 4 -> file.name.endsWith(".p12", true) || file.name.endsWith(".pfx", true)
                else -> file.name.endsWith(".pem", true) || file.name.endsWith(".crt", true) || file.name.endsWith(".cer", true)
            }
        }?.sortedBy { it.name } ?: emptyList()
        val labels = staged.map { it.name } + "Browse…"
        AlertDialog.Builder(this).setTitle("Import file").setItems(labels.toTypedArray()) { _, which ->
            if (which < staged.size) importUri(android.net.Uri.fromFile(staged[which]), code, staged[which])
            else try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE) }, code)
            } catch (_: android.content.ActivityNotFoundException) {
                message("This watch has no file picker. Copy the file into the app's imports folder with ADB, then open Import again.")
            }
        }.show()
    }
    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) data?.data?.let { importUri(it, requestCode) }
    }
    private fun importUri(uri: android.net.Uri, kind: Int, stagedFile: java.io.File? = null) {
        if (!idle()) return
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { VoicePackageImporter.readBounded(it, if (kind == 1) VoicePackageImporter.MAX_ZIP else 131072) } ?: error("Missing file")
                when (kind) {
                    1 -> {
                        val json = VoicePackageImporter.jsonFromZip(bytes)
                        ChannelStore.stage(this, VoiceHandoff.encode(json, UUID.randomUUID().toString()))
                        runOnUiThread { render() }
                    }
                    2, 4 -> runOnUiThread {
                        val target = if (kind == 4) certificateTarget else SettingsStore.load(this)
                        certificateTarget = null
                        if (target == null) { bytes.fill(0); message("Select the Mumble endpoint again.") }
                        else identityPassword(bytes, stagedFile, target)
                    }
                    3 -> {
                        val certs = CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream())
                        require(certs.isNotEmpty() && certs.size <= 8)
                        certs.forEach { require((it as X509Certificate).basicConstraints >= 0); it.checkValidity() }
                        val summary = certs.joinToString("\n") { (it as X509Certificate).subjectX500Principal.name }
                        runOnUiThread {
                            AlertDialog.Builder(this).setTitle("Trust this CA for voice?").setMessage(summary)
                                .setNegativeButton("Cancel", null).setPositiveButton("Trust") { _, _ ->
                                    try { SettingsStore.save(this, SettingsStore.load(this).copy(ca = bytes)) }
                                    catch (_: Exception) { message("Could not save CA.") }
                                }.show()
                        }
                    }
                }
            } catch (_: Exception) { runOnUiThread { message("File could not be imported. Existing configuration was preserved.") } }
        }.start()
    }
    // PKCS#12 unlocking is optional and the password is never transmitted.
    @SuppressLint("WearPasswordInput")
    private fun identityPassword(bytes: ByteArray, stagedFile: java.io.File?, target: VoiceSettings) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "PKCS#12 password" }
        val dialog = AlertDialog.Builder(this).setTitle("Import client identity").setView(input)
            .setNegativeButton("Cancel") { _, _ -> bytes.fill(0) }.setPositiveButton("Import", null).create()
        dialog.setOnCancelListener { bytes.fill(0) }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text.toString(); input.setText(""); dialog.dismiss()
            Thread {
                try {
                    val settings = target.copy(identity = bytes, identityPassword = password, enrollmentProfile = "",
                        username = "", password = "", credentialExpiresAt = 0)
                    settings.tls() // Validate password, private key and validity before saving.
                    SettingsStore.save(this, settings); LocalVoiceSettings.select(this, null)
                    stagedFile?.delete() // Remove the app-owned plaintext staging copy after sealing it.
                    runOnUiThread { message("Client identity saved. Voice login will use it on the next connection.") }
                } catch (_: Exception) { runOnUiThread { message("Identity import failed. Check the password and certificate validity.") } }
                finally { bytes.fill(0) }
            }.start()
        } }
        dialog.show()
    }
}
