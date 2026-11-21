package com.example.operaton.connector.engine.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.operaton.connector.core.error.RestConnectorException;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.VariableScope;

class VariableTokenProviderTest {

  VariableScope scope(Object token) {
    VariableScope scope = mock(VariableScope.class);
    when(scope.hasVariable("accessToken")).thenReturn(token != null);
    when(scope.getVariable("accessToken")).thenReturn(token);
    return scope;
  }

  final TokenProvider tokens = new VariableTokenProvider("accessToken");

  @Test
  void readsTheVariable_acceptsBearerPrefix() {
    assertThat(tokens.token(scope("a.b.c"))).contains("a.b.c");
    assertThat(tokens.token(scope("Bearer a.b.c"))).contains("a.b.c");
  }

  @Test
  void noVariable_noToken() {
    assertThat(tokens.token(scope(null))).isEmpty();
    assertThat(new VariableTokenProvider("").token(scope("a.b.c"))).isEmpty();
  }

  @Test
  void malformedTokenIsAMistake() {
    assertThatThrownBy(() -> tokens.token(scope("  "))).isInstanceOf(RestConnectorException.class);
    assertThatThrownBy(() -> tokens.token(scope("a b"))).isInstanceOf(RestConnectorException.class);
  }
}
