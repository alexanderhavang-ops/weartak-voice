package se.takcampus.voice.watch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import se.takcampus.voice.VoiceHandoff
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Host ingress; the manifest requires the same signature permission as WearTAK's SDK. */
class ChannelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_CANCELED
        setResultExtras(null)
        if (intent.action != VoiceHandoff.ACTION) return
        val envelope = intent.getStringExtra("envelope") ?: return
        if (envelope.length > VoiceHandoff.MAX_ENVELOPE) return
        val result = goAsync()
        try {
            worker.execute {
                try {
                    val handoff = VoiceHandoff.decode(envelope)
                    val status = ChannelStore.stage(context.applicationContext, envelope)
                    result.setResultCode(Activity.RESULT_OK)
                    result.setResultExtras(Bundle().apply {
                        putString("transferId", handoff.transferId)
                        putString("sha256", handoff.sha256)
                        putString("status", status)
                    })
                } catch (_: Exception) { result.setResultCode(Activity.RESULT_CANCELED) }
                finally { result.finish() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { result.finish() }
    }
    companion object {
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4))
    }
}
