package se.takcampus.voice.watch

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.security.MessageDigest
import com.weartak.plugin.sdk.aidl.*
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.voice.api.IVoicePackageControlV1
import com.weartak.voice.api.VoicePackageContract

/** Minimal real SDK lifecycle. No microphone or CoT capabilities are claimed. */
class VoicePluginService : Service() {
    private val hostPackage by lazy { getString(R.string.voice_host_package) }
    private val hostCertificates by lazy { resources.getStringArray(R.array.voice_host_certificates).toSet() }
    @Volatile private var endpoint: Endpoint? = null

    private fun verifyHost(uid: Int): Long {
        val info = packageManager.getPackageInfo(hostPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()
        val digest = signer?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            ?.joinToString("") { "%02X".format(it) }
        if (info.applicationInfo?.uid != uid || packageManager.getPackagesForUid(uid)?.singleOrNull() != hostPackage ||
            digest !in hostCertificates) throw SecurityException("Unapproved WearTAK host")
        return info.lastUpdateTime
    }

    private inner class Endpoint : IWearTakPlugin.Stub() {
        private val lifecycle = com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine().apply {
            dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.BIND_ATTACHED)
        }
        @Volatile private var hostUid = -1
        @Volatile private var hostUpdated = -1L
        @Volatile private var hostBinder: IBinder? = null
        private val death = IBinder.DeathRecipient { retire() }
        private var voiceControl: IVoicePackageControlV1? = null
        private var voiceClient: VoicePackageClient? = null

        private fun live() = endpoint === this && hostBinder?.isBinderAlive == true &&
            runCatching { verifyHost(hostUid) == hostUpdated }.getOrDefault(false)

        private fun authorize() {
            if (Binder.getCallingUid() != hostUid || !live()) throw SecurityException("Inactive host binding")
        }

        // Discovery calls precede initialization. Every SDK transaction still authenticates its caller.
        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                if (endpoint !== this) throw SecurityException("Retired host endpoint")
                val updated = verifyHost(Binder.getCallingUid())
                if (hostBinder != null && (updated != hostUpdated || hostBinder?.isBinderAlive != true)) {
                    retire()
                    throw SecurityException("WearTAK session ended")
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        @Synchronized fun retire() {
            hostBinder?.let { runCatching { it.unlinkToDeath(death, 0) } }
            hostBinder = null
            if (endpoint === this) endpoint = null
            voiceClient?.close()
            voiceClient = null
            voiceControl = null
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.RESET)
        }

        override fun getPluginInfo() = PluginInfoParcel(packageName, "WearTAK Voice", packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), packageName, "Mumble and local VX voice")
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)
        override fun getApiVersion() = 3
        override fun getStatus() = PluginStatusParcel(lifecycle.snapshot(), "Voice channels require wearer review")
        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) = initializePluginV2(init, control, null)
        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?) {
            val uid = Binder.getCallingUid()
            require(init != null && init.pluginIdExpected == packageName && init.hostUid == uid &&
                init.hostPackageName == hostPackage)
            check(endpoint === this && hostBinder == null) { "Host must create a fresh binding" }
            val updated = verifyHost(uid)
            val token = requireNotNull(control).asBinder()
            token.linkToDeath(death, 0)
            hostBinder = token
            hostUid = uid
            hostUpdated = updated
            voiceControl = if (init.hostExtras.getInt(VoicePackageContract.EXTRA_VERSION) == 1)
                IVoicePackageControlV1.Stub.asInterface(init.hostExtras.getBinder(VoicePackageContract.EXTRA_CONTROL)) else null
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.INITIALIZED)
        }
        override fun initializePluginV3(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?, v3: IWearTakPluginHostControlV3?) = initializePluginV2(init, control, v2)
        override fun startPlugin() {
            authorize()
            val control = voiceControl
            if (control != null && voiceClient == null) {
                val client = VoicePackageClient(this@VoicePluginService, hostPackage, hostUid, control, ::live)
                runCatching { client.start() }.onSuccess { voiceClient = client }.onFailure { client.close() }
            }
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STARTED)
        }
        override fun stopPlugin() { authorize(); voiceClient?.close(); voiceClient = null; lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STOPPED) }
        override fun shutdownPlugin() {
            authorize()
            retire()
        }
        override fun registerHostCallback(callback: IWearTakHostCallback?) {
            authorize()
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
        }
        override fun unregisterHostCallback(callback: IWearTakHostCallback?) = authorize()
        override fun getSettingsSchema() = SettingsSchemaParcel(title = "WearTAK Voice").also { authorize() }
        override fun getSettingsValues() = Bundle().also { authorize() }
        override fun applySettings(values: Bundle?) = authorize()
        override fun applySettingsWithResult(values: Bundle?, mode: Int) =
            SettingsApplyResultParcel(success = values == null || values.isEmpty, applyMode = mode).also { authorize() }
        override fun onHostSettingsChanged(settings: HostSettingsParcel?) = authorize()
        override fun getMainUiSchema() = PluginMainUiSchemaParcel().also { authorize() }
        override fun getMainUiState(): Bundle {
            authorize()
            return Bundle().apply {
                // Optional V1 hostExtras extension; absent on stock hosts.
                // Claim delivery only after the authenticated receiver registers.
                putInt("voice_channels_v1", if (voiceClient?.ready == true) 1 else 0)
                putInt("vx_mission_package_v1", 1)
                putInt("ptt_watch_button_v1", 1); putInt("ptt_media_button_v1", 1)
            }
        }
        override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
            authorize()
            error("Voice packages use the negotiated host-owned Binder control")
        }
    }
    override fun onBind(intent: Intent?): IBinder {
        endpoint?.retire()
        return Endpoint().also { endpoint = it }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        endpoint?.retire()
        return false
    }

    override fun onDestroy() {
        endpoint?.retire()
        super.onDestroy()
    }
}
