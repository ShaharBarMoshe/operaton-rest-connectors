package com.example.operaton.connector.engine.auth;

import java.util.Optional;
import org.operaton.bpm.engine.delegate.VariableScope;

/**
 * Supplies the bearer token for the REST calls of an execution. The default
 * ({@link VariableTokenProvider}) reads a process variable; register another bean (e.g. one that
 * fetches an OAuth2 client-credentials token) to change where tokens come from.
 */
public interface TokenProvider {

  /** The token without the "Bearer " prefix, or empty to send no Authorization header. */
  Optional<String> token(VariableScope execution);
}
