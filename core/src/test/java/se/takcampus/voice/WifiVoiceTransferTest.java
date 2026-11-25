package se.takcampus.voice;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class WifiVoiceTransferTest {
    @ClassRule public static TemporaryFolder temporary = new TemporaryFolder();
    private static final String CODE = "00124567";
    private static final String ENVELOPE = "{\"missionJson\":\"Göteborg\"}";
    private static Identity phone, watch, substitute;

    @BeforeClass public static void keys() throws Exception {
        phone = identity("phone");
        watch = identity("watch");
        substitute = identity("other");
    }

    @Test public void correctCodeAndCertificatesTransferExactUtf8Envelope() throws Exception {
        byte[] mission;
        try (var fixture = getClass().getResourceAsStream("/fixtures/takctl.json")) {
            assertNotNull(fixture);
            mission = fixture.readAllBytes();
        }
        String transferId = java.util.UUID.randomUUID().toString();
        String envelope = VoiceHandoff.encode(mission, transferId);
        AtomicReference<VoiceHandoff> delivered = new AtomicReference<>();
        try (Session session = new Session(watch, payload -> {
            try {
                delivered.set(VoiceHandoff.decode(payload));
                return WifiVoiceTransfer.Receipt.STAGED;
            } catch (Exception invalid) {
                return WifiVoiceTransfer.Receipt.REJECTED;
            }
        })) {
            assertEquals(WifiVoiceTransfer.Receipt.STAGED, session.send(phone, CODE, envelope));
            assertEquals(WifiVoiceTransfer.Receipt.STAGED, session.receipt());
            assertEquals(transferId, delivered.get().transferId);
            assertArrayEquals(mission, delivered.get().missionJson);
        }
    }

    @Test public void incorrectCodeNeverReachesReceiver() throws Exception {
        assertBlocked(watch, phone, "00124568");
    }

    @Test public void substitutedWatchCertificateNeverReachesReceiver() throws Exception {
        assertBlocked(new Identity(substitute.key, watch.cert), phone, CODE);
    }

    @Test public void substitutedPhoneCertificateNeverReachesReceiver() throws Exception {
        assertBlocked(watch, new Identity(substitute.key, phone.cert), CODE);
    }

    @Test public void receivesExactStagingResult() throws Exception {
        for (WifiVoiceTransfer.Receipt receipt : WifiVoiceTransfer.Receipt.values()) {
            try (Session session = new Session(watch, message -> receipt)) {
                assertEquals(receipt, session.send(phone, CODE, ENVELOPE));
                assertEquals(receipt, session.receipt());
            }
        }
    }

    @Test public void oversizePayloadIsRejectedBeforePairing() {
        assertThrows(IOException.class, () -> WifiVoiceTransfer.send(new Socket(), CODE, phone.key, phone.cert,
                "a".repeat(WifiVoiceTransfer.MAX_ENVELOPE + 1)));
    }

    private static void assertBlocked(Identity advertisedWatch, Identity advertisedPhone, String code) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Session session = new Session(advertisedWatch, message -> {
            calls.incrementAndGet();
            return WifiVoiceTransfer.Receipt.STAGED;
        })) {
            assertThrows(Exception.class, () -> session.send(advertisedPhone, code, ENVELOPE));
            assertThrows(ExecutionException.class, session::receipt);
            assertEquals(0, calls.get());
        }
    }

    private static Identity identity(String alias) throws Exception {
        Path store = temporary.getRoot().toPath().resolve(alias + ".p12");
        char[] password = "local-test-only".toCharArray();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(password), "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "5", "-dname", "CN=" + alias).redirectErrorStream(true).start();
        byte[] log = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(new String(log, java.nio.charset.StandardCharsets.UTF_8), 0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, password); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, password);
        return new Identity(factory.getKeyManagers()[0], (X509Certificate) keys.getCertificate(alias));
    }

    private static final class Identity {
        final KeyManager key;
        final X509Certificate cert;
        Identity(KeyManager key, X509Certificate cert) { this.key = key; this.cert = cert; }
    }

    private static final class Session implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<WifiVoiceTransfer.Receipt> receiver;

        Session(Identity identity, WifiVoiceTransfer.Receiver handler) throws IOException {
            listener.setSoTimeout(WifiVoiceTransfer.SOCKET_TIMEOUT_MS);
            receiver = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    return WifiVoiceTransfer.receive(socket, CODE, identity.key, identity.cert, handler);
                }
            });
        }

        WifiVoiceTransfer.Receipt send(Identity identity, String code, String envelope)
                throws IOException, GeneralSecurityException {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                return WifiVoiceTransfer.send(socket, code, identity.key, identity.cert, envelope);
            }
        }

        WifiVoiceTransfer.Receipt receipt() throws Exception { return receiver.get(10, TimeUnit.SECONDS); }

        @Override public void close() throws IOException {
            listener.close();
            executor.shutdownNow();
        }
    }
}
