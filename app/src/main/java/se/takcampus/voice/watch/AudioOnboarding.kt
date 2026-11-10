package se.takcampus.voice.watch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import se.takcampus.voice.AudioLinkCodec
import se.takcampus.voice.OnboardingLink
import se.takcampus.voice.VoicePackageImporter
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Foreground-only acoustic link receiver. Network credentials are fetched over verified HTTPS. */
class AudioOnboarding(private val activity: Activity, private val accepted: () -> Unit) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var dialog: AlertDialog? = null
    fun start() {
        check(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val text = TextView(activity).apply { text = activity.getString(R.string.setup_listening); setPadding(20, 12, 20, 12) }
        dialog = AlertDialog.Builder(activity).setTitle("Listen for setup").setView(text).setNegativeButton("Cancel", null).create().also {
            it.setOnDismissListener { close() }; it.show(); it.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Thread({
            var found: String? = null
            try {
                val decoder = AudioLinkCodec.Decoder { link -> found = link }
                val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0)
                check(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min, 8192)).build()
                recorder = input
                try {
                    check(input.state == AudioRecord.STATE_INITIALIZED)
                    if (stopped.get()) return@Thread
                    input.startRecording()
                    val pcm = ShortArray(2048); val deadline = System.nanoTime() + 60_000_000_000L
                    while (!stopped.get() && found == null && System.nanoTime() < deadline) {
                        val n = input.read(pcm, 0, pcm.size)
                        check(n > 0); decoder.feed(pcm, n)
                    }
                } finally {
                    recorder = null
                    try { input.stop() } catch (_: Exception) {}
                    input.release()
                }
                if (!stopped.get()) activity.runOnUiThread {
                    if (stopped.get() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    dialog?.dismiss()
                    if (found == null) message("No setup sound received. Move the phone closer, raise media volume and try again.")
                    else try { confirm(OnboardingLink.parse(found!!)) } catch (_: Exception) { message("That sound did not contain a supported HTTPS setup link.") }
                }
            } catch (_: Exception) {
                if (!stopped.get()) activity.runOnUiThread { dialog?.dismiss(); message("Could not listen. Check microphone permission and try again.") }
            }
        }, "voice-setup-listener").start()
    }
    private fun confirm(link: OnboardingLink) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle("Get voice setup?")
            .setMessage("Download your voice settings from\n${link.origin.host}\n\nContinue only if this is the TAKS server shown on your phone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> download(link) }.show()
    }
    private fun download(link: OnboardingLink) {
        val progress = AlertDialog.Builder(activity).setMessage("Getting voice setup…").setCancelable(false).show()
        Thread {
            try {
                val connection = link.redeemUrl().toURL().openConnection() as HttpsURLConnection
                val payload: ByteArray
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10000; connection.readTimeout = 10000
                    connection.requestMethod = "POST"; connection.doOutput = true; connection.useCaches = false
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(JSONObject().put("ticket", link.ticket).toString().toByteArray()) }
                    check(connection.responseCode == 200)
                    payload = connection.inputStream.use { VoicePackageImporter.readBounded(it, 32768) }
                } finally { connection.disconnect() }
                val config = try { parse(JSONObject(String(payload, Charsets.UTF_8)), link) } finally { payload.fill(0) }
                activity.runOnUiThread {
                    progress.dismiss()
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(activity).setTitle("Use this voice setup?")
                        .setMessage("${config.displayName}\n${config.host}:${config.port}\n\n${config.setupChannels}\n\nThis replaces your saved voice login and uses public TLS trust.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Accept") { _, _ ->
                            try { SettingsStore.save(activity, config); accepted() }
                            catch (_: Exception) { message("Could not save setup. Existing settings were preserved.") }
                        }.show()
                }
            } catch (_: Exception) { activity.runOnUiThread { progress.dismiss(); message("Setup could not be downloaded. The link may have expired or already been used. Play a fresh audio code on the phone.") } }
        }.start()
    }
    private fun parse(obj: JSONObject, link: OnboardingLink): VoiceSettings {
        require(obj.getInt("schema") == 1)
        val host = obj.getString("host")
        // Initial TAKS contract intentionally provisions the same origin's voice server.
        require(host.equals(link.origin.host, true))
        val username = obj.getString("username"); val password = obj.getString("password")
        require(username.matches(Regex("TW\\.[0-9a-f]{16}")) && password.matches(Regex("[A-Za-z0-9_-]{43}")))
        val port = obj.getInt("port"); require(port in 1..65535)
        val expires = obj.getLong("credentialExpiresAt"); require(expires > System.currentTimeMillis() / 1000)
        val channels = obj.getJSONArray("channels"); require(channels.length() in 1..128)
        val names = mutableListOf<String>()
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            require(channel.getInt("id") >= 0)
            val name = channel.getString("name"); require(name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() }); names.add(name)
        }
        val display = obj.getString("displayName"); require(display.length <= 128 && display.none { it.isISOControl() })
        return VoiceSettings(host, port, username, password, displayName = display, setupChannels = names.joinToString("\n"),
            defaultChannelId = channels.getJSONObject(0).getInt("id"), defaultChannelName = names.first(), credentialExpiresAt = expires)
    }
    private fun message(value: String) { if (!activity.isFinishing && !activity.isDestroyed) AlertDialog.Builder(activity).setMessage(value).setPositiveButton("OK", null).show() }
    override fun close() {
        stopped.set(true)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) dialog?.dismiss()
        try { recorder?.stop() } catch (_: Exception) {}
    }
}
