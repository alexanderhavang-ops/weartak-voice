package se.takcampus.voice;

/**
 * Maps Android headset media keys to PTT while headset controls are enabled.
 */
public final class HeadsetButton {
  // Android KeyEvent values. This class also runs in plain JVM tests.
  private static final int HEADSETHOOK = 79;
  private static final int MEDIA_PLAY_PAUSE = 85;
  private static final int MEDIA_PLAY = 126;
  private static final int MEDIA_PAUSE = 127;
  private final boolean toggle;
  private boolean held, toggled, stoppedOnDown;

  public HeadsetButton(boolean toggle) {
    this.toggle = toggle;
  }

  public static boolean handles(int key) {
    return key == HEADSETHOOK || key == MEDIA_PLAY_PAUSE || key == MEDIA_PLAY || key == MEDIA_PAUSE;
  }

  /** null means no PTT change. action 0=DOWN, 1=UP. */
  public Boolean event(int action, int repeat, boolean canceled) {
    if (canceled) {
      reset();
      return false;
    }
    if (action == 0 && repeat == 0 && (!held || stoppedOnDown)) {
      stoppedOnDown = toggle && toggled;
      if (stoppedOnDown) {
        toggled = false;
        held = true;
        return false;
      }
      held = true;
      return toggle ? null : true;
    }
    if (action == 1 && held) {
      held = false;
      if (stoppedOnDown) {
        stoppedOnDown = false;
        return null;
      }
      if (!toggle) return false;
      toggled = !toggled;
      return toggled;
    }
    return null;
  }

  public void reset() {
    held = false;
    toggled = false;
    stoppedOnDown = false;
  }
}
