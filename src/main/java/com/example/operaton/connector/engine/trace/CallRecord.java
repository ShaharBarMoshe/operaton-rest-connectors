package com.example.operaton.connector.engine.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One REST task's call, as the last one of its process instance.
 *
 * @param task       activity id of the REST task
 * @param method     HTTP method
 * @param url        the URL called (or, when the request was never built, the url input)
 * @param httpStatus status of the answer; null when there was none
 * @param body       response body (shaped by the response schema); null when the call failed
 * @param errorCode  BPMN error code (HTTP_404, REQUEST_INVALID, ...) or "EXCEPTION"; null on success
 * @param error      error message; null on success
 * @param mocked     answered by test mode
 */
public record CallRecord(String task, String method, String url, Integer httpStatus, JsonNode body,
                         String errorCode, String error, boolean mocked, long durationMs, Instant at) {

  public boolean succeeded() {
    return errorCode == null;
  }
}
