package se.takcampus.voice;

import static org.junit.Assert.*;

import org.junit.Test;

public class HeadsetButtonTest {
  @Test
  public void holdReleasesAndIgnoresRepeats() {
    HeadsetButton ptt = new HeadsetButton(false);
    assertEquals(Boolean.TRUE, ptt.event(0, 0, false));
    assertNull(ptt.event(0, 1, false));
    assertEquals(Boolean.FALSE, ptt.event(1, 0, false));
    assertNull(ptt.event(1, 0, false));
  }

  @Test
  public void toggleRequiresCompleteClicks() {
    HeadsetButton ptt = new HeadsetButton(true);
    assertNull(ptt.event(1, 0, false));
    assertNull(ptt.event(0, 0, false));
    assertNull(ptt.event(0, 1, false));
    assertEquals(Boolean.TRUE, ptt.event(1, 0, false));
    assertEquals(Boolean.FALSE, ptt.event(0, 0, false));
    assertNull(ptt.event(1, 0, false));
  }

  @Test
  public void separatePauseAndPlayClicksToggleOncePerSqueeze() {
    HeadsetButton ptt = new HeadsetButton(true);
    int[] keys = {127, 126, 127, 126}; // Four squeezes observed on Galaxy Buds.
    boolean expected = true;
    for (int key : keys) {
      assertTrue(HeadsetButton.handles(key));
      assertEquals(expected ? null : Boolean.FALSE, ptt.event(0, 0, false));
      assertNull(ptt.event(0, 1, false));
      assertEquals(expected ? Boolean.TRUE : null, ptt.event(1, 0, false));
      assertNull(ptt.event(1, 0, false));
      expected = !expected;
    }
    assertFalse(HeadsetButton.handles(87)); // Next track.
  }

  @Test
  public void stopWithMissingReleaseAndResumeOnNextCompleteClick() {
    HeadsetButton ptt = new HeadsetButton(true);
    assertNull(ptt.event(0, 0, false));
    assertEquals(Boolean.TRUE, ptt.event(1, 0, false));
    assertEquals(Boolean.FALSE, ptt.event(0, 0, false));
    assertNull(ptt.event(0, 2, false));
    assertNull(ptt.event(0, 0, false));
    assertEquals(Boolean.TRUE, ptt.event(1, 0, false));
  }

  @Test
  public void manualStopDiscardsPendingRelease() {
    HeadsetButton ptt = new HeadsetButton(true);
    ptt.event(0, 0, false);
    ptt.event(1, 0, false);
    ptt.reset();
    assertNull(ptt.event(1, 0, false));
    assertNull(ptt.event(0, 0, false));
    assertEquals(Boolean.TRUE, ptt.event(1, 0, false));
  }

  @Test
  public void cancellationNeverTogglesOn() {
    HeadsetButton ptt = new HeadsetButton(true);
    ptt.event(0, 0, false);
    assertEquals(Boolean.FALSE, ptt.event(1, 0, true));
    assertNull(ptt.event(1, 0, false));
    ptt.event(0, 0, false);
    ptt.reset();
    assertNull(ptt.event(1, 0, false));
    assertTrue(HeadsetButton.handles(79));
    assertTrue(HeadsetButton.handles(85));
    assertFalse(HeadsetButton.handles(264));
  }
}
