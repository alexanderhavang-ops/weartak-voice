package se.takcampus.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Wire adapter for Vx 2.1 multicast audio and its separate data channel. */
public final class VxWire {
    public static final int FRAME_MS = 20;
    public static final int SAMPLE_RATE = 8000;
    public static final int MAX_DATAGRAM = 1024;
    private static final int RTP_HEADER = 12;
    private static final int USER_STATE = 1;

    public record Presence(String uid, String callsign, String group, int port, String address,
                           boolean requestReports, long timestamp, boolean leaving) {}

    private VxWire() {}

    public static byte[] encodeAudio(byte[] opus, boolean rtp, int sequence, long unixSeconds, String uid) {
        int length = opus.length + (rtp ? RTP_HEADER : 0);
        if (opus.length == 0 || length > MAX_DATAGRAM) throw new IllegalArgumentException("Invalid audio packet size.");
        if (!rtp) return opus.clone();
        return ByteBuffer.allocate(length).put((byte) 0x80).put((byte) 0)
                .putShort((short) sequence).putInt((int) unixSeconds).putInt(uid.hashCode()).put(opus).array();
    }

    public static byte[] decodeAudio(byte[] datagram) {
        if (datagram.length == 0 || datagram.length > MAX_DATAGRAM) return null;
        int version = datagram[0] & 255;
        if (version != 0x80 && version != 0x81) return datagram.clone();
        if (version != 0x80 || datagram.length <= RTP_HEADER) return null;
        int packetType = datagram[1] & 255;
        if (packetType >= 192 && packetType <= 223) return null;
        return Arrays.copyOfRange(datagram, RTP_HEADER, datagram.length);
    }

    public static byte[] encodePresence(Presence state) {
        byte[] payload = MumbleWire.join(
                MumbleWire.text(1, state.uid()), MumbleWire.text(2, state.callsign()),
                MumbleWire.text(3, state.group()), MumbleWire.text(4, Integer.toString(state.port())),
                MumbleWire.text(5, state.address()), MumbleWire.integer(6, state.requestReports() ? 1 : 0),
                MumbleWire.integer(7, state.timestamp()), MumbleWire.integer(8, state.leaving() ? 1 : 0),
                MumbleWire.integer(9, 2), MumbleWire.text(10, "NOT_LINKING"));
        return ByteBuffer.allocate(6 + payload.length).putShort((short) USER_STATE).putInt(payload.length).put(payload).array();
    }

    public static Presence decodePresence(byte[] datagram) throws IOException {
        if (datagram.length < 6 || datagram.length > MAX_DATAGRAM) throw new IOException("Invalid VX data packet size.");
        ByteBuffer packet = ByteBuffer.wrap(datagram);
        int type = packet.getShort() & 65535;
        int length = packet.getInt();
        if (length != packet.remaining()) throw new IOException("Invalid VX data packet length.");
        if (type != USER_STATE) return null;
        MumbleWire.Fields f = new MumbleWire.Fields(Arrays.copyOfRange(datagram, 6, datagram.length));
        String port = text(f, 4);
        try {
            return new Presence(VoiceProfile.name(text(f, 1), "Participant ID"),
                    VoiceProfile.name(text(f, 2), "Callsign"), text(f, 3),
                    port.isEmpty() ? 0 : Integer.parseInt(port), text(f, 5),
                    flag(f, 6), f.has(7) ? f.integer(7) : 0, flag(f, 8));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid VX participant state.", e);
        }
    }

    private static String text(MumbleWire.Fields f, int n) throws IOException { return f.has(n) ? f.text(n) : ""; }
    private static boolean flag(MumbleWire.Fields f, int n) throws IOException { return f.has(n) && f.integer(n) != 0; }

    static byte[] senderReport(String uid, long wallMs, int packets, int octets) {
        long seconds = wallMs / 1000;
        return ByteBuffer.allocate(28).put((byte) 0x80).put((byte) 200).putShort((short) 6)
                .putInt(uid.hashCode()).putLong(seconds).putInt((int) seconds).putInt(packets).putInt(octets).array();
    }

    static byte[] sourceDescription(String uid) { return rtcpSource(uid, 202); }
    static byte[] goodbye(String uid) { return rtcpSource(uid, 203); }

    private static byte[] rtcpSource(String uid, int type) {
        return ByteBuffer.allocate(8).put((byte) 0x81).put((byte) type).putShort((short) 1)
                .putInt(uid.hashCode()).array();
    }
}
