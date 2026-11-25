package se.takcampus.voice.watch

import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoicePluginServiceTest {
    private lateinit var service: VoicePluginService
    private lateinit var remote: IWearTakPlugin
    @Before fun create() {
        RuntimeEnvironment.getApplication().deleteFile("voice-channels.json")
        service = Robolectric.buildService(VoicePluginService::class.java).create().get()
        remote = IWearTakPlugin.Stub.asInterface(service.onBind(Intent()))
    }
    private fun initialize() {
        val host = "com.aitech.weartak_civ.dev"
        val signer = javaClass.getResourceAsStream("/weartak-dev-public.pem")!!.use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val signatures = android.content.pm.SigningInfo()
        org.robolectric.Shadows.shadowOf(signatures).setSignatures(arrayOf(android.content.pm.Signature(signer.encoded)))
        val info = android.content.pm.PackageInfo().apply {
            packageName = host
            applicationInfo = android.content.pm.ApplicationInfo().also { it.packageName = host; it.uid = Binder.getCallingUid() }
            signingInfo = signatures
        }
        val pm = org.robolectric.Shadows.shadowOf(service.packageManager)
        pm.installPackage(info)
        pm.setPackagesForUid(Binder.getCallingUid(), host)
        val control = com.weartak.plugin.sdk.aidl.IWearTakPluginControl.Stub.asInterface(Binder())
        remote.initializePluginV3(PluginInitParcel(host, Binder.getCallingUid(), service.packageName), control, null, null)
    }
    @Test fun noImportOrCapabilitiesBeforeValidHostInitialization() {
        try { remote.mainUiState; fail() } catch (_: SecurityException) { }
        try { remote.initializePluginV3(PluginInitParcel(service.packageName, Binder.getCallingUid() + 1, service.packageName), null, null, null); fail() }
        catch (_: IllegalArgumentException) { }
        initialize()
        assertEquals(0, remote.mainUiState.getInt("voice_channels_v1"))
        service.onUnbind(Intent())
        try { remote.mainUiState; fail() } catch (_: SecurityException) { }
    }
    @Test fun devPluginRejectsAnotherPackageEvenIfTheUidOwnsIt() {
        val other = "example.other.host"
        org.robolectric.Shadows.shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), other)
        assertThrows(IllegalArgumentException::class.java) {
            remote.initializePluginV3(PluginInitParcel(other, Binder.getCallingUid(), service.packageName), null, null, null)
        }
    }
    @Test fun absentHostStagingNeverAdvertisesVoiceDelivery() {
        initialize()
        assertEquals(0, remote.mainUiState.getInt("voice_channels_v1"))
        assertEquals(1, remote.mainUiState.getInt("ptt_watch_button_v1"))
        assertEquals(1, remote.mainUiState.getInt("ptt_media_button_v1"))
    }
    @Test fun provisionalImportActionCannotMutateConfiguration() {
        initialize()
        try { remote.invokeMainUiAction("voice.import.v1", Bundle()); fail() }
        catch (_: IllegalStateException) { }
        assertNull(ChannelStore.pending(service)); assertNull(ChannelStore.active(service))
    }
}
