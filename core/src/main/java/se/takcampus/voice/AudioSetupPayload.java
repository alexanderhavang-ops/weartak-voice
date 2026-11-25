package se.takcampus.voice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONTokener;

/** Typed content for the existing WTK1 sound framing. Direct codes carry one public channel. */
public final class AudioSetupPayload {
    public static final int MAX_BYTES = 384;
    private static final String DIRECT = "WTVOICE1:";

    private AudioSetupPayload() {}

    public static URI zipUri(String text) {
        checkSize(text);
        URI uri = URI.create(text);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Use an HTTPS link to the onboarding ZIP.");
        }
        return uri;
    }

    public static String encodeChannel(VoiceProfile channel) throws Exception {
        String sound = DIRECT + fields(channel).toString();
        checkSize(sound);
        return sound;
    }

    public static boolean isChannel(String sound) { return sound.startsWith(DIRECT); }

    public static VoiceProfile channel(String sound) throws Exception {
        checkSize(sound);
        if (!isChannel(sound)) throw new IllegalArgumentException("Not a voice channel code.");
        String body = sound.substring(DIRECT.length());
        VoicePackageImporter.validateDepth(body);
        JSONTokener tokens = new JSONTokener(body);
        Object decoded = tokens.nextValue();
        if (!(decoded instanceof JSONArray) || tokens.nextClean() != 0) throw new IllegalArgumentException("Invalid channel data.");
        return parse((JSONArray) decoded, UUID.nameUUIDFromBytes(body.getBytes(StandardCharsets.UTF_8)));
    }

    static JSONArray fields(VoiceProfile channel) throws Exception {
        // Network interface is local to the device. Account and trust material have no fields here.
        return new JSONArray().put(channel.mode().ordinal()).put(channel.label()).put(channel.host())
                .put(channel.port()).put(channel.roomId()).put(channel.roomName())
                .put(channel.dataHost()).put(channel.dataPort());
    }

    static VoiceProfile parse(JSONArray fields, UUID id) throws Exception {
        if (fields.length() != 8) throw new IllegalArgumentException("Invalid channel fields.");
        int mode = integer(fields, 0);
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Unknown transport.");
        String host = fields.getString(2);
        int room = integer(fields, 4);
        String roomName = fields.getString(5);
        String dataHost = fields.getString(6);
        int dataPort = integer(fields, 7);
        if ((mode == 0 && (!dataHost.isEmpty() || dataPort != 0))
                || (mode != 0 && (room != -1 || !roomName.isEmpty()))) {
            throw new IllegalArgumentException("Channel fields do not match the transport.");
        }
        return new VoiceProfile(id, fields.getString(1), VoiceProfile.Mode.values()[mode], host,
                integer(fields, 3), dataHost, dataPort, "", room, roomName);
    }

    private static int integer(JSONArray fields, int index) throws Exception {
        Object value = fields.get(index);
        if (!(value instanceof Integer)) throw new IllegalArgumentException("Expected an integer channel field.");
        return (Integer) value;
    }

    private static void checkSize(String value) {
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("This code is too long. Send it over BLE or use a short HTTPS ZIP link.");
    }
}
