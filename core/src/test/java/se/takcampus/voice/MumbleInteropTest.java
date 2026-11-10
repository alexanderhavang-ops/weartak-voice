package se.takcampus.voice;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Opt-in tests against an actual isolated Mumble server; no production node is used. */
public class MumbleInteropTest {
    private Path directory;
    @Before public void configured() { Assume.assumeTrue(System.getProperty("mumble.test.dir") != null); directory = Path.of(System.getProperty("mumble.test.dir")); }
    private SSLContext tls(boolean identity) throws Exception {
        KeyStore anchors = KeyStore.getInstance("PKCS12"); anchors.load(null);
        anchors.setCertificateEntry("test-ca", CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(directory.resolve("ca.pem"))));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(anchors);
        KeyManager[] keys = null;
        if (identity) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(directory.resolve("client.p12"))) { store.load(in, "test-only".toCharArray()); }
            KeyManagerFactory key = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); key.init(store,"test-only".toCharArray()); keys = key.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys,trust.getTrustManagers(),null); return context;
    }
    private static class Probe implements MumbleConnection.Listener {
        volatile MumbleConnection.State state;
        volatile String detail;
        final BlockingQueue<MumbleAudioPacket> voice = new LinkedBlockingQueue<>();
        public void state(MumbleConnection.State state,String detail) { this.state=state;this.detail=detail; }
        public void channels(List<MumbleConnection.Channel> channels) {}
        public void voice(int session,String name,MumbleAudioPacket packet) { voice.offer(packet); }
        void await(MumbleConnection.State expected) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (state!=expected && System.nanoTime()<until) { if (state==MumbleConnection.State.ERROR && expected!=state) fail(detail); Thread.sleep(10); }
            assertEquals(detail,expected,state);
        }
    }
    private MumbleConnection client(String name,String password,SSLContext context,Probe listener) {
        return new MumbleConnection("localhost",Integer.getInteger("mumble.test.port",64739),name,password,context,listener);
    }
    private String guest() { return "watch-"+Long.toHexString(System.nanoTime()); }
    @Test public void passwordLoginJoinAndTransmitRelease() throws Exception {
        Probe a = new Probe(), b = new Probe();
        try (MumbleConnection sender=client(guest(),"voice-test-password",tls(false),a);
             MumbleConnection receiver=client(guest(),"voice-test-password",tls(false),b)) {
            sender.start(); receiver.start(); a.await(MumbleConnection.State.CONNECTED); b.await(MumbleConnection.State.CONNECTED);
            assertTrue(sender.channels().stream().anyMatch(c->c.id==17 && c.name.equals("Watch Test")));
            assertFalse(sender.canTransmit()); assertThrows(IOException.class,sender::beginTransmission);
            sender.join(17,"Watch Test"); receiver.join(17,"Watch Test");
            a.await(MumbleConnection.State.READY); b.await(MumbleConnection.State.READY);
            long token=sender.beginTransmission();
            sender.sendOpus(token,new byte[]{(byte)0xf8,-1,-2});
            MumbleAudioPacket audio=b.voice.poll(5,TimeUnit.SECONDS);
            assertNotNull("Actual server did not relay Opus",audio); assertArrayEquals(new byte[]{(byte)0xf8,-1,-2},audio.opus);
            sender.endTransmission();
            MumbleAudioPacket end=b.voice.poll(5,TimeUnit.SECONDS); assertNotNull(end); assertTrue(end.last);
            sender.sendOpus(token,new byte[]{(byte)0xf8,-1,-2});
            assertNull("Released PTT must reject stale capture token",b.voice.poll(250,TimeUnit.MILLISECONDS));
            long next=sender.beginTransmission(); assertNotEquals(token,next);
            sender.sendOpus(next,new byte[]{(byte)0xf8,-1,-2});
            assertNotNull(b.voice.poll(5,TimeUnit.SECONDS)); sender.endTransmission();
        }
    }
    @Test public void wrongPasswordRejected() throws Exception {
        Probe p=new Probe();
        try (MumbleConnection c=client(guest(),"wrong",tls(false),p)) { c.start();p.await(MumbleConnection.State.ERROR);assertFalse(c.canTransmit()); }
    }
    @Test public void untrustedServerCertificateRejected() throws Exception {
        Probe p=new Probe();
        try (MumbleConnection c=client(guest(),"voice-test-password",SSLContext.getDefault(),p)) { c.start();p.await(MumbleConnection.State.ERROR);assertTrue(p.detail.contains("TLS")); }
    }
    @Test public void registeredClientCertificateWorksWithoutPassword() throws Exception {
        Probe p=new Probe();
        try (MumbleConnection c=client("CertWatch","",tls(true),p)) {
            c.start();p.await(MumbleConnection.State.CONNECTED);c.join(17,"Watch Test");p.await(MumbleConnection.State.READY);
        }
    }
    @Test public void registeredIdentityRejectsMissingClientCertificate() throws Exception {
        Probe p=new Probe();
        try (MumbleConnection c=client("CertWatch","voice-test-password",tls(false),p)) { c.start();p.await(MumbleConnection.State.ERROR);assertFalse(c.canTransmit()); }
    }
    @Test public void staleChannelNameRejected() throws Exception {
        Probe p=new Probe();
        try (MumbleConnection c=client(guest(),"voice-test-password",tls(false),p)) {
            c.start();p.await(MumbleConnection.State.CONNECTED);
            assertThrows(IOException.class,()->c.join(17,"Old channel name"));assertFalse(c.canTransmit());
        }
    }
}
