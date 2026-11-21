package com.example.operaton.connector.engine.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CallJournal} in memory only (never in the database), bounded: beyond {@code maxInstances}
 * the oldest instance is forgotten, so instances nobody asks about cannot pile up.
 */
public class InMemoryCallJournal implements CallJournal {

  private final Map<String, CallRecord> last;

  public InMemoryCallJournal(int maxInstances) {
    this.last = new LinkedHashMap<>(16, 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, CallRecord> eldest) {
        return size() > maxInstances;
      }
    };
  }

  @Override
  public synchronized void record(String processInstanceId, CallRecord call) {
    last.remove(processInstanceId);           // re-insert: the most recently active instance is kept longest
    last.put(processInstanceId, call);
  }

  @Override
  public synchronized Optional<CallRecord> take(String processInstanceId) {
    return Optional.ofNullable(last.remove(processInstanceId));
  }
}
