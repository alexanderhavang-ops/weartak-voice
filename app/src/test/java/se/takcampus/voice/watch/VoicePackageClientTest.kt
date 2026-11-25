package se.takcampus.voice.watch

import android.app.Application
import android.os.*
import com.weartak.voice.api.*
import com.weartak.voice.api.VoicePackageContract as C
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import se.takcampus.voice.VoiceHandoff
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoicePackageClientTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var client: VoicePackageClient
    private lateinit var receiver: IVoicePackageReceiverV1
    private var bytes = byteArrayOf()
    private var taken = false
    private var state = 0
    private var receipt = CountDownLatch(1)
    private val control = object : IVoicePackageControlV1.Stub() {
        override fun getCapabilities() = Bundle().apply { putInt("version", 1); putInt("maxZipBytes", C.MAX_ZIP); putInt("maxJsonBytes", C.MAX_JSON) }
        override fun registerReceiver(value: IVoicePackageReceiverV1?) = Bundle().apply {
            receiver = requireNotNull(value); putInt("version", 1); putInt("code", C.OK)
        }
        override fun takePackage(handle: String?): Bundle { check(!taken); taken = true; return Bundle().apply { putInt("version", 1); putInt("code", C.OK); putByteArray("payload", bytes.copyOf()) } }
        override fun reportState(id: String?, payload: String?, mission: String?, value: Int, error: Int) = Bundle().apply {
            state = value; putInt("code", C.OK); receipt.countDown()
        }
        override fun getStatus(id: String?) = Bundle()
        override fun unregisterReceiver(value: IVoicePackageReceiverV1?) { }
    }
    @Before fun setup() {
        context.deleteFile("voice-channels.json"); context.deleteFile("voice-channels.json.bak")
        VoiceRuntime.receiptSync = null
        client = VoicePackageClient(context, "test.host", Binder.getCallingUid(), control); client.start()
    }
    @After fun close() { client.close(); VoiceRuntime.receiptSync = null }
    private fun send(data: ByteArray, kind: Int): String {
        bytes = data; taken = false; state = 0; receipt = CountDownLatch(1)
        val id = UUID.randomUUID().toString()
        receiver.onOffer(Bundle().apply {
            putInt("version", 1); putString("handle", UUID.randomUUID().toString()); putString("transferId", id)
            putInt("payloadKind", kind); putInt("byteCount", data.size); putString("payloadSha256", VoiceHandoff.hash(data))
            putLong("expiresAtElapsedRealtime", SystemClock.elapsedRealtime() + 120000)
        })
        assertTrue(receipt.await(5, TimeUnit.SECONDS)); return id
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }
    @Test fun jsonStagesAndOperatorReviewReportsAppliedWithoutStartingAudio() {
        val id = send(fixture("martine.json"), C.JSON)
        assertEquals(C.STAGED, state); assertNull(ChannelStore.active(context)); assertNotNull(ChannelStore.pending(context))
        receipt = CountDownLatch(1); ChannelStore.review(context, id, true)
        assertTrue(receipt.await(5, TimeUnit.SECONDS)); assertEquals(C.APPLIED, state)
        assertNotNull(ChannelStore.active(context)); assertNull(VoiceRuntime.connection)
    }
    @Test fun zipUsesSameImporterAndRejectPreservesPreviousConfiguration() {
        val id = send(fixture("martine.zip"), C.ZIP)
        assertEquals(C.STAGED, state); receipt = CountDownLatch(1); ChannelStore.review(context, id, true)
        assertTrue(receipt.await(5, TimeUnit.SECONDS)); assertEquals(C.APPLIED, state)
        val active = ChannelStore.active(context)
        send("{\"unsupported\":true}".toByteArray(), C.JSON)
        assertEquals(C.FAILED, state); assertEquals(active, ChannelStore.active(context)); assertNull(ChannelStore.pending(context))
    }
    @Test fun foreignHostCannotOffer() {
        val foreign = VoicePackageClient(context, "test.host", Binder.getCallingUid() + 1, control); foreign.start()
        Assert.assertThrows(IllegalStateException::class.java) { receiver.onOffer(Bundle()) }
        assertFalse(taken); assertNull(ChannelStore.pending(context)); foreign.close()
    }
}
