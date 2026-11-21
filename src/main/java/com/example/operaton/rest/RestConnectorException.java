package com.example.operaton.rest;

/** Technical failure (5xx, I/O, bad config). Not a BpmnError → engine creates an incident / retries. */
public class RestConnectorException extends RuntimeException {
  public RestConnectorException(String message) {
    super(message);
  }

  public RestConnectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
