package com.example.operaton;

import static org.assertj.core.api.Assertions.assertThat;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;

/** Asserts that a finished process instance left no variable values behind. */
final class StoredVariables {

  private StoredVariables() {}

  static void assertNone(RuntimeService runtime, HistoryService history, String processInstanceId) {
    assertThat(runtime.createVariableInstanceQuery().processInstanceIdIn(processInstanceId).count())
        .as("runtime variables left after completion").isZero();
    assertThat(history.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).count())
        .as("variables in history").isZero();
    assertThat(history.createHistoricDetailQuery().processInstanceId(processInstanceId).count())
        .as("variable updates in history").isZero();
  }
}
