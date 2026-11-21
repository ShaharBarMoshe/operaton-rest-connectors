package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;

/** Asserts that a finished process instance left no variable values behind. */
final class StoredVariables {

  private StoredVariables() {}

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

  /** Variables as plain Java values: Spin Json variables (REST results) become Map / List. */
  static java.util.Map<String, Object> plain(java.util.Map<String, Object> variables) {
    java.util.Map<String, Object> result = new java.util.HashMap<>();
    variables.forEach((k, v) -> {
      try {
        result.put(k, v instanceof org.operaton.spin.json.SpinJsonNode n ? MAPPER.readValue(n.toString(), Object.class) : v);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    return result;
  }

  /**
   * Id of the end event the instance reached. The process instance's own endActivityId names the
   * event subprocess when it ended there, so look at the end event activity instances instead.
   */
  static String endEvent(HistoryService history, String processInstanceId) {
    var ends = history.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
        .activityType("noneEndEvent").finished().list();
    assertThat(ends).as("end events reached").hasSize(1);
    return ends.get(0).getActivityId();
  }

  static void assertNone(RuntimeService runtime, HistoryService history, String processInstanceId) {
    assertThat(runtime.createVariableInstanceQuery().processInstanceIdIn(processInstanceId).count())
        .as("runtime variables left after completion").isZero();
    assertThat(history.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).count())
        .as("variables in history").isZero();
    assertThat(history.createHistoricDetailQuery().processInstanceId(processInstanceId).count())
        .as("variable updates in history").isZero();
  }
}
