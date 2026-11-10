package se.takcampus.voice;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AudioLinkTest {
    static final String LINK = "https://taklabs.example.org/api/onboarding/cards/watch-voice#" + "a".repeat(43);
    @Test public void arbitraryChunksAndSamplePhase() {
        short[] signal = AudioLinkCodec.encode(LINK);
        short[] pcm = new short[signal.length + 83]; System.arraycopy(signal, 0, pcm, 83, signal.length);
        AtomicReference<String> result = new AtomicReference<>(); AudioLinkCodec.Decoder decoder = new AudioLinkCodec.Decoder(result::set);
        for (int offset = 0; offset < pcm.length; offset += 997) decoder.feed(Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 997)), Math.min(997, pcm.length-offset));
        assertEquals(LINK, result.get());
    }
    @Test public void browserSender() throws Exception {
        String path = System.getProperty("audio.test.pcm"); org.junit.Assume.assumeNotNull(path);
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        short[] pcm = new short[bytes.length/2]; for(int i=0;i<pcm.length;i++) pcm[i]=input.getShort();
        AtomicReference<String> result=new AtomicReference<>(); new AudioLinkCodec.Decoder(result::set).feed(pcm,pcm.length);
        assertEquals(LINK,result.get());
    }
    @Test public void clockDriftAcrossFrames() {
        short[] source=AudioLinkCodec.encode(LINK);
        for (double speed : new double[]{.9998,1.0002}) {
            short[] pcm = new short[(int)(source.length/speed)];
            for(int i=0;i<pcm.length;i++) pcm[i]=source[Math.min(source.length-1,(int)(i*speed))];
            AtomicReference<String> result=new AtomicReference<>(); new AudioLinkCodec.Decoder(result::set).feed(pcm,pcm.length);
            assertEquals(result.get(),LINK);
        }
    }
    @Test public void noisyQuietAudioAndDcOffset() {
        short[] pcm = AudioLinkCodec.encode(LINK); Random random = new Random(7);
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(pcm[i] * .08 + random.nextGaussian() * 70 + 90);
        AtomicReference<String> result = new AtomicReference<>(); new AudioLinkCodec.Decoder(result::set).feed(pcm, pcm.length);
        assertEquals(LINK, result.get());
    }
    @Test public void silenceAndRandomNoiseDoNotEnroll() {
        AtomicReference<String> result = new AtomicReference<>(); AudioLinkCodec.Decoder decoder = new AudioLinkCodec.Decoder(result::set);
        short[] pcm = new short[48000]; decoder.feed(pcm, pcm.length); Random random = new Random(2);
        for (int i=0;i<pcm.length;i++) pcm[i]=(short)random.nextInt(); decoder.feed(pcm,pcm.length); assertNull(result.get());
    }
    @Test public void frameCorruptionCannotCompleteLink() {
        short[] pcm=AudioLinkCodec.encode(LINK);
        // Destroy part of one independently checksummed frame.
        Arrays.fill(pcm, 100000, 105000, (short)0);
        AtomicReference<String> result=new AtomicReference<>(); new AudioLinkCodec.Decoder(result::set).feed(pcm,pcm.length); assertNull(result.get());
    }
    @Test public void maximumLinkAndBounds() {
        String link="x".repeat(384); AtomicReference<String> result=new AtomicReference<>(); short[] pcm=AudioLinkCodec.encode(link);
        new AudioLinkCodec.Decoder(result::set).feed(pcm,pcm.length); assertEquals(link,result.get());
        assertThrows(IllegalArgumentException.class,()->AudioLinkCodec.encode("x".repeat(385)));
    }
    @Test public void httpsTicketOnlyNoRedirectOrUserinfo() {
        OnboardingLink parsed=OnboardingLink.parse(LINK); assertEquals("https://taklabs.example.org/api/onboarding/cards/watch-voice/redeem", parsed.redeemUrl().toString());
        for(String link:List.of(LINK.replace("https:","http:"),LINK.replace("example.org/","example.org:8443/"),LINK.replace("https://","https://user@"),LINK.replace("#","?secret="),LINK+"x"))
            assertThrows(IllegalArgumentException.class,()->OnboardingLink.parse(link));
    }
    @Test public void buttonRepeatFocusAndRelease() {
        PttButton button=new PttButton(); button.assign(265); assertTrue(button.down(265,0,true)); assertFalse(button.down(265,1,true));
        assertTrue(button.up(265)); assertFalse(button.up(265)); button.reset(); assertFalse(button.down(265,1,true));
        assertFalse(button.down(265,0,false)); assertTrue(button.down(265,0,true)); button.reset(); assertFalse(button.up(265));
        assertFalse(button.down(266,0,true)); assertThrows(IllegalArgumentException.class,()->button.assign(264));
    }
}
