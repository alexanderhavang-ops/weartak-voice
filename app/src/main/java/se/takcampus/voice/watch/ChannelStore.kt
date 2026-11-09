package se.takcampus.voice.watch

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import java.io.File

/** All state is committed together. Staging never changes the accepted mission. */
object ChannelStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "voice-channels.json"))
    private fun read(context: Context): JSONObject {
        val target = file(context)
        return try { JSONObject(String(target.readFully(), Charsets.UTF_8)) }
        catch (_: java.io.FileNotFoundException) { JSONObject().put("receipts", JSONArray()) }
    }
    private fun write(context: Context, state: JSONObject) {
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) { target.failWrite(stream); throw error }
    }
    @Synchronized fun stage(context: Context, envelope: String): String {
        val incoming = VoiceHandoff.decode(envelope)
        val state = read(context)
        val receipts = state.getJSONArray("receipts")
        for (i in 0 until receipts.length()) {
            val previous = receipts.getJSONObject(i)
            if (previous.getString("id") == incoming.transferId) {
                require(previous.getString("hash") == incoming.sha256) { "Conflicting transfer" }
                return previous.getString("status")
            }
        }
        check(!state.has("pending")) { "Review pending mission first" }
        state.put("pending", envelope)
        val bounded = JSONArray()
        for (i in maxOf(0, receipts.length() - 63) until receipts.length()) bounded.put(receipts.get(i))
        bounded.put(JSONObject().put("id", incoming.transferId).put("hash", incoming.sha256).put("status", "STAGED"))
        state.put("receipts", bounded)
        write(context, state)
        return "STAGED"
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
    }
}
