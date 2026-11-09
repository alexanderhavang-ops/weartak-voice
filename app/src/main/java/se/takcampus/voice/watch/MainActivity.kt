package se.takcampus.voice.watch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import java.util.UUID

/** Native review UI. This milestone imports channels; it deliberately exposes no working PTT. */
class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        intent.data?.takeIf { it.scheme == "content" }?.let(::importUri)
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 32) }
        fun text(value: String) { layout.addView(TextView(this).apply { text = value; textSize = 16f }) }
        fun button(label: String, action: () -> Unit) { layout.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        text("WearTAK Voice\nChannel onboarding prototype")
        text("Live audio is not implemented in this build.")
        try {
            val pending = ChannelStore.pending(this)
            if (pending != null) {
                val incoming = VoiceHandoff.decode(pending)
                val mission = VoicePackageImporter.fromJson(incoming.missionJson)
                text("Review: ${mission.name}")
                mission.channels.forEach { text("${it.name}\n${it.host}:${it.port} · ID ${it.serverChannelId}") }
                button("Use these channels") { ChannelStore.review(this, incoming.transferId, true); render() }
                button("Dismiss") { ChannelStore.review(this, incoming.transferId, false); render() }
            } else {
                val active = ChannelStore.active(this)
                if (active == null) text("No channels configured")
                else {
                    val mission = VoicePackageImporter.fromJson(active.toByteArray())
                    text("${mission.name}\n${mission.channels.size} channels saved")
                    mission.channels.forEach { text(it.name) }
                }
                button("Import channel package") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, 1)
                }
            }
        } catch (_: Exception) { text("Channel state unavailable. No changes applied.") }
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let(::importUri)
    }
    private fun importUri(uri: android.net.Uri) {
        Thread {
            try {
                val data = contentResolver.openInputStream(uri)?.use { VoicePackageImporter.readBounded(it, VoicePackageImporter.MAX_ZIP) }
                    ?: error("Missing file")
                val json = VoicePackageImporter.jsonFromZip(data)
                ChannelStore.stage(this, VoiceHandoff.encode(json, UUID.randomUUID().toString()))
                runOnUiThread { render() }
            } catch (_: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setMessage("Package could not be imported. No channels changed.").setPositiveButton("OK", null).show() }
            }
        }.start()
    }
}
