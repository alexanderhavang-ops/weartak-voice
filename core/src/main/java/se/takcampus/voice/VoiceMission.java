package se.takcampus.voice;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Configuration only. Importing a mission never grants channel access or changes TLS trust. */
public final class VoiceMission {
    public final String id, name;
    public final List<Channel> channels;

    public VoiceMission(String id, String name, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.channels = Collections.unmodifiableList(channels);
    }

    public static final class Channel {
        public final String id, name, host;
        public final int port, serverChannelId;
        public final VoiceProfile profile;

        public Channel(String id, String name, String host, int port, int serverChannelId) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.serverChannelId = serverChannelId;
            this.profile = null;
        }

        Channel(VoiceProfile profile) {
            this.id = profile.id().toString();
            this.name = profile.label();
            this.host = profile.host();
            this.port = profile.port();
            this.serverChannelId = profile.roomId();
            this.profile = profile;
        }

        public VoiceProfile profile() {
            if (profile != null) return profile;
            String address = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            String label = name.length() > 100 ? name.substring(0, 100) : name;
            return new VoiceProfile(UUID.fromString(id), label, VoiceProfile.Mode.MUMBLE, address, port, "", 0, "", serverChannelId, label);
        }
    }
}
