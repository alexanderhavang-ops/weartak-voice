package se.takcampus.voice.watch

import android.media.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import se.takcampus.voice.MumbleAudioPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Platform Opus: 48 kHz mono, bounded receive queue, microphone only while PTT is held. */
class VoiceAudio(private val context: Context, private val failure: (String) -> Unit) : AutoCloseable {
    private data class Incoming(val who: Int, val packet: MumbleAudioPacket, val queued: Long)
    private val queue = ArrayBlockingQueue<Incoming>(24)
    private val closed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    private val playback = Thread({ receiveLoop() }, "voice-playback").apply { start() }

    fun receive(who: Int, packet: MumbleAudioPacket) {
        if (!closed.get()) queue.offer(Incoming(who, packet, System.nanoTime()))
    }
    @Synchronized fun startCapture(send: (ByteArray) -> Unit) {
        check(!closed.get())
        if (capturing.get()) return
        check(captureThread?.isAlive != true) { "Previous capture is stopping" }
        if (!capturing.compareAndSet(false, true)) return
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var codec: MediaCodec? = null
            var echo: AcousticEchoCanceler? = null
            var record: AudioRecord? = null
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 24000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start()
                }
                val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0)
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min, 7680)).build()
                check(record.state == AudioRecord.STATE_INITIALIZED); recorder = record
                if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                if (!capturing.get()) return@Thread
                record.startRecording()
                val pcm = ByteArray(1920); val info = MediaCodec.BufferInfo(); var samples = 0L
                while (capturing.get() && !closed.get()) {
                    var filled = 0
                    while (filled < pcm.size && capturing.get()) {
                        val n = record.read(pcm, filled, pcm.size - filled)
                        if (n <= 0) error("Microphone read failed")
                        filled += n
                    }
                    if (!capturing.get()) break
                    val input = codec.dequeueInputBuffer(20000)
                    if (input >= 0) {
                        codec.getInputBuffer(input)!!.apply { clear(); put(pcm, 0, filled) }
                        codec.queueInputBuffer(input, 0, filled, samples * 1000000 / 48000, 0); samples += filled / 2
                    }
                    while (true) {
                        val output = codec.dequeueOutputBuffer(info, 0)
                        if (output < 0) break
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && capturing.get()) {
                                val bytes = ByteArray(info.size)
                                codec.getOutputBuffer(output)!!.apply { position(info.offset); limit(info.offset + info.size); get(bytes) }; send(bytes)
                            }
                        } finally { codec.releaseOutputBuffer(output, false) }
                    }
                }
            } catch (_: Exception) { if (capturing.get() && !closed.get()) failure("Microphone or Opus encoder unavailable") }
            finally {
                capturing.set(false); recorder = null
                try { record?.stop() } catch (_: Exception) {}
                record?.release(); echo?.release()
                try { codec?.stop() } catch (_: Exception) {}
                codec?.release()
            }
        }, "voice-capture").apply { start() }
    }
    @Synchronized fun stopCapture() {
        capturing.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        val old = captureThread
        if (old !== Thread.currentThread()) old?.join(1000)
        if (old?.isAlive != true) captureThread = null
    }
    private class Decoder : AutoCloseable {
        val codec: MediaCodec
        val track: AudioTrack
        var lastSequence = -1L
        private var ended = false
        var touched = System.nanoTime()
        init {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(1); putShort(0); putInt(48000); putShort(0); put(0)
            }.array()
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(header))
                setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).apply { flip() })
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try { codec.configure(format, null, null, 0); codec.start() }
            catch (error: Exception) { codec.release(); throw error }
            val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            try {
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min, 5760)).build()
                track.play()
            } catch (error: Exception) { codec.stop(); codec.release(); throw error }
        }
        fun packet(packet: MumbleAudioPacket) {
            if (packet.opus.isEmpty()) { ended = packet.last; drain(10000); return }
            if (ended || (packet.sequence <= lastSequence && System.nanoTime() - touched > 1_000_000_000L)) {
                codec.flush(); lastSequence = -1; ended = false
            }
            if (packet.sequence <= lastSequence) return
            MumbleAudioPacket.duration10ms(packet.opus)
            val input = codec.dequeueInputBuffer(5000)
            if (input < 0) return
            codec.getInputBuffer(input)!!.apply { clear(); put(packet.opus) }
            codec.queueInputBuffer(input, 0, packet.opus.size, packet.sequence * 10000, 0)
            lastSequence = packet.sequence; touched = System.nanoTime()
            drain(5000)
            ended = packet.last
        }
        fun drain(timeout: Long = 0) {
            val info = MediaCodec.BufferInfo()
            var wait = timeout
            while (true) {
                val out = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                if (out < 0) break
                try {
                    if (info.size > 0) {
                        val pcm = codec.getOutputBuffer(out)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        track.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                } finally { codec.releaseOutputBuffer(out, false) }
            }
        }
        override fun close() {
            try { track.pause(); track.flush(); track.release() } catch (_: Exception) {}
            try { codec.stop(); codec.release() } catch (_: Exception) {}
        }
    }
    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val decoders = linkedMapOf<Int, Decoder>()
        try {
            while (!closed.get()) {
                val item = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                val now = System.nanoTime()
                decoders.entries.removeAll { if (now - it.value.touched > 10_000_000_000L) { it.value.close(); true } else false }
                val active = decoders.entries.iterator()
                while (active.hasNext()) {
                    val decoder = active.next().value
                    try { decoder.drain() }
                    catch (_: Exception) {
                        decoder.close(); active.remove(); failure("Voice playback failed — check audio support")
                    }
                }
                if (item == null || now - item.queued > 250_000_000L) continue
                if (item.packet.opus.isEmpty() && !decoders.containsKey(item.who)) continue
                try {
                    if (!decoders.containsKey(item.who) && decoders.size >= 4) continue
                    val decoder = decoders[item.who] ?: Decoder().also { decoders[item.who] = it }
                    decoder.packet(item.packet)
                } catch (_: Exception) { decoders.remove(item.who)?.close(); failure("Voice playback failed — check audio support") }
            }
        } catch (_: InterruptedException) { /* shutdown */ }
        finally { decoders.values.forEach { it.close() }; queue.clear() }
    }
    override fun close() {
        closed.set(true); stopCapture(); playback.interrupt()
        if (Thread.currentThread() !== playback) playback.join(1000)
    }
}
