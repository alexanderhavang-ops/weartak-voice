package se.takcampus.voice;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Connection and channel metadata. Authentication is stored separately by the app. */
public record VoiceProfile(UUID id, String label, Mode mode, String host, int port,
                           String dataHost, int dataPort, String networkInterface,
                           int roomId, String roomName) {
    public enum Mode { MUMBLE, VX_UDP, VX_RTP }

    public static final int DEFAULT_MUMBLE_PORT = 64738;
    public static final String DEFAULT_VOICE_GROUP = "239.192.18.1";
    public static final String DEFAULT_DATA_GROUP = "239.192.18.2";
    public static final int DEFAULT_VOICE_PORT = 50000;
    public static final int DEFAULT_DATA_PORT = 50002;

    public VoiceProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(mode);
        label = name(label, "Channel name");
        host = host == null ? "" : host.trim();
        dataHost = dataHost == null ? "" : dataHost.trim();
        networkInterface = networkInterface == null ? "" : networkInterface.trim();
        roomName = roomName == null || roomName.trim().isEmpty() ? "" : name(roomName, "Mumble room");
        requirePort(port);
        if (mode == Mode.MUMBLE) {
            if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.:%_-]+")) {
                throw new IllegalArgumentException("Enter a Mumble hostname or IP address.");
            }
            if (roomId < -1) throw new IllegalArgumentException("Invalid Mumble room ID.");
            dataHost = "";
            dataPort = 0;
            networkInterface = "";
        } else {
            validateGroup(host);
            if (!dataHost.isEmpty() || dataPort != 0) {
                validateGroup(dataHost);
                requirePort(dataPort);
                if (host.equals(dataHost) && (port == dataPort || (mode == Mode.VX_RTP && port + 1 == dataPort))) {
                    throw new IllegalArgumentException("Audio and data must use different endpoints.");
                }
            }
            if (mode == Mode.VX_RTP && port == 65535) {
                throw new IllegalArgumentException("RTP needs the next UDP port for RTCP.");
            }
            if (!networkInterface.matches("[A-Za-z0-9_.-]{0,32}")) {
                throw new IllegalArgumentException("Invalid network interface name.");
            }
            roomId = -1;
            roomName = "";
        }
    }

    public boolean multicast() { return mode != Mode.MUMBLE; }

    public boolean hasDataChannel() { return dataPort != 0; }

    public VoiceProfile withRoom(int id, String name) {
        if (multicast()) throw new IllegalStateException("Local multicast uses the saved channel.");
        return new VoiceProfile(this.id, label, mode, host, port, "", 0, "", id, name);
    }

    public String endpoint() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    public static String name(String text, String field) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be 1–100 characters.");
        }
        return value;
    }

    private static void requirePort(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
    }

    private static void validateGroup(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Enter an IPv4 multicast group.");
        int[] address = new int[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (!octet.matches("0|[1-9][0-9]{0,2}") || (address[i] = Integer.parseInt(octet)) > 255) {
                throw new IllegalArgumentException("Invalid IPv4 multicast group.");
            }
        }
        if (address[0] < 224 || address[0] > 239) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s is outside the IPv4 multicast range.", value));
        }
    }
}
