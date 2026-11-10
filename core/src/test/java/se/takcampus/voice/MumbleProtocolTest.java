package se.takcampus.voice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;

public class MumbleProtocolTest {
    @Test public void frameRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MumbleWire.write(out, 9, new byte[]{1,2,3});
        MumbleWire.Frame frame = MumbleWire.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(9, frame.type); assertArrayEquals(new byte[]{1,2,3}, frame.body);
    }
    @Test public void fragmentedFrameRead() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); MumbleWire.write(out, 7, new byte[]{5,6,7});
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1,len)); }
        };
        assertArrayEquals(new byte[]{5,6,7}, MumbleWire.read(fragmented).body);
    }
    @Test public void oversizedFrameRejectedBeforeAllocation() {
        assertThrows(IOException.class, () -> MumbleWire.read(new ByteArrayInputStream(new byte[]{0,0,127,-1,-1,-1})));
    }
    @Test public void truncatedFrameRejected() {
        assertThrows(IOException.class, () -> MumbleWire.read(new ByteArrayInputStream(new byte[]{0,1,0,0,0,5,1})));
    }
    @Test public void protobufNumbersAndText() throws Exception {
        MumbleWire.Fields f = new MumbleWire.Fields(MumbleWire.join(MumbleWire.integer(1, 70000), MumbleWire.text(3, "Övning")));
        assertEquals(70000, f.id(1)); assertEquals("Övning", f.text(3)); assertFalse(f.has(2));
    }
    @Test public void duplicateProtobufScalarRejected() throws Exception {
        MumbleWire.Fields f = new MumbleWire.Fields(MumbleWire.join(MumbleWire.integer(1, 1), MumbleWire.integer(1, 2)));
        assertThrows(IOException.class, () -> f.id(1));
    }
    @Test public void malformedProtobufRejected() {
        for (byte[] b : new byte[][]{{0},{10,99},{8,-128},{11}}) assertThrows(IOException.class, () -> new MumbleWire.Fields(b));
    }
    private byte[] incoming(long session, byte[] outgoing) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(outgoing[0]); MumbleAudioPacket.put(out, session);
        out.write(outgoing, 1, outgoing.length-1); return out.toByteArray();
    }
    @Test public void audioVarintBoundaries() throws Exception {
        for (long sequence : new long[]{0,127,128,16383,16384,0x1fffff,0x200000,0xfffffff,0x10000000,0xffffffffL,0x100000000L}) {
            MumbleAudioPacket p = MumbleAudioPacket.incoming(incoming(135, MumbleAudioPacket.outgoing(sequence, new byte[]{(byte)0xf8,-1,-2},false)));
            assertEquals(sequence,p.sequence); assertEquals(135,p.session); assertFalse(p.last);
        }
    }
    @Test public void endOfTransmissionRetainsFlag() throws Exception {
        MumbleAudioPacket p = MumbleAudioPacket.incoming(incoming(2, MumbleAudioPacket.outgoing(42,new byte[0],true)));
        assertTrue(p.last); assertEquals(0,p.opus.length);
    }
    @Test public void shortVoicePacketRejected() {
        assertThrows(IOException.class, () -> MumbleAudioPacket.incoming(new byte[]{(byte)0x80,1,0,10,1}));
    }
    @Test public void opusDurationMatchesTenMillisecondUnits() throws Exception {
        assertEquals(2,MumbleAudioPacket.duration10ms(new byte[]{(byte)0xf8,-1,-2}));
        assertEquals(1,MumbleAudioPacket.duration10ms(new byte[]{(byte)0xf0,-1,-2}));
        assertThrows(IOException.class, () -> MumbleAudioPacket.duration10ms(new byte[0]));
    }
    @Test public void certificateAndPasswordAreNotRequiredByConstructor() throws Exception {
        MumbleConnection c = new MumbleConnection("localhost",64738,"test",null,javax.net.ssl.SSLContext.getDefault(),null);
        assertFalse(c.canTransmit()); assertThrows(IOException.class,c::beginTransmission);
    }
}
