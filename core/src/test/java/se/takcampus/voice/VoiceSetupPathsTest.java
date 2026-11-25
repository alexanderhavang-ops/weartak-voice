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
