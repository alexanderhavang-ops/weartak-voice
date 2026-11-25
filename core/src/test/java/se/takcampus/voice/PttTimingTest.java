package se.takcampus.voice;

import org.junit.Test;
import static org.junit.Assert.*;

public class PttTimingTest {
    private byte[] frame(int offset, int level) {
        byte[] pcm = new byte[1920]; // 20 ms at 48 kHz
        for (int i = 0; i < pcm.length / 2; i++) {
            int value = offset + (i % 2 == 0 ? level : -level);
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    @Test public void silenceStartsAtMicReadyAndReminderDoesNotExtendIt() {
        PttTiming p = new PttTiming(100);
        assertEquals(60100, p.nextCheck());
        assertFalse(p.takeReminder(1000));
        p.ready(1100);
        assertEquals(4100, p.nextCheck());
        p.observe(frame(0, 1500), 1150);  // ready chirp
        p.observe(frame(600, 40), 3000);  // quiet with DC offset
        assertFalse(p.takeReminder(4099));
        assertTrue(p.takeReminder(4100));
        p.observe(frame(0, 1500), 4220);  // reminder pickup
        assertEquals(6100, p.nextCheck());
        p.observe(frame(0, 1500), 6100);  // expired sessions cannot reopen
        assertEquals(6100, p.deadline());
        assertFalse(p.maximumFirst());
        assertFalse(p.takeReminder(7100));
    }

    @Test public void speechRestartsFiveSecondsAtEitherSampleRate() {
        for (int rate : new int[]{8000, 48000}) {
            PttTiming p = new PttTiming(0);
            p.ready(100);
            byte[] speech = java.util.Arrays.copyOf(frame(0, 220), rate / 50 * 2);
            p.observe(speech, 5099);
            assertEquals(10099, p.deadline());
            p.observe(speech, 9000);
            assertEquals(14000, p.deadline());
        }
    }

    @Test public void remindersRepeatAndSpeechCannotExtendHardCap() {
        PttTiming p = new PttTiming(500);
        p.ready(700);
        byte[] speech = frame(0, 3000);
        int reminders = 0;
        for (long now = 700; now < 60500; now += 20) {
            p.observe(speech, now);
            if (p.takeReminder(now)) reminders++;
        }
        assertEquals(19, reminders);
        assertEquals(60500, p.deadline());
        assertTrue(p.maximumFirst());
        assertFalse(p.takeReminder(60500));
    }
}
