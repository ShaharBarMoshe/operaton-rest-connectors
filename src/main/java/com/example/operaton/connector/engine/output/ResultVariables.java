package com.example.operaton.connector.engine.output;

import com.example.operaton.connector.schema.JsonComposer;
import com.fasterxml.jackson.databind.JsonNode;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.spin.plugin.variable.SpinValues;

/**
 * Writes what a call produced into process variables: the HTTP status ({@code statusVariable}) and
 * the response body ({@code resultVariable}). JSON objects / arrays become Spin "Json" variables:
 * Cockpit and the REST API show the JSON text, they can be a source again, and expressions read them
 * with {@code ${var.prop("field").stringValue()}}; other values are stored as plain values.
 */
public class ResultVariables {

  private final JsonComposer composer;

  public ResultVariables(JsonComposer composer) {
    this.composer = composer;
  }

  /** @param variable target variable, null = not wanted */
  public void status(VariableScope execution, String variable, Integer status) {
    if (variable != null && status != null) {
      execution.setVariable(variable, status);
    }
  }

  /** @param variable target variable, null = not wanted */
  public void body(VariableScope execution, String variable, JsonNode body) {
    if (variable != null) {
      execution.setVariable(variable, body.isContainerNode()
          ? SpinValues.jsonValue(body.toString()).create()
          : composer.toJava(body));
    }
  }
}
