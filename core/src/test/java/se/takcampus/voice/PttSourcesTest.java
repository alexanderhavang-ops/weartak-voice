package se.takcampus.voice;

import static org.junit.Assert.*;

import org.junit.Test;

public class PttSourcesTest {
  @Test
  public void releasingOneButtonDoesNotStopAnother() {
    PttSources ptt = new PttSources();
    assertTrue(ptt.update("watch", true));
    assertTrue(ptt.update("headset", true));
    assertTrue(ptt.update("watch", false));
    assertFalse(ptt.update("headset", false));
  }

  @Test
  public void cancelRequiresReleaseBeforeHeldSourceCanResume() {
    PttSources ptt = new PttSources();
    ptt.update("headset", true);
    ptt.cancel();
    assertFalse(ptt.update("headset", true));
    assertFalse(ptt.update("headset", false));
    assertTrue(ptt.update("headset", true));
  }

  @Test
  public void repeatedDownsDoNotAccumulateHolds() {
    PttSources ptt = new PttSources();
    ptt.update("screen", true);
    ptt.update("screen", true);
    assertFalse(ptt.update("screen", false));
    ptt.cancel();
    assertTrue(ptt.update("watch", true));
  }
}
