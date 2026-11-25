package se.takcampus.voice;

import java.util.HashSet;
import java.util.Set;

/**
 * Multiple buttons can be held. Cancellation requires release before the same source can talk
 * again.
 */
public final class PttSources {
  private final Set<String> held = new HashSet<>(), blocked = new HashSet<>();

  public synchronized boolean update(String source, boolean down) {
    if (!down) {
      held.remove(source);
      blocked.remove(source);
    } else if (!blocked.contains(source)) held.add(source);
    return !held.isEmpty();
  }

  public synchronized void cancel() {
    blocked.addAll(held);
    held.clear();
  }
}
