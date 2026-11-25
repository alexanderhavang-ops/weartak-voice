package se.takcampus.voice;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class VoiceSetupPathsTest {
    private static final VoiceProfile MUMBLE = new VoiceProfile(UUID.randomUUID(), "Lokal trafik",
            VoiceProfile.Mode.MUMBLE, "voice.example.org", 64738, "", 0, "", 42, "Central");
    private static final VoiceProfile UDP = new VoiceProfile(UUID.randomUUID(), "Samband UDP",
            VoiceProfile.Mode.VX_UDP, "239.192.18.1", 50000, "", 0, "", -1, "");
    private static final VoiceProfile RTP = new VoiceProfile(UUID.randomUUID(), "Samband RTP",
            VoiceProfile.Mode.VX_RTP, "239.192.18.1", 50000, "239.192.18.2", 50002, "", -1, "");

    @Test public void audioLinkAndDirectChannelsSurviveSoundFraming() throws Exception {
        String url = "https://voice.example.org/setup/weekly.zip?ticket=example";
        assertEquals(url, AudioSetupPayload.zipUri(audioRoundTrip(url)).toString());
        for (VoiceProfile channel : List.of(MUMBLE, UDP, RTP)) {
            String code = AudioSetupPayload.encodeChannel(channel);
            assertEquals(code, audioRoundTrip(code));
            VoiceProfile parsed = AudioSetupPayload.channel(code);
            assertEquals(channel.mode(), parsed.mode());
            assertEquals(channel.endpoint(), parsed.endpoint());
            assertEquals(channel.label(), parsed.label());
            assertEquals(channel.roomId(), parsed.roomId());
            assertEquals(channel.roomName(), parsed.roomName());
            assertEquals(channel.dataHost(), parsed.dataHost());
            assertEquals(channel.dataPort(), parsed.dataPort());
        }
    }

    @Test public void vxZipAndBlePreserveTheSameSettings() throws Exception {
        for (VoiceProfile profile : List.of(MUMBLE, UDP, RTP)) {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            VxPackage.write(archive, profile);
            byte[] json = VoicePackageImporter.jsonFromZip(archive.toByteArray());
            byte[] handoff = VoiceHandoff.decode(VoiceHandoff.encode(json, UUID.randomUUID().toString())).missionJson;
            assertEquals(profile, VoicePackageImporter.fromJson(handoff).channels.get(0).profile());
        }
    }

    @Test public void rejectsCredentialsAndUnexpectedAudioFields() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AudioSetupPayload.zipUri("http://voice.example.org/file.zip"));
        assertThrows(IllegalArgumentException.class, () -> AudioSetupPayload.zipUri("https://user:secret@voice.example.org/file.zip"));
        String direct = AudioSetupPayload.encodeChannel(MUMBLE);
        assertThrows(IllegalArgumentException.class, () -> AudioSetupPayload.channel(direct + "{}"));
        assertThrows(IllegalArgumentException.class, () -> AudioSetupPayload.channel(direct.substring(0, direct.length() - 1) + ",\"secret\"]"));
    }

    @Test public void phoneLoginChoiceSurvivesZipAndBleAndIsScopedToEndpoint() throws Exception {
        VoiceProfile other = new VoiceProfile(UUID.randomUUID(), "Other", VoiceProfile.Mode.MUMBLE,
                "other.example.org", 64738, "", 0, "", 17, "Elsewhere");
        byte[] base = VoicePackageImporter.portableJson(List.of(MUMBLE, other, UDP), "Two destinations");
        base = VoicePackageImporter.setLogin(base, other, new VoiceLogin("other-user", "other-pass"));
        for (VoiceLogin login : List.of(VoiceLogin.enroll(UUID.randomUUID().toString()), VoiceLogin.enroll(""),
                VoiceLogin.server(""), VoiceLogin.server("shared-pass"), new VoiceLogin("member", "account-pass"),
                VoiceLogin.certificate(new byte[]{1, 2, 3}, "file-pass"))) {
            base = VoicePackageImporter.setLogin(base, MUMBLE, login);
            byte[] zip = VoiceHandoff.packageForTransfer(base);
            VoiceMission mission = VoicePackageImporter.fromJson(VoiceHandoff.decode(
                    VoiceHandoff.encode(zip, UUID.randomUUID().toString())).missionJson);
            VoiceLogin actual = mission.login(MUMBLE);
            assertEquals(login.mode, actual.mode);
            assertEquals(login.profileId, actual.profileId);
            assertEquals(login.identity, actual.identity);
            assertEquals(login.username, actual.username);
            assertEquals(login.password, actual.password);
            assertEquals("other-pass", mission.login(other).password);
            assertNull(mission.login(UDP));
        }
    }

    @Test public void malformedOrMixedAuthIsRejected() throws Exception {
        for (String malformed : List.of("{\"mode\":\"enroll\",\"profileId\":\"invalid\"}",
                "{\"mode\":\"p12\",\"identity\":\"!\",\"filePassword\":\"\"}", "{\"mode\":\"unknown\"}")) {
            org.json.JSONObject obj = new org.json.JSONObject().put("voiceAuth", new org.json.JSONObject(malformed));
            assertEquals("Invalid Mumble credentials", assertThrows(IllegalArgumentException.class,
                    () -> VoiceLogin.take(obj)).getMessage());
        }
        org.json.JSONObject mixed = new org.json.JSONObject().put("password", "existing")
                .put("voiceAuth", new org.json.JSONObject().put("mode", "server"));
        assertThrows(IllegalArgumentException.class, () -> VoiceLogin.take(mixed));
    }

    private static String audioRoundTrip(String value) {
        AtomicReference<String> decoded = new AtomicReference<>();
        short[] samples = AudioLinkCodec.encode(value);
        AudioLinkCodec.Decoder decoder = new AudioLinkCodec.Decoder(decoded::set);
        for (int i = 0; i < samples.length; i += 731) {
            short[] chunk = java.util.Arrays.copyOfRange(samples, i, Math.min(samples.length, i + 731));
            decoder.feed(chunk, chunk.length);
        }
        return decoded.get();
    }
}
