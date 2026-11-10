package se.takcampus.voice;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** One connection, no automatic reconnect/replay. TLS hostname verification is mandatory. */
public final class MumbleConnection implements Closeable {
    public enum State { CONNECTING, AUTHENTICATING, CONNECTED, JOINING, READY, CLOSED, ERROR }
    public static final class Channel {
        public final int id, parent;
        public final String name;
        Channel(int id, int parent, String name) { this.id = id; this.parent = parent; this.name = name; }
    }
    public interface Listener {
        void state(State state, String detail);
        void channels(List<Channel> channels);
        void voice(int session, String name, MumbleAudioPacket packet);
    }
    private static final class Pending {
        final int type; final byte[] body; final long epoch, expires;
        Pending(int type, byte[] body, long epoch, long expires) { this.type = type; this.body = body; this.epoch = epoch; this.expires = expires; }
    }
    private final Listener listener;
    private final SSLContext tls;
    private final String host, username;
    private final int port;
    private String password;
    private final Map<Integer, Channel> channels = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, Integer> memberships = new HashMap<>();
    private final Map<Integer, Integer> restrictions = new HashMap<>();
    private final ArrayBlockingQueue<Pending> output = new ArrayBlockingQueue<>(48);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Object voiceLock = new Object();
    private volatile State state = State.CLOSED;
    private volatile boolean closed, blocked;
    private volatile Socket socket;
    private volatile int self = -1, joined = -1, requested = -1;
    private long epoch, sequence;
    private boolean transmitting;
    private Thread reader, writer;

    public MumbleConnection(String host, int port, String username, String password, SSLContext tls, Listener listener) {
        if (host == null || host.isEmpty() || host.contains("/") || port < 1 || port > 65535 || username == null || username.trim().isEmpty() || username.length() > 128)
            throw new IllegalArgumentException("Invalid server or username");
        this.host = host; this.port = port; this.username = username;
        this.password = password == null ? "" : password; this.tls = tls; this.listener = listener;
    }
    public synchronized void start() {
        if (reader != null) throw new IllegalStateException("Connection already used");
        update(State.CONNECTING, "Connecting securely");
        reader = new Thread(this::run, "mumble-read"); reader.start();
    }
    public State state() { return state; }
    public boolean canTransmit() { return state == State.READY && !closed && !blocked; }
    public int joinedChannel() { return joined; }
    public synchronized List<Channel> channels() {
        List<Channel> result = new ArrayList<>(channels.values());
        result.sort(Comparator.comparing(c -> c.name)); return result;
    }
    private void run() {
        try {
            Socket raw = new Socket(); socket = raw;
            if (closed) { raw.close(); return; }
            raw.connect(new InetSocketAddress(host, port), 10000);
            SSLSocket secured = (SSLSocket) tls.getSocketFactory().createSocket(raw, host, port, true);
            socket = secured;
            if (closed) { secured.close(); return; }
            secured.setSoTimeout(10000); secured.setTcpNoDelay(true);
            SSLParameters parameters = secured.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secured.setSSLParameters(parameters); secured.startHandshake();
            secured.setSoTimeout(35000);
            update(State.AUTHENTICATING, "Authenticating");
            writer = new Thread(() -> writeLoop(secured), "mumble-write"); writer.start();
            control(0, MumbleWire.join(MumbleWire.integer(1, 0x10300), MumbleWire.text(2, "WearTAK Voice 0.2"), MumbleWire.text(3, "Wear OS")));
            control(2, MumbleWire.join(MumbleWire.text(1, username), MumbleWire.text(2, password), MumbleWire.integer(5, 1)));
            password = "";
            timer.scheduleAtFixedRate(() -> { try { control(3, MumbleWire.integer(1, System.nanoTime() / 1000)); } catch (Exception e) { fail("Connection lost"); } }, 5, 5, TimeUnit.SECONDS);
            timer.schedule(() -> { if (self < 0 && !closed) fail("Login timed out"); }, 15, TimeUnit.SECONDS);
            while (!closed) handle(MumbleWire.read(secured.getInputStream()));
        } catch (SSLException e) { fail("TLS failed: check server hostname, trust and client certificate"); }
        catch (Exception e) { if (!closed) fail("Connection failed or closed by server"); }
        finally { finish(); }
    }
    private void writeLoop(SSLSocket secured) {
        try {
            while (!closed) {
                Pending p = output.poll(1, TimeUnit.SECONDS);
                if (p == null) continue;
                synchronized (voiceLock) {
                    if (p.epoch >= 0 && (!transmitting || p.epoch != epoch || p.expires < System.nanoTime())) continue;
                }
                MumbleWire.write(secured.getOutputStream(), p.type, p.body);
            }
        } catch (Exception e) { if (!closed) fail("Connection lost"); }
    }
    private void control(int type, byte[] body) throws IOException {
        if (closed || !output.offer(new Pending(type, body, -1, Long.MAX_VALUE))) throw new IOException("Connection output unavailable");
    }
    private synchronized void handle(MumbleWire.Frame frame) throws IOException {
        if (frame.type == 1) {
            try {
                MumbleAudioPacket packet = MumbleAudioPacket.incoming(frame.body);
                if (packet.session != self) listener.voice((int) packet.session, names.containsKey((int) packet.session) ? names.get((int) packet.session) : "Voice", packet);
            } catch (IOException ignored) { /* Non-Opus datagrams, including pings, are not decoded. */ }
            return;
        }
        MumbleWire.Fields f = new MumbleWire.Fields(frame.body);
        switch (frame.type) {
            case 4:
                int reason = f.has(1) ? f.id(1) : 0;
                fail(reason == 3 || reason == 4 ? "Login rejected: password or identity" : reason == 5 ? "Username already in use" : reason == 7 ? "Server requires a client certificate" : "Login rejected by server (code " + reason + ")"); break;
            case 5:
                self = f.id(1);
                joined = memberships.containsKey(self) ? memberships.get(self) : -1;
                blocked = restrictions.containsKey(self) && restrictions.get(self) != 0;
                update(State.CONNECTED, "Connected — choose a channel"); break;
            case 6:
                int removed = f.id(1); channels.remove(removed);
                if (removed == joined || removed == requested) { endTransmission(); joined = -1; requested = -1; update(State.CONNECTED, "Channel removed — choose another"); }
                listener.channels(channels()); break;
            case 7:
                int id = f.id(1);
                Channel old = channels.get(id);
                if (channels.size() >= 4096 && old == null) throw new IOException("Channel limit");
                channels.put(id, new Channel(id, f.has(2) ? f.id(2) : old != null ? old.parent : 0,
                    f.has(3) ? bounded(f.text(3)) : old != null ? old.name : "Channel " + id));
                listener.channels(channels()); break;
            case 8:
                int removedUser = f.id(1); names.remove(removedUser); memberships.remove(removedUser); restrictions.remove(removedUser);
                if (removedUser == self) fail("Disconnected by server"); break;
            case 9:
                int session = f.id(1);
                if (names.size() >= 8192 && !names.containsKey(session)) throw new IOException("User limit");
                if (!names.containsKey(session)) names.put(session, "Voice");
                if (f.has(3)) names.put(session, bounded(f.text(3)));
                int mask = restrictions.containsKey(session) ? restrictions.get(session) : 0;
                for (int field = 6; field <= 10; field++) if (f.has(field)) {
                    if (f.integer(field) != 0) mask |= 1 << (field - 6); else mask &= ~(1 << (field - 6));
                }
                restrictions.put(session, mask);
                if (f.has(5)) memberships.put(session, f.id(5));
                if (session == self) {
                    boolean wasBlocked = blocked;
                    blocked = mask != 0;
                    if (wasBlocked && !blocked) update(state, channelLabel(joined));
                    if (blocked) { endTransmission(); update(state, "Server has muted or restricted your voice"); }
                    if (f.has(5)) {
                        int actual = f.id(5);
                        if (actual != joined) endTransmission();
                        joined = actual;
                        if (requested == actual) { requested = -1; update(State.READY, channelLabel(actual)); }
                        else if (state == State.READY) update(State.CONNECTED, "Channel changed — confirm selection");
                    }
                }
                break;
            case 12:
                endTransmission(); requested = -1;
                update(State.CONNECTED, "Server denied the channel/action"); break;
            case 21:
                if (f.has(4) && f.integer(4) == 0) fail("Server does not support Opus voice"); break;
            default: break;
        }
    }
    private static String bounded(String s) {
        s = s.replaceAll("[\\p{Cntrl}]", " "); return s.length() > 128 ? s.substring(0, 128) : s;
    }
    private String channelLabel(int id) { Channel c = channels.get(id); return c == null ? "Channel " + id : c.name; }
    public synchronized void join(int id, String expectedName) throws IOException {
        if (closed || self < 0) throw new IOException("Not connected");
        Channel channel = channels.get(id);
        if (channel == null || (expectedName != null && !expectedName.equals(channel.name))) throw new IOException("Saved channel does not match the live server");
        endTransmission();
        if (joined == id) { requested = -1; update(State.READY, channel.name); return; }
        requested = id; update(State.JOINING, "Joining " + channel.name);
        control(9, MumbleWire.join(MumbleWire.integer(1, self), MumbleWire.integer(5, id)));
        timer.schedule(() -> {
            synchronized (MumbleConnection.this) {
                if (!closed && requested == id) { requested = -1; update(State.CONNECTED, "Channel join timed out"); }
            }
        }, 8, TimeUnit.SECONDS);
    }
    public long beginTransmission() throws IOException {
        synchronized (voiceLock) {
            if (!canTransmit()) throw new IOException("Channel is not ready for transmission");
            if (!transmitting) { transmitting = true; epoch++; }
            return epoch;
        }
    }
    public void sendOpus(long token, byte[] opus) throws IOException {
        int duration = MumbleAudioPacket.duration10ms(opus);
        synchronized (voiceLock) {
            if (!transmitting || token != epoch || !canTransmit()) return;
            byte[] packet = MumbleAudioPacket.outgoing(sequence, opus, false); sequence += duration;
            // Bounded and expiring; stale microphone audio must never become a backlog.
            output.offer(new Pending(1, packet, epoch, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));
        }
    }
    public void endTransmission() {
        synchronized (voiceLock) {
            if (!transmitting) return;
            transmitting = false; epoch++;
            output.removeIf(p -> p.epoch >= 0);
            if (!closed) {
                try { control(1, MumbleAudioPacket.outgoing(sequence, new byte[0], true)); }
                catch (IOException ignored) { /* close will discard the remaining queue */ }
            }
        }
    }
    private void update(State next, String detail) { if (!closed) { state = next; listener.state(next, detail); } }
    private void fail(String detail) { if (!closed) { state = State.ERROR; listener.state(State.ERROR, detail); finish(); } }
    private void finish() {
        closed = true; password = "";
        synchronized (voiceLock) { transmitting = false; epoch++; output.clear(); }
        timer.shutdownNow();
        Socket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) {}
        if (writer != null) writer.interrupt();
    }
    @Override public void close() {
        if (!closed) { endTransmission(); state = State.CLOSED; listener.state(State.CLOSED, "Disconnected"); }
        finish();
    }
}
