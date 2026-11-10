package se.takcampus.voice;

/** Momentary PTT: repeats never retrigger; focus loss requires a fresh physical press. */
public final class PttButton {
    private int key = -1;
    private boolean held;
    public static boolean assignable(int key) { return key == 4 || key == 265 || key == 266 || key == 267; }
    public void assign(int key) {
        if (key != -1 && !assignable(key)) throw new IllegalArgumentException("System-reserved button");
        this.key = key; held = false;
    }
    public boolean down(int key, int repeats, boolean eligible) {
        if (key != this.key || held || repeats != 0 || !eligible) return false;
        held = true; return true;
    }
    public boolean up(int key) {
        if (key != this.key || !held) return false;
        held = false; return true;
    }
    public boolean handles(int key) { return key == this.key && this.key != -1; }
    public void reset() { held = false; }
}
