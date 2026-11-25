package se.takcampus.voice;

import static se.takcampus.voice.MumbleWire.bytes;
import static se.takcampus.voice.MumbleWire.integer;
import static se.takcampus.voice.MumbleWire.join;
import static se.takcampus.voice.MumbleWire.text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Portable VX channel packages and their optional endpoint-scoped Mumble logins. */
public final class VxPackage {
    public static final String RECEIVE_ACTION = "com.atakmap.android.gbr.multicastvoice.sharing.downloaded";
    private static final String MANIFEST = "MANIFEST/manifest.xml";
    private static final int ENTRY_LIMIT = 256 * 1024;
    private static final int TOTAL_LIMIT = 524288;
    private static final int MAX_PROFILES = 96;

    private VxPackage() {}

    public static List<VoiceProfile> read(InputStream input) throws Exception {
        VoiceMission mission = VoicePackageImporter.fromJson(missionJson(input));
        List<VoiceProfile> profiles = new ArrayList<>();
        for (VoiceMission.Channel channel : mission.channels) profiles.add(channel.profile());
        return profiles;
    }

    static byte[] missionJson(InputStream input) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            Set<String> seen = new HashSet<>();
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (seen.size() >= 32 || name.length() > 180 || !seen.add(name) || name.startsWith("/")
                        || name.contains("\\") || name.contains("..")) throw new IOException("Invalid VX archive entries.");
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IOException("Unexpected data in a VX archive directory.");
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > TOTAL_LIMIT || output.size() + n > ENTRY_LIMIT) throw new IOException("VX package is too large.");
                    output.write(buffer, 0, n);
                }
                entries.put(name, output.toByteArray());
            }
        }
        byte[] manifest = entries.get(MANIFEST);
        if (manifest == null || manifest.length > 32768) throw new IOException("VX package manifest is missing or too large.");
        List<String> declared = declaredEntries(manifest);
        String proto = null, legacy = null;
        for (String path : declared) {
            if (!entries.containsKey(path)) throw new IOException("VX package is missing declared content.");
            String file = path.substring(path.lastIndexOf('/') + 1);
            if (file.endsWith("_proto")) {
                if (proto != null) throw new IOException("Multiple VX profiles in one package are ambiguous.");
                proto = path;
            } else if (file.matches("[a-fA-F0-9-]{36}(\\.json)?")) {
                if (legacy != null) throw new IOException("Multiple legacy VX profiles are ambiguous.");
                legacy = path;
            }
        }
        if (proto == null && legacy == null) throw new IOException("No VX channel profile was found.");
        JSONObject json = legacy == null ? null : new JSONObject(new String(entries.get(legacy), StandardCharsets.UTF_8));
        List<VoiceProfile> profiles = proto == null ? decodeLegacy(json) : decodeProto(entries.get(proto));
        byte[] portable = VoicePackageImporter.portableJson(profiles, "VX channels");
        if (json == null) return portable;
        VoiceLogin.Fields fields = new VoiceLogin.Fields(json);
        if (fields.common == null && fields.byId.isEmpty()) return portable;
        List<VoiceMission.Channel> declaredChannels = new ArrayList<>();
        for (VoiceProfile p : decodeLegacy(json)) declaredChannels.add(new VoiceMission.Channel(p));
        Map<String, VoiceLogin> logins = fields.resolve(declaredChannels);
        // A JSON credential may only accompany the same channel ID and Mumble endpoint in the binary profile.
        JSONObject result = new JSONObject(new String(portable, StandardCharsets.UTF_8));
        for (VoiceMission.Channel source : declaredChannels) {
            VoiceLogin login = logins.get(VoiceLogin.endpoint(source.profile()));
            if (login == null) continue;
            int index = -1;
            for (int i = 0; i < profiles.size(); i++) {
                VoiceProfile p = profiles.get(i);
                if (p.id().equals(source.profile().id()) && !p.multicast() &&
                        VoiceLogin.endpoint(p).equals(VoiceLogin.endpoint(source.profile()))) index = i;
            }
            if (index < 0) throw VoiceLogin.invalid();
            login.write(result.getJSONArray("channels").getJSONObject(index));
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> declaredEntries(byte[] manifest) throws Exception {
        String xml = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(manifest)).toString();
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("VX manifest contains a DTD.");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entity."); });
        Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        if (!root.getTagName().equals("MissionPackageManifest")) throw new IOException("Invalid ATAK package manifest.");
        NodeList parameters = root.getElementsByTagName("Parameter");
        boolean vx = false;
        for (int i = 0; i < parameters.getLength(); i++) {
            Element e = (Element) parameters.item(i);
            if (e.getAttribute("name").equals("onReceiveAction") && e.getAttribute("value").equals(RECEIVE_ACTION)) vx = true;
        }
        if (!vx) throw new IOException("Choose a VX channel package.");
        NodeList contents = root.getElementsByTagName("Content");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < contents.getLength(); i++) {
            Element e = (Element) contents.item(i);
            if (!e.getAttribute("ignore").equals("true")) paths.add(e.getAttribute("zipEntry"));
        }
        return paths;
    }

    static List<VoiceProfile> decodeProto(byte[] proto) throws IOException {
        MumbleWire.Fields mission = new MumbleWire.Fields(proto);
        List<byte[]> channelContainers = mission.messages(3);
        List<byte[]> connectionContainers = mission.messages(4);
        boolean legacyTaksLayout = channelContainers.size() > 1 || connectionContainers.size() > 1;
        if (connectionContainers.size() == 1) {
            MumbleWire.Fields connection = new MumbleWire.Fields(connectionContainers.get(0));
            legacyTaksLayout |= connection.messages(1).size() == 1 && uuidText(connection.text(1));
        }
        Map<String, MumbleWire.Fields> connections = new HashMap<>();
        for (MumbleWire.Fields entry : unwrap(connectionContainers)) {
            if (connections.put(entry.text(1), entry) != null) throw new IOException("Duplicate VX connection.");
        }
        List<VoiceProfile> profiles = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (MumbleWire.Fields channel : unwrap(channelContainers)) {
            UUID id = UUID.fromString(channel.text(1));
            if (!ids.add(id)) throw new IOException("Duplicate VX channel ID.");
            MumbleWire.Fields connection = connections.get(channel.text(4));
            if (connection == null) throw new IOException("VX channel references a missing connection.");
            String label = channel.text(2);
            String host = connection.text(2);
            int port = connection.id(3);
            if (channel.has(5) || connection.has(4)) {
                if (!channel.has(5) || channel.has(6) || connection.has(5)) throw new IOException("Conflicting VX channel types.");
                MumbleWire.Fields presence = new MumbleWire.Fields(connection.has(4) ? connection.bytes(4) : new byte[0]);
                int protocol = number(connection, 6, 1);
                if (protocol > 2) throw new IOException("Unsupported VX multicast send protocol.");
                profiles.add(new VoiceProfile(id, label, protocol == 2 ? VoiceProfile.Mode.VX_RTP : VoiceProfile.Mode.VX_UDP,
                        host, port, optional(presence, 2), number(presence, 3, 0), optional(connection, 7), -1, ""));
            } else {
                MumbleWire.Fields room = channel.has(6) ? new MumbleWire.Fields(channel.bytes(6)) : channel;
                // Historical TAKS encoders placed the Mumble room ID in the channel-number field.
                int roomId = legacyTaksLayout || !channel.has(6)
                        ? number(channel, 3, 0) : number(room, 1, 0);
                String roomName = channel.has(6) ? optional(room, 2) : label;
                profiles.add(new VoiceProfile(id, label, VoiceProfile.Mode.MUMBLE, host, port,
                        "", 0, "", roomId, roomName));
            }
        }
        return checked(profiles);
    }

    private static List<MumbleWire.Fields> unwrap(List<byte[]> containers) throws IOException {
        List<MumbleWire.Fields> entries = new ArrayList<>();
        for (byte[] raw : containers) {
            MumbleWire.Fields data = new MumbleWire.Fields(raw);
            if (data.messages(1).size() == 1 && uuidText(data.text(1))) entries.add(data);
            else for (byte[] wrapped : data.messages(1)) entries.add(new MumbleWire.Fields(wrapped));
            if (entries.size() > MAX_PROFILES) throw new IOException("Too many VX entries.");
        }
        return entries;
    }

    private static boolean uuidText(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException e) { return false; }
    }

    static List<VoiceProfile> decodeLegacy(JSONObject mission) throws Exception {
        JSONArray channels = mission.getJSONArray("channels");
        if (channels.length() > MAX_PROFILES + 1) throw new IOException("Too many VX channels.");
        String dataHost = mission.optString("missionIP", "");
        int dataPort = mission.optInt("missionPort", 0);
        if (dataPort <= 0) {
            dataHost = "";
            dataPort = 0;
        }
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel.optBoolean("isEngineering")) {
                dataHost = channel.getString("host");
                dataPort = channel.getInt("port");
                break;
            }
        }
        List<VoiceProfile> result = new ArrayList<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject c = channels.getJSONObject(i);
            if (c.optBoolean("isEngineering")) continue;
            UUID id = UUID.fromString(c.getString("id"));
            String name = c.getString("name");
            String host = c.getString("host");
            if (c.optBoolean("isMumble")) {
                int colon = host.lastIndexOf(':');
                if (colon < 1) throw new IOException("VX Mumble host is missing its port.");
                int port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
                result.add(new VoiceProfile(id, name, VoiceProfile.Mode.MUMBLE, host, port, "", 0, "",
                        c.getInt("serverChannelId"), c.optString("subtitle", name)));
            } else {
                boolean rtp = c.optString("protocol", mission.optString("missionDefaultProtocol", "UDP")).equals("RTP");
                result.add(new VoiceProfile(id, name, rtp ? VoiceProfile.Mode.VX_RTP : VoiceProfile.Mode.VX_UDP,
                        host, c.getInt("port"), dataHost, dataPort, "", -1, ""));
            }
        }
        return checked(result);
    }

    public static void write(OutputStream output, VoiceProfile p) throws Exception {
        if (!p.multicast() && p.roomId() < 0) throw new IOException("Choose a Mumble room before exporting.");
        String mission = UUID.randomUUID().toString();
        String connection = UUID.randomUUID().toString();
        String data = UUID.randomUUID().toString();
        String mode = p.mode() == VoiceProfile.Mode.VX_RTP ? "RTP" : "UDP";
        byte[] channelType = p.multicast() ? bytes(5, new byte[0])
                : bytes(6, join(integer(1, p.roomId()), text(2, p.roomName())));
        byte[] connectionType;
        if (!p.multicast()) connectionType = bytes(5, new byte[0]);
        else if (p.hasDataChannel()) connectionType = bytes(4, join(text(1, data), text(2, p.dataHost()), integer(3, p.dataPort())));
        else connectionType = bytes(4, new byte[0]);
        byte[] channel = join(text(1, p.id().toString()), text(2, p.label()), integer(3, 1), text(4, connection), channelType);
        byte[] endpoint = join(text(1, connection), text(2, p.host()), integer(3, p.port()), connectionType,
                integer(6, p.multicast() ? (mode.equals("RTP") ? 2 : 1) : 3), text(7, p.networkInterface()));
        byte[] proto = join(text(1, mission), text(2, p.label()), bytes(3, bytes(1, channel)), bytes(4, bytes(1, endpoint)));
        JSONArray channels = new JSONArray().put(new JSONObject().put("id", p.id().toString()).put("name", p.label())
                .put("host", p.multicast() ? p.host() : p.endpoint()).put("missionId", mission)
                .put("serverChannelId", p.roomId()).put("subtitle", p.roomName()).put("isMumble", !p.multicast())
                .put("isEngineering", false).put("port", p.multicast() ? p.port() : -1).put("protocol", mode));
        if (p.hasDataChannel()) {
            channels.put(new JSONObject().put("id", data).put("name", "Eng Ch").put("host", p.dataHost())
                    .put("missionId", mission).put("isMumble", false).put("isEngineering", true)
                    .put("port", p.dataPort()).put("protocol", "UDP"));
        }
        byte[] legacy = new JSONObject().put("missionId", new JSONObject().put("uuid", mission)).put("name", p.label())
                .put("missionType", "COMBINED").put("missionIP", p.dataHost())
                .put("missionPort", p.hasDataChannel() ? Integer.toString(p.dataPort()) : "-1")
                .put("missionDefaultProtocol", mode).put("channels", channels).toString().getBytes(StandardCharsets.UTF_8);
        String legacyPath = UUID.randomUUID().toString().replace("-", "") + "/" + mission;
        String protoPath = UUID.randomUUID().toString().replace("-", "") + "/" + mission + "_proto";
        String manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MissionPackageManifest version=\"2\"><Configuration>"
                + parameter("uid", UUID.randomUUID().toString()) + parameter("name", p.label())
                + parameter("onReceiveImport", "true") + parameter("onReceiveDelete", "false")
                + parameter("onReceiveAction", RECEIVE_ACTION) + "</Configuration><Contents>"
                + content(legacyPath) + content(protoPath) + "</Contents></MissionPackageManifest>";
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
            entry(zip, legacyPath, legacy);
            entry(zip, protoPath, proto);
        }
    }

    private static String content(String name) {
        return "<Content ignore=\"false\" zipEntry=\"" + name + "\">" + parameter("uid", UUID.randomUUID().toString()) + "</Content>";
    }

    private static String parameter(String name, String value) {
        String escaped = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        return "<Parameter name=\"" + name + "\" value=\"" + escaped + "\"/>";
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static List<VoiceProfile> checked(List<VoiceProfile> profiles) throws IOException {
        if (profiles.isEmpty() || profiles.size() > MAX_PROFILES) throw new IOException("VX package has no supported channels.");
        return java.util.Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    private static String optional(MumbleWire.Fields fields, int n) throws IOException { return fields.has(n) ? fields.text(n) : ""; }
    private static int number(MumbleWire.Fields fields, int n, int fallback) throws IOException { return fields.has(n) ? fields.id(n) : fallback; }
}
