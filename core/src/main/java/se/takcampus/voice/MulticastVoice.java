package se.takcampus.voice;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A single local group with a voice socket and an optional VX data socket. */
public final class MulticastVoice implements Closeable {
    public interface Listener {
        void audio(String source, String callsign, byte[] opus);
        void participants(List<String> callsigns);
        void failure(String message);
    }

    private record Participant(String address, String callsign, long seen) {}
    private final VoiceProfile profile;
    private final Listener listener;
    private final String uid;
    private final String callsign;
    private final InetAddress audioGroup;
    private final InetAddress dataGroup;
    private final NetworkInterface network;
    private final String localAddress;
    private final MulticastSocket audioSocket;
    private final MulticastSocket dataSocket;
    private final ScheduledExecutorService tasks = Executors.newScheduledThreadPool(3);
    private final Map<String, Participant> participants = new HashMap<>();
    private final boolean rtp;
    private volatile boolean closed;
    private boolean started;
    private int sequence = new SecureRandom().nextInt(65535);
    private int packets;
    private int octets;
    private boolean reporting;
    private long lastReply;

    public MulticastVoice(VoiceProfile profile, String uid, String callsign,
                          NetworkInterface network, Listener listener) throws IOException {
        if (!profile.multicast()) throw new IllegalArgumentException("Choose a multicast profile.");
        this.profile = profile;
        this.uid = VoiceProfile.name(uid, "Participant ID");
        this.callsign = VoiceProfile.name(callsign, "Callsign");
        this.network = network;
        this.listener = listener;
        if (network == null || !network.isUp() || !network.supportsMulticast()) {
            throw new IOException("No multicast-capable Wi-Fi interface is available.");
        }
        localAddress = java.util.Collections.list(network.getInetAddresses()).stream().filter(a -> a instanceof Inet4Address)
                .map(InetAddress::getHostAddress).findFirst().orElseThrow(() -> new IOException("Wi-Fi has no IPv4 address."));
        rtp = profile.mode() == VoiceProfile.Mode.VX_RTP;
        audioGroup = InetAddress.getByName(profile.host());
        dataGroup = profile.hasDataChannel() ? InetAddress.getByName(profile.dataHost()) : null;
        MulticastSocket openedAudio = join(audioGroup, profile.port());
        try {
            dataSocket = dataGroup == null ? null : join(dataGroup, profile.dataPort());
        } catch (IOException | RuntimeException e) {
            openedAudio.close();
            throw e;
        }
        audioSocket = openedAudio;
    }

    private MulticastSocket join(InetAddress group, int port) throws IOException {
        MulticastSocket socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setNetworkInterface(network);
            socket.setTimeToLive(1);
            socket.setSoTimeout(3000);
            socket.joinGroup(new InetSocketAddress(group, port), network);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    public synchronized void start() throws IOException {
        if (closed || started) throw new IOException("Local voice session is unavailable.");
        publish(true, false);
        if (rtp) send(audioSocket, audioGroup, profile.port() + 1, VxWire.sourceDescription(uid));
        started = true;
        tasks.execute(() -> receive(audioSocket, false));
        if (dataSocket != null) tasks.execute(() -> receive(dataSocket, true));
        if (dataSocket != null || rtp) tasks.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized void send(byte[] opus) throws IOException {
        if (closed || !started) return;
        byte[] datagram = VxWire.encodeAudio(opus, rtp, sequence, System.currentTimeMillis() / 1000, uid);
        sequence = (sequence + 1) % 65535;
        send(audioSocket, audioGroup, profile.port(), datagram);
        packets++;
        octets += opus.length;
        reporting = rtp;
    }

    private void heartbeat() {
        try {
            synchronized (this) {
                if (closed) return;
                publish(false, false);
                if (reporting) {
                    send(audioSocket, audioGroup, profile.port() + 1,
                            VxWire.senderReport(uid, System.currentTimeMillis(), packets, octets));
                    reporting = false;
                }
                long now = System.nanoTime();
                if (participants.values().removeIf(p -> now - p.seen() > TimeUnit.SECONDS.toNanos(25))) {
                    notifyParticipants();
                }
            }
        } catch (IOException e) {
            if (!closed) listener.failure("Local Wi-Fi connection was interrupted.");
        }
    }

    private void receive(MulticastSocket socket, boolean presence) {
        byte[] buffer = new byte[VxWire.MAX_DATAGRAM + 1];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String source = packet.getAddress().getHostAddress();
                if (source == null || source.equals(localAddress) || packet.getLength() > VxWire.MAX_DATAGRAM) continue;
                byte[] data = java.util.Arrays.copyOf(buffer, packet.getLength());
                if (presence) readPresence(data, source);
                else {
                    byte[] opus = VxWire.decodeAudio(data);
                    if (opus != null) listener.audio(source, participantName(source), opus);
                }
            } catch (SocketTimeoutException ignored) {
                // Recheck closure after an idle receive interval.
            } catch (IOException e) {
                if (!closed) listener.failure("Local Wi-Fi socket closed.");
                return;
            }
        }
    }

    private synchronized void readPresence(byte[] data, String source) {
        try {
            VxWire.Presence state = VxWire.decodePresence(data);
            if (state == null || state.uid().equals(uid) || closed) return;
            if (state.requestReports() && System.nanoTime() - lastReply > TimeUnit.SECONDS.toNanos(1)) {
                lastReply = System.nanoTime();
                publish(false, false);
            }
            if (!state.group().equals(profile.host()) || state.port() != profile.port()) return;
            Participant before = participants.get(state.uid());
            if (state.leaving()) {
                if (before != null && before.address().equals(source)) {
                    participants.remove(state.uid());
                    notifyParticipants();
                }
                return;
            }
            if (before == null && participants.size() >= 128) return;
            participants.put(state.uid(), new Participant(source, state.callsign(), System.nanoTime()));
            if (before == null || !before.callsign().equals(state.callsign())) notifyParticipants();
        } catch (IOException ignored) {
            // Unrecognized or malformed datagrams share the same group.
        }
    }

    private synchronized String participantName(String source) {
        for (Participant participant : participants.values()) {
            if (participant.address().equals(source)) return participant.callsign();
        }
        return source;
    }

    private void notifyParticipants() {
        List<String> names = new ArrayList<>();
        for (Participant p : participants.values()) names.add(p.callsign());
        names.sort(Comparator.naturalOrder());
        listener.participants(List.copyOf(names));
    }

    private void publish(boolean requestReports, boolean leaving) throws IOException {
        if (dataSocket == null) return;
        VxWire.Presence state = new VxWire.Presence(uid, callsign, profile.host(), profile.port(), localAddress,
                requestReports, System.currentTimeMillis(), leaving);
        send(dataSocket, dataGroup, profile.dataPort(), VxWire.encodePresence(state));
    }

    private static void send(MulticastSocket socket, InetAddress group, int port, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, group, port));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (started) {
                publish(false, true);
                if (rtp) send(audioSocket, audioGroup, profile.port() + 1, VxWire.goodbye(uid));
            }
        } catch (IOException ignored) {
            // Disconnect must still close both sockets if the Wi-Fi link has gone away.
        } finally {
            audioSocket.close();
            if (dataSocket != null) dataSocket.close();
            tasks.shutdownNow();
        }
    }
}
