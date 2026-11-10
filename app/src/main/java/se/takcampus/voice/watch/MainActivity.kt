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
import android.view.MotionEvent
import android.view.View
import android.widget.*
import se.takcampus.voice.MumbleConnection
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

/** Standalone watch UI. PTT is momentary and always released when this screen loses focus. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var speaker: TextView
    private lateinit var talk: Button
    private lateinit var connect: Button
    private lateinit var channels: Spinner
    private var liveChannels = emptyList<MumbleConnection.Channel>()
    private var channelSignature = ""
    private val observer: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed && ::status.isInitialized) updateRuntime() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        getExternalFilesDir("imports")?.mkdirs()
        render()
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("notificationPrompted", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationPrompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        intent.data?.takeIf { it.scheme == "content" }?.let { importUri(it, 1) }
    }
    override fun onResume() { super.onResume(); VoiceRuntime.observers.add(observer); updateRuntime() }
    override fun onPause() { VoiceRuntime.ptt?.invoke(false); super.onPause() }
    override fun onStop() { VoiceRuntime.observers.remove(observer); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) VoiceRuntime.ptt?.invoke(false) }
    private fun message(text: String) = AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    private fun idle(): Boolean {
        if (VoiceRuntime.connection != null || VoiceRuntime.state == MumbleConnection.State.CONNECTING) {
            message("Disconnect before changing configuration."); return false
        }
        return true
    }
    private fun render() {
        VoiceRuntime.ptt?.invoke(false)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 40) }
        fun text(value: String): TextView = TextView(this).apply { text = value; textSize = 16f; column.addView(this) }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { action() }; column.addView(this) }
        text("WearTAK Voice").textSize = 20f
        status = text("Disconnected")
        speaker = text("")
        talk = button("HOLD TO TALK") { }.apply {
            minHeight = 96
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true); VoiceRuntime.ptt?.invoke(true); true }
                    MotionEvent.ACTION_UP -> { VoiceRuntime.ptt?.invoke(false); view.parent.requestDisallowInterceptTouchEvent(false); view.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { VoiceRuntime.ptt?.invoke(false); view.parent.requestDisallowInterceptTouchEvent(false); true }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.x < 0 || event.y < 0 || event.x > view.width || event.y > view.height) VoiceRuntime.ptt?.invoke(false)
                        true
                    }
                    else -> true
                }
            }
        }
        connect = button("Connect") {
            if (VoiceRuntime.connection != null || VoiceRuntime.state == MumbleConnection.State.CONNECTING) VoiceRuntime.stop(this)
            else connectVoice()
        }
        channels = Spinner(this).also { column.addView(it) }
        button("Join selected channel") {
            val channel = liveChannels.getOrNull(channels.selectedItemPosition - 1) ?: return@button
            try {
                // If this ID was supplied in an imported package, verify the saved name before joining.
                val settings = SettingsStore.load(this)
                val mission = ChannelStore.active(this)?.let { VoicePackageImporter.fromJson(it.toByteArray()) }
                val expected = mission?.channels?.find { it.host == settings.host && it.port == settings.port && it.serverChannelId == channel.id }?.name
                VoiceRuntime.connection?.join(channel.id, expected)
            } catch (_: Exception) { message("Channel unavailable or saved channel name no longer matches the server.") }
        }
        button("Server & login") { if (idle()) serverDialog() }
        button("Client certificate / trust") { if (idle()) identityMenu() }
        try {
            ChannelStore.pending(this)?.let { pending ->
                val incoming = VoiceHandoff.decode(pending)
                val mission = VoicePackageImporter.fromJson(incoming.missionJson)
                text("Received: ${mission.name}\n${mission.channels.joinToString { it.name }}")
                button("Accept channels") {
                    if (!idle()) return@button
                    try {
                        ChannelStore.review(this, incoming.transferId, true)
                        val old = SettingsStore.load(this)
                        if (old.host.isBlank()) SettingsStore.save(this, old.copy(host = mission.channels[0].host, port = mission.channels[0].port))
                        render()
                    } catch (_: Exception) { message("Could not save channel configuration.") }
                }
                button("Dismiss package") { ChannelStore.review(this, incoming.transferId, false); render() }
            }
            ChannelStore.active(this)?.let {
                val mission = VoicePackageImporter.fromJson(it.toByteArray())
                text("Saved: ${mission.name}\n${mission.channels.size} channels")
                button("Choose mission server") {
                    if (!idle()) return@button
                    val endpoints = mission.channels.distinctBy { ch -> "${ch.host}:${ch.port}" }
                    AlertDialog.Builder(this).setTitle("Mission server").setItems(endpoints.map { ch -> "${ch.host}:${ch.port}" }.toTypedArray()) { _, which ->
                        try { SettingsStore.save(this, SettingsStore.load(this).copy(host = endpoints[which].host, port = endpoints[which].port)) }
                        catch (_: Exception) { message("Could not save server.") }
                    }.show()
                }
            }
        } catch (_: Exception) { text("Saved channel configuration unavailable") }
        button("Import channel package") { if (idle()) picker("application/zip", 1) }
        text("Standalone alpha · TLS/TCP audio\nNo automatic WearTAK credential access")
        setContentView(ScrollView(this).apply { addView(column) })
        channelSignature = ""; updateRuntime()
    }
    private fun updateRuntime() {
        status.text = getString(se.takcampus.voice.watch.R.string.connection_status, VoiceRuntime.state, VoiceRuntime.detail)
        speaker.text = VoiceRuntime.speaker.takeIf { it.isNotEmpty() }?.let { "Speaking: $it" } ?: ""
        talk.isEnabled = VoiceRuntime.connection?.canTransmit() == true
        talk.text = if (VoiceRuntime.transmitting) "TRANSMITTING — RELEASE" else "HOLD TO TALK"
        talk.setBackgroundColor(if (VoiceRuntime.transmitting) 0xffa32b2b.toInt() else 0xff165969.toInt())
        connect.text = if (VoiceRuntime.connection != null || VoiceRuntime.state == MumbleConnection.State.CONNECTING) "Disconnect" else "Connect"
        val current = VoiceRuntime.connection?.channels() ?: emptyList()
        val signature = current.joinToString { "${it.id}:${it.parent}:${it.name}" }
        if (signature != channelSignature || channels.adapter == null) {
            val selected = liveChannels.getOrNull(channels.selectedItemPosition - 1)?.id
            liveChannels = current; channelSignature = signature
            channels.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("Choose a channel…") + current.map { "${it.name} (#${it.id})" })
            channels.setSelection(current.indexOfFirst { it.id == selected }.let { if (it < 0) 0 else it + 1 })
        }
    }
    private fun connectVoice() {
        try {
            val settings = SettingsStore.load(this)
            if (settings.host.isBlank() || settings.username.isBlank()) { serverDialog(); return }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
            }
            startForegroundService(Intent(this, VoiceService::class.java))
        } catch (_: Exception) { message("Could not start voice. Check permissions and saved settings.") }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) connectVoice()
    }
    // Manual fallback for standalone alpha; phone-assisted provisioning is not available yet.
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
        val user = field("Mumble username / callsign", old.username, InputType.TYPE_CLASS_TEXT)
        val pass = field("Password (optional with certificate)", old.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(this).setTitle("Server & login").setView(ScrollView(this).apply { addView(column) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val settings = old.copy(host = host.text.toString().trim(), port = port.text.toString().toInt(),
                    username = user.text.toString().trim(), password = pass.text.toString())
                require(settings.host.isNotEmpty() && !settings.host.contains('/') && settings.username.isNotEmpty())
                SettingsStore.save(this, settings); pass.setText(""); dialog.dismiss()
            } catch (_: Exception) { Toast.makeText(this, "Check hostname, port and username", Toast.LENGTH_SHORT).show() }
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
                        SettingsStore.save(this, if (which == 2) old.copy(identity = null, identityPassword = "") else old.copy(ca = null))
                    } catch (_: Exception) { message("Could not update credentials.") }
                }
            }.show()
    }
    private fun picker(mime: String, code: Int) {
        val staged = getExternalFilesDir("imports")?.listFiles()?.filter { file ->
            file.isFile && file.length() <= 262144 && when (code) {
                1 -> file.name.endsWith(".zip", true)
                2 -> file.name.endsWith(".p12", true) || file.name.endsWith(".pfx", true)
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
                    2 -> runOnUiThread { identityPassword(bytes, stagedFile) }
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
    private fun identityPassword(bytes: ByteArray, stagedFile: java.io.File?) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; hint = "PKCS#12 password" }
        val dialog = AlertDialog.Builder(this).setTitle("Import client identity").setView(input)
            .setNegativeButton("Cancel") { _, _ -> bytes.fill(0) }.setPositiveButton("Import", null).create()
        dialog.setOnCancelListener { bytes.fill(0) }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text.toString(); input.setText(""); dialog.dismiss()
            Thread {
                try {
                    val settings = SettingsStore.load(this).copy(identity = bytes, identityPassword = password)
                    settings.tls() // Validate password, private key and validity before saving.
                    SettingsStore.save(this, settings)
                    stagedFile?.delete() // Remove the app-owned plaintext staging copy after sealing it.
                    runOnUiThread { message("Client identity saved. Voice login will use it on the next connection.") }
                } catch (_: Exception) { runOnUiThread { message("Identity import failed. Check the password and certificate validity.") } }
                finally { bytes.fill(0) }
            }.start()
        } }
        dialog.show()
    }
}
