package se.takcampus.voice.watch

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import java.text.DateFormat
import java.util.Date

/** Main-thread connection journal. PID and time bounds tie OS exit evidence to the previous run. */
internal object VoiceHistory {
    private lateinit var prefs: SharedPreferences
    private var started = 0L
    private var previousExit = ""
    private var history = emptyList<String>()
    var lastDisconnect = ""
        private set

    fun initialize(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("voice_connection_history", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        started = now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val oldPid = prefs.getInt("pid", 0)
        val oldStart = prefs.getLong("processStart", 0)
        val openSince = prefs.getLong("openSince", 0)
        val previous = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, oldPid, 16)
                .firstOrNull { it.processName == Application.getProcessName() &&
                    (oldPid == 0 || it.pid == oldPid) && it.timestamp in oldStart..started }
        }.getOrNull()
        previousExit = when {
            previous != null -> "${stamp(previous.timestamp)} · ${reason(previous.reason, previous.status)}"
            oldPid != 0 -> "Process restarted · Wear OS supplied no matching exit reason"
            else -> "No previous process exit recorded"
        }
        history = prefs.getString("ends", "").orEmpty().lines().filter { it.isNotEmpty() }
        val edit = prefs.edit().putInt("pid", Process.myPid()).putLong("processStart", started)
        if (openSince != 0L) {
            val line = if (previous != null && previous.timestamp >= openSince)
                "${stamp(previous.timestamp)} · App exited during voice: ${reason(previous.reason, previous.status)}"
            else "Session started ${stamp(openSince)} · process restarted before a disconnect was recorded. Cause unknown."
            append(edit, line)
        }
        edit.remove("openSince").commit()
        lastDisconnect = history.firstOrNull().orEmpty()
    }

    fun begin() {
        prefs.edit().putLong("openSince", System.currentTimeMillis()).commit()
    }

    fun end(cause: String) {
        if (prefs.getLong("openSince", 0) == 0L) return
        val edit = prefs.edit().remove("openSince")
        append(edit, "${stamp(System.currentTimeMillis())} · ${cause.lineSequence().first().take(320)}")
        edit.commit()
    }

    private fun append(edit: SharedPreferences.Editor, line: String) {
        history = (listOf(line) + history).take(8)
        lastDisconnect = line
        edit.putString("ends", history.joinToString("\n"))
    }

    fun report(): String = "Current app process started\n${stamp(started)}\n\nPrevious app process\n$previousExit\n\nRecent session endings\n" +
        history.ifEmpty { listOf("No session endings recorded yet") }.joinToString("\n\n")

    private fun stamp(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

    private fun reason(code: Int, status: Int): String = when (code) {
        ApplicationExitInfo.REASON_CRASH -> "App crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native app crash"
        ApplicationExitInfo.REASON_ANR -> "App stopped responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Wear OS stopped the app for low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "Process ended by signal $status"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App process exited (status $status)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User-requested process stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User profile stopped"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "App updated"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "App package state changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Wear OS stopped the app for resource use"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A required process exited"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Process initialization failed"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "App permissions changed"
        ApplicationExitInfo.REASON_FREEZER -> "Wear OS ended a frozen process"
        ApplicationExitInfo.REASON_OTHER -> "Other system-reported exit"
        ApplicationExitInfo.REASON_UNKNOWN -> "Wear OS reports an unknown cause"
        else -> "Wear OS exit code $code"
    }
}
