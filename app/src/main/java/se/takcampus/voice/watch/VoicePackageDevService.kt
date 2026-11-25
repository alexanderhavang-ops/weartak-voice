package se.takcampus.voice.watch

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import com.weartak.voice.api.*

/** Exported only by standaloneDebug, protected by an isolated Android signature permission. */
class VoicePackageDevService : Service() {
    private var hostUid = -1
    private var client: VoicePackageClient? = null
    private fun authorize() { check(hostUid >= 0 && Binder.getCallingUid() == hostUid) }
    private val binder = object : IVoicePackageDevGatewayV1.Stub() {
        override fun initialize(hostPackage: String?, uid: Int, control: IVoicePackageControlV1?) {
            requireNotNull(hostPackage)
            require(hostPackage == "com.aitech.weartak_civ.dev" && uid == Binder.getCallingUid())
            require(packageManager.getPackagesForUid(uid).orEmpty().contains(hostPackage))
            require(packageManager.checkSignatures(hostPackage, packageName) == PackageManager.SIGNATURE_MATCH)
            client?.close(); hostUid = uid
            client = VoicePackageClient(this@VoicePackageDevService, hostPackage, uid, requireNotNull(control)).also { it.start() }
        }
        override fun getState(): Bundle { authorize(); return Bundle().apply { putBoolean("ready", client?.ready == true) } }
        override fun shutdown() { authorize(); client?.close(); client = null; hostUid = -1 }
    }
    override fun onBind(intent: Intent?) = binder
    override fun onUnbind(intent: Intent?): Boolean { client?.close(); client = null; hostUid = -1; return false }
    override fun onDestroy() { client?.close(); super.onDestroy() }
}
