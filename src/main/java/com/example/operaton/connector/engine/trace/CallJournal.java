package com.example.operaton.connector.engine.trace;

import java.util.Optional;

/**
 * Remembers the last REST call of each process instance, so a caller waiting for an instance (see
 * the process run API) can return what the last call answered, even though the instance's
 * variables are gone when it completes.
 */
public interface CallJournal {

  void record(String processInstanceId, CallRecord call);

  /** The last call of the instance, removed from the journal. */
  Optional<CallRecord> take(String processInstanceId);
}
