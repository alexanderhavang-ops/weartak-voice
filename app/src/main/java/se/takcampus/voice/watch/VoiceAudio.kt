package se.takcampus.voice.watch

import android.media.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import se.takcampus.voice.PttTiming
import android.os.Process
import android.util.Log
import se.takcampus.voice.MumbleAudioPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Platform Opus: 48 kHz mono, bounded receive queue, microphone only while PTT is held. */
class VoiceAudio(private val context: Context, private val failure: (String) -> Unit) : AutoCloseable {
    private companion object {
        fun releaseCodec(codec: MediaCodec?) {
            if (codec == null) return
            runCatching { codec.stop() }
            runCatching { codec.release() }.onFailure { Log.w("WearTAKBuds", "Codec release failed", it) }
        }
    }

    private data class Incoming(val who: String, val opus: ByteArray, val sequence: Long, val last: Boolean, val queued: Long)
    private val localSequence = java.util.concurrent.atomic.AtomicLong()
    private val queue = ArrayBlockingQueue<Incoming>(24)
    private val closed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    private val playback = Thread({ receiveLoop() }, "voice-playback").apply { start() }

    fun receive(who: Int, packet: MumbleAudioPacket) {
        if (!closed.get() && VoiceRuntime.audioFocus) queue.offer(Incoming(who.toString(), packet.opus, packet.sequence, packet.last, System.nanoTime()))
    }
    fun receiveLocal(who: String, opus: ByteArray) {
        if (!closed.get() && VoiceRuntime.audioFocus) queue.offer(Incoming(who, opus, localSequence.getAndAdd(2), false, System.nanoTime()))
    }
    @Synchronized fun startCapture(timing: PttTiming, onReady: () -> Unit, rate: Int = 48000, send: (ByteArray) -> Unit) {
        check(!closed.get())
        if (capturing.get()) return
        check(captureThread?.isAlive != true) { "Previous capture is stopping" }
        if (!capturing.compareAndSet(false, true)) return
        captureThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            var codec: MediaCodec? = null
            var echo: AcousticEchoCanceler? = null
            var record: AudioRecord? = null
            try {
                val frame = rate / 50 * 2
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, if (rate == 48000) 24000 else 32000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0)
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min, frame * 4)).build()
                check(record.state == AudioRecord.STATE_INITIALIZED); recorder = record
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(record.audioSessionId)
                    echo?.enabled = true
                }
                if (!capturing.get()) return@Thread
                record.startRecording()
                var firstFrame = true
                val pcm = ByteArray(frame); val info = MediaCodec.BufferInfo(); var samples = 0L
                while (capturing.get() && !closed.get()) {
                    var filled = 0
                    while (filled < pcm.size && capturing.get()) {
                        val n = record.read(pcm, filled, pcm.size - filled)
                        if (n <= 0) error("Microphone read failed")
                        filled += n
                    }
                    if (!capturing.get()) break
                    val now = SystemClock.elapsedRealtime()
                    if (now >= timing.deadline()) break
                    if (firstFrame) {
                        firstFrame = false
                        onReady()
                    }
                    timing.observe(pcm, now)
                    val input = codec.dequeueInputBuffer(20000)
                    if (input >= 0) {
                        codec.getInputBuffer(input)!!.apply { clear(); put(pcm, 0, filled) }
                        codec.queueInputBuffer(input, 0, filled, samples * 1000000 / rate, 0); samples += filled / 2
                    }
                    while (true) {
                        val output = codec.dequeueOutputBuffer(info, 0)
                        if (output < 0) break
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && capturing.get() && SystemClock.elapsedRealtime() < timing.deadline()) {
                                val bytes = ByteArray(info.size)
                                codec.getOutputBuffer(output)!!.apply { position(info.offset); limit(info.offset + info.size); get(bytes) }; send(bytes)
                            }
                        } finally { codec.releaseOutputBuffer(output, false) }
                    }
                }
            } catch (e: Exception) {
                if (capturing.get() && !closed.get()) {
                    Log.w("WearTAKBuds", "Capture failed", e)
                    failure("Microphone or Opus encoder unavailable")
                }
            }
            finally {
                capturing.set(false); recorder = null
                runCatching { record?.stop() }
                runCatching { echo?.release() }.onFailure { Log.w("WearTAKBuds", "Echo effect release failed", it) }
                runCatching { record?.release() }.onFailure { Log.w("WearTAKBuds", "Recorder release failed", it) }
                releaseCodec(codec)
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
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min, 5760)).build()
            } catch (error: Exception) { releaseCodec(codec); throw error }
            try { track.play() }
            catch (error: Exception) { close(); throw error }
        }
        fun packet(packet: Incoming) {
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
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }.onFailure { Log.w("WearTAKBuds", "Playback track release failed", it) }
            releaseCodec(codec)
        }
    }
    private fun receiveLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val decoders = linkedMapOf<String, Decoder>()
        try {
            while (!closed.get()) {
                val item = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!VoiceRuntime.audioFocus) {
                    decoders.values.forEach { it.close() }
                    decoders.clear()
                    queue.clear()
                    continue
                }
                val now = System.nanoTime()
                decoders.entries.removeAll { if (now - it.value.touched > 500_000_000L) { it.value.close(); true } else false }
                val active = decoders.entries.iterator()
                while (active.hasNext()) {
                    val decoder = active.next().value
                    try { decoder.drain() }
                    catch (_: Exception) {
                        decoder.close(); active.remove(); failure("Voice playback failed — check audio support")
                    }
                }
                if (item == null || now - item.queued > 250_000_000L) continue
                if (item.opus.isEmpty() && !decoders.containsKey(item.who)) continue
                try {
                    if (!decoders.containsKey(item.who) && decoders.size >= 4) continue
                    val decoder = decoders[item.who] ?: Decoder().also { decoders[item.who] = it }
                    decoder.packet(item)
                } catch (e: Exception) {
                    Log.w("WearTAKBuds", "Playback failed", e)
                    decoders.remove(item.who)?.close()
                    failure("Voice playback failed — check audio support")
                }
            }
        } catch (_: InterruptedException) { /* shutdown */ }
        finally { decoders.values.forEach { it.close() }; queue.clear() }
    }
    override fun close() {
        closed.set(true); stopCapture(); playback.interrupt()
        if (Thread.currentThread() !== playback) playback.join(1000)
    }
}
