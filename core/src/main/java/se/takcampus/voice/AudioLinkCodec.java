package se.takcampus.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/** WTK1 acoustic transport: short HTTPS link only, not credentials. See docs/AUDIO_ONBOARDING.md. */
public final class AudioLinkCodec {
    public static final int RATE = 48000, SLOT = 144, MAX_LINK = 384, CHUNK = 32, FRAME = 44;
    private static final int SYNC = 0xD391C5A7;
    private static final long PATTERN;
    static {
        long value = 0;
        for (int i = 31; i >= 0; i--) value = (value << 2) | (((SYNC >>> i) & 1) == 0 ? 1 : 2);
        PATTERN = value;
    }
    private AudioLinkCodec() {}
    private static long crc(byte[] bytes, int length) { CRC32 crc = new CRC32(); crc.update(bytes, 0, length); return crc.getValue(); }
    public static byte[][] frames(String link) {
        byte[] bytes = link.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_LINK) throw new IllegalArgumentException("Link too long");
        int count = (bytes.length + CHUNK - 1) / CHUNK;
        byte[][] frames = new byte[count][];
        int transfer = (int) crc(bytes, bytes.length);
        for (int index = 0; index < count; index++) {
            byte[] frame = new byte[FRAME];
            ByteBuffer.wrap(frame).putInt(transfer).putShort((short) bytes.length).put((byte) index).put((byte) count);
            System.arraycopy(bytes, index * CHUNK, frame, 8, Math.min(CHUNK, bytes.length - index * CHUNK));
            ByteBuffer.wrap(frame, FRAME - 4, 4).putInt((int) crc(frame, FRAME - 4)); frames[index] = frame;
        }
        return frames;
    }
    /** Reference encoder used by transport tests. Phone uses the same slot format in Web Audio. */
    public static short[] encode(String link) {
        ByteArrayOutputStream slots = new ByteArrayOutputStream();
        for (byte[] frame : frames(link)) {
            for (int i = 0; i < 16; i++) slots.write(i & 1);
            for (int i = 31; i >= 0; i--) bit(slots, (SYNC >>> i) & 1);
            for (byte b : frame) for (int i = 7; i >= 0; i--) bit(slots, (b >>> i) & 1);
            // Quiet gap lets every decoder abandon a damaged frame before the next sync.
            for (int i = 0; i < 24; i++) slots.write(2);
        }
        byte[] tones = slots.toByteArray(); short[] pcm = new short[tones.length * SLOT];
        for (int slot = 0; slot < tones.length; slot++) {
            if (tones[slot] == 2) continue;
            int frequency = tones[slot] == 0 ? 2000 : 4000;
            for (int n = 0; n < SLOT; n++) pcm[slot * SLOT + n] = (short) (15000 * Math.sin(2 * Math.PI * frequency * n / RATE));
        }
        return pcm;
    }
    private static void bit(ByteArrayOutputStream slots, int bit) { slots.write(bit); slots.write(1 - bit); }

    /** Bounded streaming receiver, eight clock phases; CRC checks every independently synchronized frame. */
    public static final class Decoder {
        private final Phase[] phases = new Phase[8];
        private final Consumer<String> complete;
        private long samples;
        private int transfer, total, count;
        private final byte[][] chunks = new byte[12][];
        private boolean done;
        public Decoder(Consumer<String> complete) {
            this.complete = complete;
            for (int i = 0; i < phases.length; i++) phases[i] = new Phase(i * SLOT / phases.length);
        }
        public void feed(short[] pcm, int length) {
            if (length < 0 || length > pcm.length) throw new IllegalArgumentException();
            for (int i = 0; i < length && !done; i++, samples++) for (Phase p : phases) p.sample(pcm[i], samples);
        }
        private void frame(byte[] frame) {
            if ((int) crc(frame, FRAME - 4) != ByteBuffer.wrap(frame, FRAME - 4, 4).getInt()) return;
            ByteBuffer header = ByteBuffer.wrap(frame);
            int id = header.getInt(), size = header.getShort() & 65535, index = header.get() & 255, number = header.get() & 255;
            if (size < 1 || size > MAX_LINK || number != (size + CHUNK - 1) / CHUNK || index >= number) return;
            if (count == 0 || (id != transfer && index == 0)) {
                Arrays.fill(chunks, null); transfer = id; total = size; count = number;
            }
            if (id != transfer || size != total || number != count) return;
            byte[] chunk = Arrays.copyOfRange(frame, 8, 40);
            if (chunks[index] != null && !Arrays.equals(chunks[index], chunk)) return;
            chunks[index] = chunk;
            for (int i = 0; i < count; i++) if (chunks[i] == null) return;
            byte[] all = new byte[total];
            for (int i = 0; i < count; i++) System.arraycopy(chunks[i], 0, all, i * CHUNK, Math.min(CHUNK, total - i * CHUNK));
            if ((int) crc(all, total) != transfer) return;
            done = true; complete.accept(new String(all, StandardCharsets.UTF_8));
        }
        private final class Phase {
            final int offset;
            final double low = 2 * Math.cos(2 * Math.PI * 2000 / RATE), high = 2 * Math.cos(2 * Math.PI * 4000 / RATE);
            double l1, l2, h1, h2, energy;
            int n, seen, byteBits, pair = -1, position = -1;
            long history;
            byte[] buffer;
            Phase(int offset) { this.offset = offset; }
            void sample(short value, long at) {
                if (at < offset) return;
                double l = value + low * l1 - l2; l2 = l1; l1 = l;
                double h = value + high * h1 - h2; h2 = h1; h1 = h; energy += (double) value * value;
                if (++n < SLOT) return;
                double a = l1*l1 + l2*l2 - low*l1*l2, b = h1*h1 + h2*h2 - high*h1*h2;
                boolean valid = energy > SLOT * 30.0 * 30 && Math.max(a,b) > energy * SLOT * .12 && Math.max(a,b) > Math.min(a,b) * 1.8;
                int tone = b > a ? 1 : 0;
                n = 0; l1 = l2 = h1 = h2 = energy = 0;
                if (!valid) { position = -1; pair = -1; history = 0; seen = 0; return; }
                if (position >= 0) {
                    if (pair == -1) pair = tone;
                    else {
                        if (pair == tone) { position = -1; pair = -1; seen = 0; history = 0; return; }
                        buffer[position] = (byte) ((buffer[position] << 1) | pair); pair = -1;
                        if (++byteBits == 8) {
                            byteBits = 0;
                            if (++position == FRAME) { frame(buffer); position = -1; seen = 0; history = 0; }
                        }
                    }
                    return;
                }
                history = (history << 1) | tone; seen = Math.min(seen + 1, 64);
                if (seen == 64 && history == PATTERN) { buffer = new byte[FRAME]; position = 0; byteBits = 0; pair = -1; }
            }
        }
    }
}
