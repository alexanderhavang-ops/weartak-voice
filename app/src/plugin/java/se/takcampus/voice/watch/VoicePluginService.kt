package se.takcampus.voice.watch

import android.app.Service
import android.content.Intent
import android.os.Bundle
import com.weartak.plugin.sdk.aidl.*
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.voice.api.IVoicePackageControlV1
import com.weartak.voice.api.VoicePackageContract

/** Minimal real SDK lifecycle. No microphone or CoT capabilities are claimed. */
class VoicePluginService : Service() {
    @Volatile private var hostUid = -1
    private var hostPackage = ""
    private var voiceControl: IVoicePackageControlV1? = null
    @Volatile
    private var voiceClient: VoicePackageClient? = null
    private fun authorize() { check(hostUid >= 0 && android.os.Binder.getCallingUid() == hostUid) { "Inactive host binding" } }
    private val lifecycle = com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine()
    private val binder = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(packageName, "WearTAK Voice", packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), packageName, "Mumble and local VX voice")
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)
        override fun getApiVersion() = 3
        override fun getStatus() = PluginStatusParcel(lifecycle.snapshot(), "Voice channels require wearer review")
        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) = initializePluginV2(init, control, null)
        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?) {
            require(init != null && init.pluginIdExpected == packageName && init.hostUid == android.os.Binder.getCallingUid())
            if (packageName == "se.takcampus.voice.watch.dev") {
                require(init.hostPackageName == "com.aitech.weartak_civ.dev") { "Unexpected development host" }
            }
            val packages = packageManager.getPackagesForUid(init.hostUid).orEmpty()
            require(init.hostPackageName in packages)
            hostUid = init.hostUid
            hostPackage = init.hostPackageName
            voiceClient?.close(); voiceClient = null
            voiceControl = if (init.hostExtras.getInt(VoicePackageContract.EXTRA_VERSION) == 1)
                IVoicePackageControlV1.Stub.asInterface(init.hostExtras.getBinder(VoicePackageContract.EXTRA_CONTROL)) else null
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.INITIALIZED)
        }
        override fun initializePluginV3(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?, v3: IWearTakPluginHostControlV3?) = initializePluginV2(init, control, v2)
        override fun startPlugin() {
            authorize()
            val control = voiceControl
            if (control != null && voiceClient == null) {
                val client = VoicePackageClient(this@VoicePluginService, hostPackage, hostUid, control)
                runCatching { client.start() }.onSuccess { voiceClient = client }.onFailure { client.close() }
            }
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STARTED)
        }
        override fun stopPlugin() { authorize(); voiceClient?.close(); voiceClient = null; lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STOPPED) }
        override fun shutdownPlugin() {
            authorize()
            voiceClient?.close(); voiceClient = null; voiceControl = null
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_SHUTDOWN)
        }
        override fun registerHostCallback(callback: IWearTakHostCallback?) { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_CALLBACK_REGISTERED) }
        override fun unregisterHostCallback(callback: IWearTakHostCallback?) {}
        override fun getSettingsSchema() = SettingsSchemaParcel(title = "WearTAK Voice")
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) {}
        override fun applySettingsWithResult(values: Bundle?, mode: Int) = SettingsApplyResultParcel(success = values == null || values.isEmpty, applyMode = mode)
        override fun onHostSettingsChanged(settings: HostSettingsParcel?) {}
        override fun getMainUiSchema() = PluginMainUiSchemaParcel()
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
    override fun onBind(intent: Intent?) = binder.also { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.BIND_ATTACHED) }
    override fun onUnbind(intent: Intent?): Boolean {
        voiceClient?.close(); voiceClient = null; voiceControl = null; hostUid = -1
        lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.RESET)
        return false
    }
}
