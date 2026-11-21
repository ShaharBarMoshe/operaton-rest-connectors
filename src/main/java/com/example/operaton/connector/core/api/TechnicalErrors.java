package com.example.operaton.connector.core.api;

import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.error.RestConnectorException;

/** How 5xx responses, timeouts and connection problems are raised. */
public enum TechnicalErrors {
  /** {@link RestConnectorException}: the task fails (incident, or retried with async before). */
  EXCEPTION,
  /** {@link ConnectorError} {@code HTTP_5xx} / {@code CONNECTION_FAILED}: the model can handle it. */
  BPMN_ERROR;

  /** The task input value: empty / "exception" / "bpmnError". */
  public static TechnicalErrors fromInput(String value) {
    if (value == null || value.isBlank() || value.trim().equals("exception")) {
      return EXCEPTION;
    }
    if (value.trim().equals("bpmnError")) {
      return BPMN_ERROR;
    }
    throw new IllegalArgumentException("must be 'exception' or 'bpmnError', got: " + value);
  }
}
