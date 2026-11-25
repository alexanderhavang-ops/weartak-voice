package se.takcampus.voice;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** An optional Mumble login. Its string representation never includes the secret. */
public final class VoiceLogin {
    public final String username, password;

    public VoiceLogin(String username, String password) {
        if (username == null || password == null || password.isEmpty() || password.length() > 1024 ||
                username.length() > 128 || username.chars().anyMatch(Character::isISOControl))
            throw invalid();
        this.username = username;
        this.password = password;
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Mumble credentials"); }
    static String endpoint(VoiceProfile p) { return p.endpoint().toLowerCase(Locale.ROOT); }

    private static String remove(JSONObject obj, String... names) {
        String value = null;
        for (String key : names) {
            if (!obj.has(key)) continue;
            Object field = obj.remove(key);
            if (!(field instanceof String) || (value != null && !value.equals(field))) throw invalid();
            value = (String) field;
        }
        return value;
    }

    private static VoiceLogin take(JSONObject obj) {
        String user = remove(obj, "username", "mumbleUsername", "mumble_username");
        String password = remove(obj, "password", "mumblePassword", "serverPassword", "mumbleServerPassword",
                "server_password", "mumble_password", "mumble_server_password", "mumble-password");
        if (password == null && user == null) return null;
        if ((password == null || password.isEmpty()) && (user == null || user.isEmpty())) return null;
        return new VoiceLogin(user == null ? "" : user, password);
    }

    private static void add(Map<String, VoiceLogin> map, VoiceProfile profile, VoiceLogin login) {
        if (login == null) return;
        if (profile.multicast()) throw invalid();
        VoiceLogin old = map.putIfAbsent(endpoint(profile), login);
        if (old != null && (!old.username.equals(login.username) || !old.password.equals(login.password))) throw invalid();
    }

    /** Removes recognized fields before normal channel validation; resolves only explicit destinations. */
    static final class Fields {
        final VoiceLogin common;
        final Map<String, VoiceLogin> byId = new HashMap<>();
        Fields(JSONObject obj) throws Exception {
            common = take(obj);
            JSONArray channels = obj.getJSONArray("channels");
            for (int i = 0; i < channels.length(); i++) {
                JSONObject channel = channels.getJSONObject(i);
                VoiceLogin login = take(channel);
                if (login != null && byId.put(channel.getString("id"), login) != null) throw invalid();
            }
        }
        Map<String, VoiceLogin> resolve(List<VoiceMission.Channel> channels) {
            Map<String, VoiceLogin> result = new HashMap<>();
            String commonEndpoint = null;
            Map<String, VoiceLogin> remaining = new HashMap<>(byId);
            for (VoiceMission.Channel channel : channels) {
                VoiceProfile profile = channel.profile();
                add(result, profile, remaining.remove(channel.id));
                if (common != null && !profile.multicast()) {
                    String key = endpoint(profile);
                    if (commonEndpoint != null && !commonEndpoint.equals(key)) throw invalid();
                    commonEndpoint = key;
                    add(result, profile, common);
                }
            }
            if (!remaining.isEmpty() || (common != null && commonEndpoint == null)) throw invalid();
            return result;
        }
    }
}
