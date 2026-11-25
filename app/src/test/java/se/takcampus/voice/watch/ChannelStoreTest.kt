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
    @Test fun transferIdCannotBeReusedForDifferentContents() {
        val id = UUID.randomUUID().toString(); ChannelStore.stage(context, VoiceHandoff.encode(mission, id))
        val changed = org.json.JSONObject(String(mission)).put("name", "OTHER").toString().toByteArray()
        try { ChannelStore.stage(context, VoiceHandoff.encode(changed, id)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals("STAGED", ChannelStore.receipt(context, id))
    }
}
