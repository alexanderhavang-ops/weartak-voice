package se.takcampus.voice;

/** Times use monotonic milliseconds. One instance belongs to one button activation. */
public final class PttTiming {
    public static final long MAX_MS = 60_000;
    public static final long SILENCE_MS = 5_000;
    public static final long REMINDER_MS = 3_000;
    // About -46 dBFS RMS for signed 16-bit microphone samples.
    private static final int MIN_RMS = 164;
    private final long maximumDeadline;
    private long silenceDeadline = Long.MAX_VALUE;
    private long reminderAt = Long.MAX_VALUE;
    private long ignoreLevelUntil;

    public PttTiming(long pressedAt) {
        maximumDeadline = pressedAt + MAX_MS;
    }

    public synchronized void ready(long now) {
        if (now >= maximumDeadline || silenceDeadline != Long.MAX_VALUE) return;
        silenceDeadline = now + SILENCE_MS;
        reminderAt = now + REMINDER_MS;
        ignoreLevelUntil = now + 350;
    }

    public synchronized void observe(byte[] pcm, long now) {
        if (silenceDeadline == Long.MAX_VALUE || now < ignoreLevelUntil || now >= deadline()) return;
        int samples = pcm.length / 2;
        if (samples == 0) return;
        long sum = 0, squares = 0;
        for (int i = 0; i < samples * 2; i += 2) {
            int sample = (pcm[i] & 255) | (pcm[i + 1] << 8);
            sum += sample;
            squares += (long) sample * sample;
        }
        // Remove DC bias before comparing the mean square to the threshold.
        if (squares - sum * sum / samples >= (long) samples * MIN_RMS * MIN_RMS) {
            silenceDeadline = now + SILENCE_MS;
        }
    }

    public synchronized boolean takeReminder(long now) {
        if (now < reminderAt || now >= deadline()) return false;
        reminderAt = now + REMINDER_MS;
        ignoreLevelUntil = now + 300;
        return true;
    }

    public synchronized long deadline() { return Math.min(maximumDeadline, silenceDeadline); }
    public synchronized boolean maximumFirst() { return maximumDeadline <= silenceDeadline; }
    public synchronized long nextCheck() { return Math.min(deadline(), reminderAt); }
}
