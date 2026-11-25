package se.takcampus.voice;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** An optional Mumble login. Its string representation never includes the secret. */
public final class VoiceLogin {
    public static final String SERVER = "server", ACCOUNT = "account", ENROLL = "enroll", CERT = "p12";
    public final String mode, username, password, profileId, identity;

    private VoiceLogin(String mode, String username, String password, String profileId, String identity) {
        this.mode = mode;
        this.username = username;
        this.password = password;
        this.profileId = profileId;
        this.identity = identity;
    }

    public static VoiceLogin server(String password) {
        if (password == null || password.length() > 1024) throw invalid();
        return new VoiceLogin(SERVER, "", password, "", "");
    }

    public static VoiceLogin enroll(String id) {
        if (id == null || (!id.isEmpty() && !java.util.UUID.fromString(id).toString().equals(id))) throw invalid();
        return new VoiceLogin(ENROLL, "", "", id, "");
    }

    public static VoiceLogin certificate(byte[] bytes, String password) {
        if (bytes == null || bytes.length == 0 || bytes.length > 24000 || password == null || password.length() > 1024) throw invalid();
        return new VoiceLogin(CERT, "", password, "", java.util.Base64.getEncoder().encodeToString(bytes));
    }

    void write(JSONObject channel) throws Exception {
        if (ACCOUNT.equals(mode) || (SERVER.equals(mode) && !password.isEmpty())) {
            if (!username.isEmpty()) channel.put("username", username);
            channel.put("password", password);
            return;
        }
        JSONObject auth = new JSONObject().put("mode", mode);
        if (ENROLL.equals(mode)) auth.put("profileId", profileId);
        if (CERT.equals(mode)) auth.put("identity", identity).put("filePassword", password);
        channel.put("voiceAuth", auth);
    }


    public VoiceLogin(String username, String password) {
        this(username == null || username.isEmpty() ? SERVER : ACCOUNT, username, password, "", "");
        if (username == null || password == null || password.isEmpty() || password.length() > 1024 ||
                username.length() > 128 || username.chars().anyMatch(Character::isISOControl))
            throw invalid();
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

    static VoiceLogin take(JSONObject obj) throws Exception {
        String user = remove(obj, "username", "mumbleUsername", "mumble_username");
        String password = remove(obj, "password", "mumblePassword", "serverPassword", "mumbleServerPassword",
                "server_password", "mumble_password", "mumble_server_password", "mumble-password");
        if (obj.has("voiceAuth")) {
            Object raw = obj.remove("voiceAuth");
            if (user != null || password != null || !(raw instanceof JSONObject)) throw invalid();
            JSONObject auth = (JSONObject) raw;
            try {
                return parseAuth(auth);
            } catch (Exception malformed) {
                throw invalid();
            }
        }
        if (password == null && user == null) return null;
        if ((password == null || password.isEmpty()) && (user == null || user.isEmpty())) return null;
        return new VoiceLogin(user == null ? "" : user, password);
    }

    private static VoiceLogin parseAuth(JSONObject auth) throws Exception {
        String mode = auth.getString("mode");
        if (SERVER.equals(mode) && auth.length() == 1) return server("");
        if (ENROLL.equals(mode) && auth.length() == 2) return enroll(auth.getString("profileId"));
        if (CERT.equals(mode) && auth.length() == 3)
            return certificate(java.util.Base64.getDecoder().decode(auth.getString("identity")), auth.getString("filePassword"));
        throw invalid();
    }

    private static void add(Map<String, VoiceLogin> map, VoiceProfile profile, VoiceLogin login) {
        if (login == null) return;
        if (profile.multicast()) throw invalid();
        VoiceLogin old = map.putIfAbsent(endpoint(profile), login);
        if (old != null && (!old.username.equals(login.username) || !old.password.equals(login.password) || !old.mode.equals(login.mode) || !old.identity.equals(login.identity) || !old.profileId.equals(login.profileId))) throw invalid();
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
