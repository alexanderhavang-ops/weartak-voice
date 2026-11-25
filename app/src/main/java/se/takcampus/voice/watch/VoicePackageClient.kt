package se.takcampus.voice.watch

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.weartak.voice.api.*
import com.weartak.voice.api.VoicePackageContract as C
import se.takcampus.voice.VoiceHandoff
import se.takcampus.voice.VoicePackageImporter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private class VoicePackageReadException(val code: Int) : IllegalStateException("Voice package unavailable")

/** Shared by the real SDK wrapper and the standalone DEV adapter. Intake never starts audio. */
class VoicePackageClient(private val context: Context, private val hostPackage: String,
    private val hostUid: Int, private val control: IVoicePackageControlV1,
    private val sessionLive: () -> Boolean = { true }) {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Voice-package-intake").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    @Volatile var ready = false; private set
    @Volatile private var closed = false
    private val receiver = object : IVoicePackageReceiverV1.Stub() {
        override fun onOffer(offer: Bundle?) {
            check(Binder.getCallingUid() == hostUid && !closed && sessionLive()) { "Foreign voice offer" }
            requireNotNull(offer)
            val copy = Bundle(offer)
            if (!busy.compareAndSet(false, true)) {
                worker.execute { reject(copy, C.BUSY) }; return
            }
            worker.execute { try { receive(copy) } finally { busy.set(false) } }
        }
        override fun onRevoked(handle: String?, reason: Int) {
            check(Binder.getCallingUid() == hostUid && !closed && sessionLive())
            VoiceRuntime.onboardingStatus = "Voice offer expired or revoked"; VoiceRuntime.changed()
        }
    }
    private var enrollment: VoiceEnrollment? = null
    fun start() {
        val caps = control.capabilities
        require(caps.getInt("version") == 1 && caps.getInt("maxZipBytes") in 1..C.MAX_ZIP && caps.getInt("maxJsonBytes") in 1..C.MAX_JSON)
        val result = control.registerReceiver(receiver)
        check(result.getInt("code", -1) == C.OK && result.getInt("version") == 1)
        caps.getBinder("voiceEnrollment")?.let {
            enrollment = VoiceEnrollment(IVoiceEnrollment.Stub.asInterface(it), hostUid)
            VoiceEnrollment.current = enrollment
        }
        ready = true
        VoiceRuntime.receiptSync = { syncReceipts() }
        syncReceipts()
    }
    private fun receive(offer: Bundle) {
        try {
            check(!closed && sessionLive())
            require(offer.getInt("version") == 1)
            val id = requireNotNull(offer.getString("transferId")); require(UUID.fromString(id).toString() == id)
            val handle = requireNotNull(offer.getString("handle")); require(UUID.fromString(handle).toString() == handle)
            val hash = requireNotNull(offer.getString("payloadSha256")); require(hash.matches(Regex("[a-f0-9]{64}")))
            val kind = offer.getInt("payloadKind"); require(kind == C.ZIP || kind == C.JSON)
            val size = offer.getInt("byteCount"); require(size in 1..if (kind == C.ZIP) C.MAX_ZIP else C.MAX_JSON)
            require(offer.getLong("expiresAtElapsedRealtime") > SystemClock.elapsedRealtime())
            val taken = control.takePackage(handle)
            require(taken.getInt("version") == 1)
            if (taken.getInt("code", -1) != C.OK) throw VoicePackageReadException(taken.getInt("code", C.UNAVAILABLE))
            val bytes = requireNotNull(taken.getByteArray("payload"))
            try {
                check(!closed && sessionLive()); require(bytes.size == size && VoiceHandoff.hash(bytes) == hash) { "Payload integrity failure" }
                val json = if (kind == C.ZIP) VoicePackageImporter.jsonFromZip(bytes) else VoiceHandoff.missionFromPayload(bytes)
                val envelope = VoiceHandoff.encode(json, id)
                check(!closed && sessionLive())
                val status = ChannelStore.stage(context, envelope, hostPackage, hash)
                VoiceRuntime.onboardingStatus = "Voice package: $status"
                VoiceRuntime.configRevision += 1; VoiceRuntime.changed()
                if (status == "STAGED") notifyReview()
                runCatching { report(id, hash, VoiceHandoff.hash(json), status) }.onFailure {
                    VoiceRuntime.onboardingStatus = "Voice package: $status; host receipt pending"
                    VoiceRuntime.changed()
                }
            } finally { bytes.fill(0) }
        } catch (failure: Exception) {
            val code = when (failure) {
                is PendingVoiceReviewException -> C.BUSY
                is java.io.IOException -> C.LOCAL_IO
                is SecurityException -> C.UNAUTHORIZED
                is android.os.RemoteException -> C.UNAVAILABLE
                is VoicePackageReadException -> failure.code.takeIf { it in 1..C.LOCAL_IO } ?: C.UNAVAILABLE
                else -> C.INVALID_PACKAGE
            }
            reject(offer, code)
            VoiceRuntime.onboardingStatus = "Voice package rejected; existing channels retained"
            VoiceRuntime.changed()
        }
    }
    private fun notifyReview() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("voice-onboarding", "Voice channel review", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(context, 2,
                android.content.Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(2, android.app.Notification.Builder(context, "voice-onboarding")
                .setSmallIcon(se.takcampus.voice.watch.R.drawable.ic_voice)
                .setContentTitle("Voice channels ready")
                .setContentText("Tap to review the received channel package")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }

    private fun reject(offer: Bundle, error: Int) {
        runCatching { control.reportState(offer.getString("transferId"), offer.getString("payloadSha256"), "", C.FAILED, error) }
    }
    private fun report(id: String, payloadHash: String, missionHash: String, status: String) {
        val state = when (status) { "STAGED" -> C.STAGED; "APPLIED" -> C.APPLIED; "REJECTED" -> C.REJECTED; else -> C.FAILED }
        if (state == C.APPLIED || state == C.REJECTED) {
            val previous = control.getStatus(id)
            if (previous.getInt("state") in setOf(C.DOWNLOADED, C.FAILED, C.EXPIRED)) {
                // Re-establish the durable review receipt after transport loss/rebind, then report the operator's decision.
                control.reportState(id, payloadHash, missionHash, C.STAGED, C.OK)
            }
        }
        val reply = control.reportState(id, payloadHash, missionHash, state, C.OK)
        check(reply.getInt("code", -1) == C.OK)
    }
    fun syncReceipts() {
        if (closed) return
        worker.execute {
            for (receipt in ChannelStore.hostReceipts(context, hostPackage)) {
                if (closed) break
                runCatching { report(receipt.getString("id"), receipt.getString("payloadHash"), receipt.getString("hash"), receipt.getString("status")) }
            }
        }
    }
    fun close() {
        if (closed) return
        if (VoiceEnrollment.current === enrollment) VoiceEnrollment.current = null
        closed = true; ready = false; VoiceRuntime.receiptSync = null
        runCatching { control.unregisterReceiver(receiver) }; worker.shutdownNow()
    }
}
