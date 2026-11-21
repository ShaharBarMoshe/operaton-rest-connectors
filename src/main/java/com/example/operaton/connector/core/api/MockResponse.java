package com.example.operaton.connector.core.api;

/**
 * Test mode of a task: the request is built and validated but not sent; this answer is used as if
 * the service had returned it (status mapping, response schema, result variables all apply).
 *
 * @param body response body (JSON or text), null / empty for none
 */
public record MockResponse(int status, String body) {

  public MockResponse {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("mock status must be an HTTP status (100-599), got " + status);
    }
  }
}
