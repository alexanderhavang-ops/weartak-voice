package se.takcampus.voice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

public class OnboardingTest {
    private byte[] fixture(String name) throws Exception {
        try (java.io.InputStream input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing test fixture: " + name);
            return input.readAllBytes();
        }
    }
    private byte[] json() throws Exception { return fixture("takctl.json"); }
    private void rejected(byte[] bytes) throws Exception {
        assertThrows(Exception.class, () -> VoicePackageImporter.fromJson(bytes));
    }
    private void verify(VoiceMission mission) {
        assertEquals("Samband-test", mission.name);
        assertEquals(2, mission.channels.size());
        assertEquals("GruppL-Ö", mission.channels.get(0).name);
        assertEquals("voice.example.org", mission.channels.get(0).host);
        assertEquals(64738, mission.channels.get(0).port);
        assertEquals(17, mission.channels.get(0).serverChannelId);
        assertEquals(93, mission.channels.get(1).serverChannelId);
    }
    @Test public void takctlPackage() throws Exception { verify(VoicePackageImporter.fromZip(fixture("takctl.zip"))); }
    @Test public void martinePackage() throws Exception { verify(VoicePackageImporter.fromZip(fixture("martine.zip"))); }
    @Test public void takctlBinaryPackage() throws Exception { verify(VoicePackageImporter.fromZip(fixture("takctl-binary.zip"))); }
    @Test public void martineBinaryPackage() throws Exception { verify(VoicePackageImporter.fromZip(fixture("martine-binary.zip"))); }
    @Test public void bothBinaryEncodings() throws Exception {
        for (String kind : new String[]{"takctl", "martine"}) verify(VoicePackageImporter.fromJson(VxProtoImporter.toJson(fixture(kind + ".proto"))));
    }
    @Test public void handoffRetainsExactBytesAndIdentity() throws Exception {
        String id = UUID.randomUUID().toString();
        VoiceHandoff received = VoiceHandoff.decode(VoiceHandoff.encode(json(), id));
        assertEquals(id, received.transferId);
        assertArrayEquals(json(), received.missionJson);
        verify(VoicePackageImporter.fromJson(received.missionJson));
    }
    @Test public void handoffRejectsTamper() throws Exception {
        JSONObject envelope = new JSONObject(VoiceHandoff.encode(json(), UUID.randomUUID().toString()));
        envelope.put("sha256", "0".repeat(64));
        assertThrows(Exception.class, () -> VoiceHandoff.decode(envelope.toString()));
    }
    @Test public void handoffRejectsVersionAndSecrets() throws Exception {
        JSONObject envelope = new JSONObject(VoiceHandoff.encode(json(), UUID.randomUUID().toString()));
        envelope.put("password", "test-only");
        assertThrows(Exception.class, () -> VoiceHandoff.decode(envelope.toString()));
        envelope.remove("password"); envelope.put("version", 2);
        assertThrows(Exception.class, () -> VoiceHandoff.decode(envelope.toString()));
    }
    @Test public void unknownMissionFieldsRejected() throws Exception { rejected(new JSONObject(new String(json(), StandardCharsets.UTF_8)).put("unknownSetting", "test-only").toString().getBytes(StandardCharsets.UTF_8)); }
    @Test public void unknownChannelFieldsRejected() throws Exception {
        JSONObject obj = new JSONObject(new String(json(), StandardCharsets.UTF_8));
        obj.getJSONArray("channels").getJSONObject(0).put("unknownSetting", "test-only");
        rejected(obj.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test public void credentialsTravelInZipAndSurviveHandoff() throws Exception {
        for (String username : new String[]{"", "radio-user"}) {
            JSONObject original = new JSONObject(new String(json(), StandardCharsets.UTF_8));
            if (username.isEmpty()) original.put("mumblePassword", "shared-test");
            else original.put("username", username).put("password", "account-test");
            byte[] mission = original.toString().getBytes(StandardCharsets.UTF_8);
            byte[] preserved = VoicePackageImporter.withAvailableLogins(mission, p -> {
                throw new AssertionError("Packaged login must take priority");
            });
            byte[] outgoing = VoiceHandoff.packageForTransfer(preserved);
            JSONObject container = new JSONObject(new String(outgoing, StandardCharsets.UTF_8));
            byte[] zip = java.util.Base64.getDecoder().decode(container.getString("voiceZip"));
            assertTrue(VoicePackageImporter.fromZip(zip).hasLogins());
            VoiceHandoff delivered = VoiceHandoff.decode(VoiceHandoff.encode(outgoing, UUID.randomUUID().toString()));
            VoiceMission parsed = VoicePackageImporter.fromJson(delivered.missionJson);
            for (VoiceMission.Channel channel : parsed.channels) {
                VoiceLogin login = parsed.login(channel.profile());
                assertEquals(username, login.username);
                assertEquals(username.isEmpty() ? "shared-test" : "account-test", login.password);
            }
        }
    }

    @Test public void companionCanAddMissingLoginOnlyToMatchingEndpoint() throws Exception {
        JSONObject source = new JSONObject(new String(json(), StandardCharsets.UTF_8));
        source.getJSONArray("channels").getJSONObject(1).put("host", "other.example.org:64738");
        byte[] data = source.toString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, VoicePackageImporter.withAvailableLogins(data, p -> null));
        byte[] enriched = VoicePackageImporter.withAvailableLogins(data, p -> p.host().equals("voice.example.org")
                ? new VoiceLogin("watch-test", "account-test") : null);
        VoiceMission mission = VoicePackageImporter.fromJson(VoiceHandoff.missionFromPayload(VoiceHandoff.packageForTransfer(enriched)));
        assertEquals("watch-test", mission.login(mission.channels.get(0).profile()).username);
        assertNull(mission.login(mission.channels.get(1).profile()));
        source.put("serverPassword", "ambiguous-test");
        rejected(source.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void multicastRejected() throws Exception { changedChannel("isMumble", false); }
    @Test public void negativeIdRejected() throws Exception { changedChannel("serverChannelId", -1); }
    @Test public void fractionalIdRejected() throws Exception { changedChannel("serverChannelId", 1.5); }
    @Test public void stringIdRejected() throws Exception { changedChannel("serverChannelId", "17"); }
    @Test public void userinfoRejected() throws Exception { changedChannel("host", "user@voice.example.org:64738"); }
    @Test public void pathRejected() throws Exception { changedChannel("host", "voice.example.org:64738/path"); }
    @Test public void invalidPortRejected() throws Exception { changedChannel("host", "voice.example.org:99999"); }
    @Test public void duplicateIdRejected() throws Exception {
        JSONObject obj = new JSONObject(new String(json(), StandardCharsets.UTF_8));
        obj.getJSONArray("channels").getJSONObject(1).put("id", obj.getJSONArray("channels").getJSONObject(0).getString("id"));
        rejected(obj.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void changedChannel(String key, Object value) throws Exception {
        JSONObject obj = new JSONObject(new String(json(), StandardCharsets.UTF_8));
        obj.getJSONArray("channels").getJSONObject(0).put(key, value);
        rejected(obj.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test public void oversizedInputRejected() { assertThrows(Exception.class, () -> VoicePackageImporter.fromZip(new byte[VoicePackageImporter.MAX_ZIP + 1])); }
    @Test public void malformedProtoRejected() {
        for (byte[] bad : new byte[][]{{0}, {10, 100}, {(byte) 128}, {11, 1}})
            assertThrows(Exception.class, () -> VxProtoImporter.toJson(bad));
    }
    @Test public void traversalRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("../voice")); zip.write(json()); }
        assertThrows(Exception.class, () -> VoicePackageImporter.fromZip(bytes.toByteArray()));
    }
    @Test public void zipBombRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("large")); zip.write(new byte[VoicePackageImporter.MAX_EXPANDED + 1]); }
        assertThrows(Exception.class, () -> VoicePackageImporter.fromZip(bytes.toByteArray()));
    }
    @Test public void rootChannelZeroPreserved() throws Exception {
        JSONObject obj = new JSONObject(new String(json(), StandardCharsets.UTF_8));
        obj.getJSONArray("channels").getJSONObject(0).put("serverChannelId", 0);
        assertEquals(0, VoicePackageImporter.fromJson(obj.toString().getBytes(StandardCharsets.UTF_8)).channels.get(0).serverChannelId);
    }
    @Test public void trailingJsonRejected() throws Exception {
        rejected((new String(json(), StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8));
    }
    @Test public void deepJsonRejected() throws Exception { rejected(("[".repeat(1000) + "0" + "]".repeat(1000)).getBytes(StandardCharsets.UTF_8)); }
    @Test public void conflictingBinaryAndJsonRejected() throws Exception {
        byte[] changed = new JSONObject(new String(json(), StandardCharsets.UTF_8)).put("name", "Other mission").toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream source = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fixture("takctl.zip")));
             ZipOutputStream target = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                target.putNextEntry(new ZipEntry(entry.getName()));
                target.write(entry.getName().equals("MANIFEST/manifest.xml") || entry.getName().endsWith("_proto") ? source.readAllBytes() : changed);
                target.closeEntry();
            }
        }
        assertThrows(Exception.class, () -> VoicePackageImporter.fromZip(output.toByteArray()));
    }
}
