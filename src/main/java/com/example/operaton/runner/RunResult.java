package com.example.operaton.runner;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outcome of POST /api/process/{key}/run.
 *
 * @param status            SUCCESS: the instance completed without an error event or incident; else FAILURE
 * @param reason            FAILURE only: BPMN_ERROR, INCIDENT, EXCEPTION, TIMEOUT or TERMINATED
 * @param processInstanceId null when the start itself failed
 * @param endEvent          id of the end event the instance finished at
 * @param response          SUCCESS: the last REST call of the instance and its response
 * @param error             FAILURE: what went wrong
 * @param lastCall          FAILURE: the last REST call, when there was one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResult(Status status, String reason, String processKey, String processInstanceId, String endEvent,
                        long durationMs, Call response, Error error, Call lastCall) {

  public enum Status { SUCCESS, FAILURE }

  /** A REST call of the process: what was called and what it answered. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Call(String task, String method, String url, Integer httpStatus, Object body, Boolean mocked) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Error(String code, String message, String task) {
  }
}
