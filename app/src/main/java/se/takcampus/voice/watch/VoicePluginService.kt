package se.takcampus.voice.watch

import android.app.Service
import android.content.Intent
import android.os.Bundle
import com.weartak.plugin.sdk.aidl.*
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

/** Minimal real SDK lifecycle. No microphone or CoT capabilities are claimed. */
class VoicePluginService : Service() {
    private val lifecycle = com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine()
    private val binder = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(packageName, "WearTAK Voice", "0.1.0", packageName, "Channel onboarding prototype; audio pending")
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)
        override fun getApiVersion() = 3
        override fun getStatus() = PluginStatusParcel(lifecycle.snapshot(), "Channel onboarding only; audio pending")
        override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) = initializePluginV2(init, control, null)
        override fun initializePluginV2(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?) {
            require(init != null && init.pluginIdExpected == packageName && init.hostUid == android.os.Binder.getCallingUid())
            lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.INITIALIZED)
        }
        override fun initializePluginV3(init: PluginInitParcel?, control: IWearTakPluginControl?, v2: IWearTakPluginHostControlV2?, v3: IWearTakPluginHostControlV3?) = initializePluginV2(init, control, v2)
        override fun startPlugin() { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STARTED) }
        override fun stopPlugin() { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_STOPPED) }
        override fun shutdownPlugin() { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_SHUTDOWN) }
        override fun registerHostCallback(callback: IWearTakHostCallback?) { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.HOST_CALLBACK_REGISTERED) }
        override fun unregisterHostCallback(callback: IWearTakHostCallback?) {}
        override fun getSettingsSchema() = SettingsSchemaParcel(title = "WearTAK Voice")
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) {}
        override fun applySettingsWithResult(values: Bundle?, mode: Int) = SettingsApplyResultParcel(success = values == null || values.isEmpty, applyMode = mode)
        override fun onHostSettingsChanged(settings: HostSettingsParcel?) {}
        override fun getMainUiSchema() = PluginMainUiSchemaParcel()
        override fun getMainUiState() = Bundle()
        override fun invokeMainUiAction(actionId: String?, args: Bundle?) {}
    }
    override fun onBind(intent: Intent?) = binder.also { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.BIND_ATTACHED) }
    override fun onUnbind(intent: Intent?): Boolean { lifecycle.dispatch(com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent.RESET); return false }
}
