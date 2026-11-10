package se.takcampus.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Legacy Opus audio packets for Mumble 1.3-compatible TCP tunneling. */
public final class MumbleAudioPacket {
    public final long session, sequence;
    public final byte[] opus;
    public final boolean last;
    private MumbleAudioPacket(long session, long sequence, byte[] opus, boolean last) {
        this.session = session; this.sequence = sequence; this.opus = opus; this.last = last;
    }
    public static byte[] outgoing(long sequence, byte[] opus, boolean last) throws IOException {
        if (sequence < 0 || opus.length > 1000) throw new IOException("Invalid audio packet");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(4 << 5); put(out, sequence); put(out, opus.length | (last ? 0x2000 : 0));
        out.write(opus, 0, opus.length); return out.toByteArray();
    }
    public static MumbleAudioPacket incoming(byte[] packet) throws IOException {
        Cursor c = new Cursor(packet);
        if ((c.byteValue() >>> 5) != 4) throw new IOException("Unsupported voice codec");
        long session = c.number(), sequence = c.number(), size = c.number();
        if (session > Integer.MAX_VALUE || size < 0 || (size & ~0x3fffL) != 0) throw new IOException("Invalid voice header");
        int length = (int) (size & 0x1fff);
        if (length > 1000 || length > packet.length - c.at) throw new IOException("Truncated voice packet");
        // Optional positional floats are deliberately not interpreted.
        return new MumbleAudioPacket(session, sequence, Arrays.copyOfRange(packet, c.at, c.at + length), (size & 0x2000) != 0);
    }
    public static void put(ByteArrayOutputStream out, long n) throws IOException {
        if (n < 0) throw new IOException("Negative audio integer");
        if (n < 0x80) out.write((int) n);
        else if (n < 0x4000) { out.write((int) (n >> 8) | 0x80); out.write((int) n & 255); }
        else if (n < 0x200000) { out.write((int) (n >> 16) | 0xc0); out.write((int) (n >> 8) & 255); out.write((int) n & 255); }
        else if (n < 0x10000000) { out.write((int) (n >> 24) | 0xe0); for (int s = 16; s >= 0; s -= 8) out.write((int) (n >> s) & 255); }
        else { out.write(n <= 0xffffffffL ? 0xf0 : 0xf4); for (int s = n <= 0xffffffffL ? 24 : 56; s >= 0; s -= 8) out.write((int) (n >> s) & 255); }
    }
    private static final class Cursor {
        final byte[] data; int at;
        Cursor(byte[] data) { this.data = data; }
        int byteValue() throws IOException { if (at == data.length) throw new IOException("Truncated audio integer"); return data[at++] & 255; }
        long number() throws IOException {
            int b = byteValue(), remaining;
            long n;
            if ((b & 0x80) == 0) return b;
            if ((b & 0xc0) == 0x80) { n = b & 0x3f; remaining = 1; }
            else if ((b & 0xe0) == 0xc0) { n = b & 0x1f; remaining = 2; }
            else if ((b & 0xf0) == 0xe0) { n = b & 0x0f; remaining = 3; }
            else if (b == 0xf0 || b == 0xf4) { n = 0; remaining = b == 0xf0 ? 4 : 8; }
            else throw new IOException("Unsupported audio integer encoding");
            for (int i = 0; i < remaining; i++) n = (n << 8) | byteValue();
            if (n < 0) throw new IOException("Audio integer overflow");
            return n;
        }
    }
    /** Sequence counters are in 10 ms units. Reject malformed and unsupported short Opus frames. */
    public static int duration10ms(byte[] opus) throws IOException {
        if (opus.length == 0) throw new IOException("Empty Opus packet");
        int toc = opus[0] & 255, samples;
        if ((toc & 0x80) != 0) samples = (48000 << ((toc >> 3) & 3)) / 400;
        else if ((toc & 0x60) == 0x60) samples = (toc & 8) != 0 ? 960 : 480;
        else { int size = (toc >> 3) & 3; samples = size == 3 ? 2880 : (48000 << size) / 100; }
        int frames = (toc & 3) == 0 ? 1 : (toc & 3) == 3 ? opus.length > 1 ? opus[1] & 63 : 0 : 2;
        int total = samples * frames;
        if (frames == 0 || total < 480 || total > 5760 || total % 480 != 0) throw new IOException("Unsupported Opus duration");
        return total / 480;
    }
}
