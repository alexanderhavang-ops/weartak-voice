package se.takcampus.voice.watch

import android.os.SystemClock
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import se.takcampus.voice.MumbleConnection
import java.text.DateFormat
import java.util.Date

/** Main-thread journal. A flow is one TCP attempt, including failures before TLS login. */
internal object SocketHistory {
    private lateinit var prefs: SharedPreferences
    private val flows = mutableListOf<JSONObject>()
    private var current: MumbleConnection? = null

    fun initialize(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("mumble_socket_history", Context.MODE_PRIVATE)
        val stored = runCatching { JSONArray(prefs.getString("flows", "[]")) }.getOrNull() ?: return
        for (i in 0 until minOf(10, stored.length())) stored.optJSONObject(i)?.let { flows.add(it) }
        val first = flows.firstOrNull()
        if (first != null && !first.has("reason")) {
            first.put("reason", "App process ended. Disconnect time unknown.")
            save()
        }
    }

    private var initialSleepOffset = 0L
    private fun sleepOffset() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    fun begin(session: MumbleConnection, host: String, port: Int, phoneCallRequested: Boolean) {
        initialSleepOffset = sleepOffset()
        current = session
        flows.add(0, JSONObject().put("host", host).put("port", port)
            .put("callRequested", phoneCallRequested).put("start", System.currentTimeMillis()))
        while (flows.size > 10) flows.removeAt(flows.lastIndex)
        save()
    }

    private fun sample() {
        val session = current ?: return
        flows.first().apply {
            put("lastSample", System.currentTimeMillis())
            put("cpuSleepMs", (sleepOffset() - initialSleepOffset).coerceAtLeast(0))
            put("pingTicks", JSONArray(session.pingTicks().asList()))
            put("pingWrites", JSONArray(session.pingWrites().asList()))
            put("pingReplies", JSONArray(session.pingReplies().asList()))
            put("connected", session.connectedAt())
            put("local", session.localIp()); put("remote", session.remoteIp())
            put("rx", session.bytesIn()); put("tx", session.bytesOut())
        }
    }

    fun authenticated(effectiveMode: String, standby: String) {
        if (current == null) return
        flows.first().put("callType", effectiveMode).put("standby", standby)
        checkpoint()
    }

    fun checkpoint() {
        if (current == null) return
        sample()
        save()
    }

    fun end(reason: String) {
        if (current == null) return
        sample()
        flows.first().put("end", System.currentTimeMillis()).put("reason", reason)
        current = null
        save()
    }

    private fun save() { prefs.edit().putString("flows", JSONArray(flows).toString()).apply() }

    private fun stamp(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

    private fun pingSummary(flow: JSONObject): String {
        val writes = flow.optJSONArray("pingWrites") ?: return ""
        if (flow.optLong("connected") == 0L) return ""
        fun lastTime(key: String): String {
            val samples = flow.optJSONArray(key) ?: return "None"
            return if (samples.length() == 0) "None" else stamp(samples.getLong(samples.length() - 1))
        }
        // Include the trailing gap even if no further ping was written before disconnect.
        var previous = if (writes.length() < 48) flow.optLong("connected") else writes.getLong(0)
        var longest = 0L
        for (i in 0 until writes.length()) {
            val time = writes.getLong(i)
            longest = maxOf(longest, time - previous)
            previous = time
        }
        longest = maxOf(longest, flow.optLong("lastSample") - previous)
        return "Last ping timer  ${lastTime("pingTicks")}\nLast ping write  ${lastTime("pingWrites")}\nLast ping reply  ${lastTime("pingReplies")}\nLongest recent write gap  ${longest / 1000}s"
    }

    fun report(): String {
        sample()
        if (flows.isEmpty()) return "No Mumble socket attempts recorded yet."
        return flows.joinToString("\n\n") { f ->
            val connected = f.optLong("connected")
            val active = f === flows.firstOrNull() && current != null
            val ended = if (f.has("end")) "Ended  ${stamp(f.getLong("end"))}"
                else if (active) "Current attempt" else "End time unknown"
            val local = f.optString("local").ifEmpty { "Unavailable" }
            val remote = f.optString("remote").takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            listOf(
                "${f.getString("host")}:${f.getInt("port")}$remote",
                "Started  ${stamp(f.getLong("start"))}",
                if (connected > 0) "TCP connected  ${stamp(connected)}" else "TCP connection unconfirmed",
                if (f.has("callRequested")) {
                    val requested = if (f.getBoolean("callRequested")) "On" else "Off"
                    val effective = f.optString("callType", if (active) "Awaiting login" else "Login unconfirmed")
                    "Phone call mode  $requested · $effective"
                } else "Phone call mode  Unrecorded",
                f.optString("standby").takeIf { it.isNotEmpty() }?.let { "Low Power Standby  $it" }.orEmpty(),
                if (f.has("cpuSleepMs")) "CPU sleep during flow  ${f.optLong("cpuSleepMs") / 1000}s" else "",
                if (f.has("cpuTestStart")) "CPU test started  ${stamp(f.getLong("cpuTestStart"))} · ${f.optString("cpuTestEndReason", "3 min maximum")}" else "",
                f.optJSONArray("cpuLockEvents")?.let { events ->
                    (0 until events.length()).joinToString("\n") { i ->
                        val event = events.getJSONObject(i)
                        val state = if (event.getBoolean("enabled")) "enabled" else "disabled"
                        "CPU lock $state observed  ${stamp(event.getLong("observed"))}"
                    }
                }.orEmpty(),
                if (f.has("cpuTestEndObserved")) "App ended test  ${stamp(f.getLong("cpuTestEndObserved"))}" else "",
                pingSummary(f),
                "Local IP  $local",
                "In  ${f.optLong("rx")} B   Out  ${f.optLong("tx")} B",
                ended, f.optString("reason")
            ).filter { it.isNotEmpty() }.joinToString("\n")
        } + "\n\nBytes count complete Mumble frames, including pings and tunneled audio. TLS and network headers are excluded."
    }
}
