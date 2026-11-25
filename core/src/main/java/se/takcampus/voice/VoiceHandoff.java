package se.takcampus.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Configuration transport over the existing bonded Companion link, not an audio relay. */
public final class VoiceHandoff {
    public static final String TYPE = "voice_channels_v1";
    public static final String ACTION = "se.takcampus.voice.STAGE_CHANNELS";
    public static final String WATCH_PACKAGE = "se.takcampus.voice.watch";
    public static final int MAX_ENVELOPE = 49152;
    public final String transferId, sha256;
    public final byte[] missionJson;

    private VoiceHandoff(String id, String hash, byte[] data) {
        transferId = id; sha256 = hash; missionJson = data;
    }
    /** Keep credential-bearing missions together as an actual ZIP inside the v1 transport. */
    public static byte[] packageForTransfer(byte[] missionJson) throws Exception {
        VoiceMission mission = VoicePackageImporter.fromJson(missionJson);
        if (!mission.hasLogins()) return missionJson;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            writeEntry(zip, "MANIFEST/manifest.xml", ("<MissionPackageManifest version=\"2\"><Contents>" +
                    "<Content zipEntry=\"voice.json\"/></Contents></MissionPackageManifest>").getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "voice.json", missionJson);
        }
        byte[] payload = new JSONObject().put("voiceZip", java.util.Base64.getEncoder().encodeToString(output.toByteArray()))
                .toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > VoicePackageImporter.MAX_JSON) throw new IllegalArgumentException("Package too large for transfer");
        return payload;
    }

    private static void writeEntry(java.util.zip.ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        entry.setTime(315532800000L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    public static byte[] missionFromPayload(byte[] payload) throws Exception {
        if (payload.length == 0 || payload.length > VoicePackageImporter.MAX_JSON) throw new IllegalArgumentException("Payload size");
        String text = new String(payload, StandardCharsets.UTF_8);
        VoicePackageImporter.validateDepth(text);
        org.json.JSONTokener tokener = new org.json.JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Invalid package");
        JSONObject obj = (JSONObject) value;
        if (keys(obj).equals(keys("voiceZip"))) {
            return VoicePackageImporter.jsonFromZip(java.util.Base64.getDecoder().decode(obj.getString("voiceZip")));
        }
        VoicePackageImporter.fromJson(payload);
        return payload;
    }

    public static String encode(byte[] missionJson, String transferId) throws Exception {
        missionFromPayload(missionJson);
        UUID.fromString(transferId);
        String envelope = new JSONObject().put("type", TYPE).put("version", 1)
            .put("transferId", transferId).put("sha256", hash(missionJson))
            .put("missionJson", new String(missionJson, StandardCharsets.UTF_8)).toString();
        if (envelope.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE) throw new IllegalArgumentException("Envelope size");
        return envelope;
    }
    public static VoiceHandoff decode(String envelope) throws Exception {
        if (envelope.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE) throw new IllegalArgumentException("Envelope size");
        VoicePackageImporter.validateDepth(envelope);
        org.json.JSONTokener tokener = new org.json.JSONTokener(envelope);
        Object parsed = tokener.nextValue();
        if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Handoff JSON");
        JSONObject obj = (JSONObject) parsed;
        if (!keys(obj).equals(keys("type", "version", "transferId", "sha256", "missionJson")) ||
            !TYPE.equals(obj.getString("type")) || !Integer.valueOf(1).equals(obj.get("version")))
            throw new IllegalArgumentException("Unsupported handoff");
        String id = obj.getString("transferId");
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Transfer ID");
        byte[] data = obj.getString("missionJson").getBytes(StandardCharsets.UTF_8);
        String digest = hash(data);
        if (!digest.equals(obj.getString("sha256"))) throw new IllegalArgumentException("Digest mismatch");
        return new VoiceHandoff(id, digest, missionFromPayload(data));
    }
    public static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static Set<String> keys(String... names) { return new java.util.HashSet<>(java.util.Arrays.asList(names)); }

    private static Set<String> keys(JSONObject obj) {
        Set<String> result = new java.util.HashSet<>();
        java.util.Iterator<String> iterator = obj.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

}
