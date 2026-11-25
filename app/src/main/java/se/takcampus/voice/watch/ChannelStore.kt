package se.takcampus.voice.watch

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import java.io.File

class PendingVoiceReviewException : IllegalStateException("Review pending mission first")

/** All state is committed together. Staging never changes the accepted mission. */
object ChannelStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "voice-channels.json"))
    private fun read(context: Context): JSONObject {
        val target = file(context)
        return try {
            val stored = target.readFully()
            val bytes = if (stored.firstOrNull() == 1.toByte()) SettingsStore.openChannels(stored) else stored
            try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
        }
        catch (_: java.io.FileNotFoundException) { JSONObject().put("receipts", JSONArray()) }
    }
    private fun write(context: Context, state: JSONObject) {
        val target = file(context)
        val stream = target.startWrite()
        try {
            val bytes = state.toString().toByteArray(Charsets.UTF_8)
            try { stream.write(if (state.optBoolean("sealed")) SettingsStore.sealChannels(bytes) else bytes) }
            finally { bytes.fill(0) }
            target.finishWrite(stream)
        } catch (error: Exception) { target.failWrite(stream); throw error }
    }
    @Synchronized fun stage(context: Context, envelope: String, hostPackage: String? = null, payloadHash: String? = null): String {
        val incoming = VoiceHandoff.decode(envelope)
        val state = read(context)
        val receipts = state.getJSONArray("receipts")
        for (i in 0 until receipts.length()) {
            val previous = receipts.getJSONObject(i)
            if (previous.getString("id") == incoming.transferId) {
                require(previous.getString("hash") == incoming.sha256) { "Conflicting transfer" }
                if (hostPackage != null) {
                    require(previous.optString("hostPackage", hostPackage) == hostPackage) { "Foreign host transfer" }
                    previous.put("hostPackage", hostPackage).put("payloadHash", payloadHash)
                    write(context, state)
                }
                return previous.getString("status")
            }
        }
        if (state.has("pending")) throw PendingVoiceReviewException()
        if (VoicePackageImporter.fromJson(incoming.missionJson).hasLogins()) state.put("sealed", true)
        state.put("pending", envelope)
        val bounded = JSONArray()
        for (i in maxOf(0, receipts.length() - 63) until receipts.length()) bounded.put(receipts.get(i))
        bounded.put(JSONObject().put("id", incoming.transferId).put("hash", incoming.sha256).put("status", "STAGED")
            .apply { if (hostPackage != null) { put("hostPackage", hostPackage); put("payloadHash", payloadHash) } })
        state.put("receipts", bounded)
        write(context, state)
        return "STAGED"
    }
    @Synchronized fun hostReceipts(context: Context, hostPackage: String): List<JSONObject> {
        val receipts = read(context).getJSONArray("receipts")
        return (0 until receipts.length()).map { receipts.getJSONObject(it) }
            .filter { it.optString("hostPackage") == hostPackage && it.optString("payloadHash").matches(Regex("[a-f0-9]{64}")) }
    }
    @Synchronized fun receipt(context: Context, transferId: String): String {
        val receipts = read(context).getJSONArray("receipts")
        for (i in 0 until receipts.length()) if (receipts.getJSONObject(i).getString("id") == transferId)
            return receipts.getJSONObject(i).getString("status")
        return "UNKNOWN"
    }
    @Synchronized fun pending(context: Context): String? = read(context).optString("pending").takeIf { it.isNotEmpty() }
    @Synchronized fun active(context: Context): String? = read(context).optString("active").takeIf { it.isNotEmpty() }
    @Synchronized fun review(context: Context, expectedTransfer: String, accept: Boolean) {
        val state = read(context)
        val incoming = VoiceHandoff.decode(state.getString("pending"))
        require(incoming.transferId == expectedTransfer) { "Pending mission changed" }
        if (accept) {
            VoicePackageImporter.fromJson(incoming.missionJson)
            state.put("active", String(incoming.missionJson, Charsets.UTF_8))
        }
        val receipts = state.getJSONArray("receipts")
        for (i in 0 until receipts.length()) if (receipts.getJSONObject(i).getString("id") == expectedTransfer)
            receipts.getJSONObject(i).put("status", if (accept) "APPLIED" else "REJECTED")
        state.remove("pending")
        write(context, state)
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(2)
        VoiceRuntime.receiptSync?.invoke()
    }
}
