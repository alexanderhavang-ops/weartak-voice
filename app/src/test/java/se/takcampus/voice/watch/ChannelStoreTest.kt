package se.takcampus.voice.watch

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.takcampus.voice.VoiceHandoff
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val mission = javaClass.getResourceAsStream("/fixtures/martine.json")!!.use { it.readBytes() }
    @Before fun clean() { context.deleteFile("voice-channels.json"); context.deleteFile("voice-channels.json.bak") }
    @Test fun stagingRequiresReviewAndDuplicateDeliveryCannotOverwriteAcceptedMission() {
        val id = UUID.randomUUID().toString(); val envelope = VoiceHandoff.encode(mission, id)
        assertEquals("STAGED", ChannelStore.stage(context, envelope)); assertNull(ChannelStore.active(context))
        assertEquals("STAGED", ChannelStore.receipt(context, id))
        ChannelStore.review(context, id, true)
        assertEquals("APPLIED", ChannelStore.receipt(context, id)); assertNotNull(ChannelStore.active(context))
        assertEquals("APPLIED", ChannelStore.stage(context, envelope)); assertNull(ChannelStore.pending(context))
    }
    @Test fun pendingReviewCannotBeReplacedAndRejectionDoesNotApply() {
        val id = UUID.randomUUID().toString(); ChannelStore.stage(context, VoiceHandoff.encode(mission, id))
        try { ChannelStore.stage(context, VoiceHandoff.encode(mission, UUID.randomUUID().toString())); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(id, VoiceHandoff.decode(ChannelStore.pending(context)).transferId)
        ChannelStore.review(context, id, false)
        assertEquals("REJECTED", ChannelStore.receipt(context, id)); assertNull(ChannelStore.active(context))
    }
    @Test fun clearConfigRemovesSetupsAndControlsButKeepsDiagnosticsAndInstallationIdentity() {
        val controls = context.getSharedPreferences("watch-test-controls", 0)
        controls.edit().putBoolean("backToggle", true).putInt("pttButton", 4).commit()
        val local = context.getSharedPreferences("voice-local", 0)
        local.edit().putString("uid", "installation").putString("callsign", "test").putString("channel", "old").commit()
        context.getSharedPreferences("ptt", 0).edit().putString("headset", "toggle").commit()
        context.getSharedPreferences(VoiceService.SESSION_PREFS, 0).edit().putBoolean(VoiceService.PHONE_CALL, true).commit()
        val history = context.getSharedPreferences("mumble_socket_history", 0)
        history.edit().putString("flows", "previous").commit()
        val id = UUID.randomUUID().toString()
        val hash = VoiceHandoff.hash(mission)
        ChannelStore.stage(context, VoiceHandoff.encode(mission, id))
        ChannelStore.review(context, id, true)
        val pending = UUID.randomUUID().toString()
        ChannelStore.stage(context, VoiceHandoff.encode(mission, pending), "test.host", hash)
        context.getFileStreamPath("voice-settings.vault").writeText("unreadable test data")

        repeat(2) { clearVoiceConfig(context, controls) }

        assertTrue(SettingsStore.load(context).host.isEmpty())
        assertNull(ChannelStore.active(context))
        assertNull(ChannelStore.pending(context))
        assertEquals("REJECTED", ChannelStore.receipt(context, pending))
        assertEquals("UNKNOWN", ChannelStore.receipt(context, id))
        assertEquals("installation", LocalVoiceSettings.uid(context))
        assertEquals("WearTAK", LocalVoiceSettings.callsign(context))
        assertNull(LocalVoiceSettings.load(context))
        assertFalse(VoiceService.phoneCallRequested(context))
        assertTrue(controls.all.isEmpty())
        assertTrue(context.getSharedPreferences("ptt", 0).all.isEmpty())
        assertEquals("previous", history.getString("flows", ""))
        assertEquals("STAGED", ChannelStore.stage(context, VoiceHandoff.encode(mission, id)))
    }
    @Test fun transferIdCannotBeReusedForDifferentContents() {
        val id = UUID.randomUUID().toString(); ChannelStore.stage(context, VoiceHandoff.encode(mission, id))
        val changed = org.json.JSONObject(String(mission)).put("name", "OTHER").toString().toByteArray()
        try { ChannelStore.stage(context, VoiceHandoff.encode(changed, id)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals("STAGED", ChannelStore.receipt(context, id))
    }
}
