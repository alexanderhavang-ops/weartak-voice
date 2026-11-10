package se.takcampus.voice;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded Mumble control framing and protobuf fields; audio varints are a different format. */
public final class MumbleWire {
    private MumbleWire() {}
    public static final int MAX_FRAME = 1024 * 1024;
    public static final class Frame {
        public final int type;
        public final byte[] body;
        public Frame(int type, byte[] body) { this.type = type; this.body = body; }
    }
    public static Frame read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int type = in.readUnsignedShort();
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) throw new IOException("Mumble frame size limit");
        byte[] body = new byte[length]; in.readFully(body);
        return new Frame(type, body);
    }
    public static void write(OutputStream stream, int type, byte[] body) throws IOException {
        if (type < 0 || type > 65535 || body.length > MAX_FRAME) throw new IOException("Mumble frame size limit");
        DataOutputStream out = new DataOutputStream(stream);
        out.writeShort(type); out.writeInt(body.length); out.write(body); out.flush();
    }
    public static byte[] integer(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, (long) field << 3); varint(out, value); return out.toByteArray();
    }
    public static byte[] text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] bytes(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, ((long) field << 3) | 2); varint(out, value.length);
        out.write(value, 0, value.length); return out.toByteArray();
    }
    public static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        do { int b = (int) value & 127; value >>>= 7; out.write(value == 0 ? b : b | 128); } while (value != 0);
    }
    public static final class Fields {
        private final Map<Integer, List<Object>> values = new HashMap<>();
        private final byte[] bytes;
        private int at;
        public Fields(byte[] data) throws IOException {
            bytes = data;
            int count = 0;
            while (at < bytes.length) {
                if (++count > 32768) throw new IOException("Too many protobuf fields");
                long tag = number(); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (field <= 0 || tag >>> 3 > 0x1fffffff) throw new IOException("Invalid protobuf tag");
                Object value;
                if (wire == 0) value = number();
                else if (wire == 2) {
                    long length = number();
                    if (length < 0 || length > bytes.length - at) throw new IOException("Truncated protobuf field");
                    value = Arrays.copyOfRange(bytes, at, at + (int) length); at += (int) length;
                } else if (wire == 1 || wire == 5) {
                    int length = wire == 1 ? 8 : 4;
                    if (length > bytes.length - at) throw new IOException("Truncated fixed field");
                    at += length; continue;
                } else throw new IOException("Unsupported protobuf wire type");
                if (!values.containsKey(field)) values.put(field, new ArrayList<>());
                values.get(field).add(value);
            }
        }
        private long number() throws IOException {
            long n = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at == bytes.length) throw new IOException("Truncated protobuf integer");
                int b = bytes[at++] & 255;
                if (shift == 63 && b > 1) throw new IOException("Protobuf integer overflow");
                n |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return n;
            }
            throw new IOException("Protobuf integer overflow");
        }
        public boolean has(int field) { return values.containsKey(field); }
        private Object one(int field) throws IOException {
            List<Object> found = values.get(field);
            if (found == null || found.size() != 1) throw new IOException("Missing/duplicate protobuf scalar");
            return found.get(0);
        }
        public long integer(int field) throws IOException {
            Object value = one(field);
            if (!(value instanceof Long)) throw new IOException("Expected integer field");
            return (Long) value;
        }
        public int id(int field) throws IOException {
            long n = integer(field);
            if (n < 0 || n > Integer.MAX_VALUE) throw new IOException("Invalid Mumble identifier");
            return (int) n;
        }
        public String text(int field) throws IOException {
            Object value = one(field);
            if (!(value instanceof byte[])) throw new IOException("Expected text field");
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
    }
}
