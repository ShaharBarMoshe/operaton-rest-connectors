package com.example.operaton.connector.engine.auth;

import com.example.operaton.connector.core.error.RestConnectorException;
import java.util.Optional;
import org.operaton.bpm.engine.delegate.VariableScope;

/**
 * Takes the token from the execution variable {@code rest-connector.jwt-variable} (default
 * {@code accessToken}), usually set when the process is started. Not set → no token. A leading
 * "Bearer " is accepted; an empty or malformed value is a mistake (exception → incident).
 */
public class VariableTokenProvider implements TokenProvider {

  private final String variable;

  /** @param variable variable name; blank disables tokens */
  public VariableTokenProvider(String variable) {
    this.variable = variable == null ? "" : variable.trim();
  }

  @Override
  public Optional<String> token(VariableScope execution) {
    if (variable.isEmpty() || !execution.hasVariable(variable)) {
      return Optional.empty();
    }
    Object v = execution.getVariable(variable);
    String token = v == null ? "" : v.toString().trim();
    if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
      token = token.substring(7).trim();
    }
    if (token.isEmpty() || token.chars().anyMatch(c -> c <= ' ')) {
      throw new RestConnectorException("Variable '" + variable + "' does not hold a valid bearer token");
    }
    return Optional.of(token);
  }
}
