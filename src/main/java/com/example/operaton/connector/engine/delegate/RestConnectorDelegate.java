package com.example.operaton.connector.engine.delegate;

import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.engine.input.ExecutionCallContext;
import com.example.operaton.connector.engine.input.TaskInputs;
import com.example.operaton.connector.engine.output.ResultVariables;
import com.example.operaton.connector.engine.trace.CallJournal;
import com.example.operaton.connector.engine.trace.CallRecord;
import java.time.Instant;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;

/**
 * The engine side of a REST service task: reads the task inputs ({@link TaskInputs}), runs the call
 * ({@link RestConnector}), stores status and result ({@link ResultVariables}), records the call
 * ({@link CallJournal}) and turns {@link ConnectorError}s into BPMN errors. One bean per HTTP method
 * ({@code ${restGet}}, {@code ${restPut}}, {@code ${restUpdate}}, {@code ${restPost}}, {@code ${restDelete}}),
 * see {@code RestConnectorConfig}.
 *
 * <p>While a task runs, the log MDC key {@value #MDC_KEY} holds "process instance / task", so every
 * connector log line of the task can be told apart.
 */
public class RestConnectorDelegate implements JavaDelegate {

  public static final String MDC_KEY = "restTask";
  private static final Logger LOG = LoggerFactory.getLogger(RestConnectorDelegate.class);

  private final HttpMethod method;
  private final boolean sourcesAsBody;
  private final TaskInputs inputs;
  private final RestConnector connector;
  private final ResultVariables results;
  private final CallJournal journal;

  /**
   * @param sourcesAsBody without a body schema, send the merged sources as body (POST / PUT / PATCH)
   */
  public RestConnectorDelegate(HttpMethod method, boolean sourcesAsBody, TaskInputs inputs,
                               RestConnector connector, ResultVariables results, CallJournal journal) {
    this.method = method;
    this.sourcesAsBody = sourcesAsBody;
    this.inputs = inputs;
    this.connector = connector;
    this.results = results;
    this.journal = journal;
  }

  public HttpMethod method() {
    return method;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, "[" + execution.getProcessInstanceId() + " " + execution.getCurrentActivityId() + "] ");
    long start = System.nanoTime();
    try {
      run(execution, start);
    } finally {
      if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
    }
  }

  private void run(DelegateExecution execution, long start) {
    String task = execution.getCurrentActivityId();
    RestCall call;
    try {
      call = inputs.read(execution, method, sourcesAsBody);
    } catch (RuntimeException e) {
      LOG.error("FAILED {} '{}': {}", method, name(execution), e.getMessage());
      record(execution, task, null, null, null, "EXCEPTION", e.getMessage(), false, start);
      throw e;
    }
    LOG.info("START {} '{}' ({}: url {}, sources {}, auth {}{})", method, name(execution),
        execution.getProcessDefinitionId(), call.url(), call.sources().size(),
        call.bearerToken() != null ? "bearer token" : "none", call.mockResponse() != null ? ", MOCK" : "");

    String statusVariable = inputs.statusVariable(execution);
    RestResult result;
    try {
      result = connector.execute(call, new ExecutionCallContext(execution));
    } catch (ConnectorError e) {
      results.status(execution, statusVariable, e.status());
      LOG.warn("END {} '{}' with BPMN error {} after {} ms: {}", method, name(execution), e.code(), ms(start), e.getMessage());
      record(execution, task, e.uri() != null ? e.uri().toString() : call.url(), e.status(), null, e.code(), e.getMessage(),
          call.mockResponse() != null, start);
      throw new BpmnError(e.code(), e.getMessage(), e);
    } catch (RuntimeException e) {
      LOG.error("FAILED {} '{}' after {} ms: {}", method, name(execution), ms(start), e.getMessage());
      record(execution, task, call.url(), null, null, "EXCEPTION", e.getMessage(), call.mockResponse() != null, start);
      throw e;
    }
    results.status(execution, statusVariable, result.status());
    String resultVariable = inputs.resultVariable(execution);
    results.body(execution, resultVariable, result.body());
    record(execution, task, String.valueOf(result.uri()), result.status(), result.body(), null, null, result.mocked(), start);
    LOG.info("END {} '{}' {} in {} ms{}{}", method, name(execution), result.status(), ms(start),
        resultVariable != null ? ", result -> " + resultVariable : "",
        statusVariable != null ? ", status -> " + statusVariable : "");
  }

  private void record(DelegateExecution execution, String task, String url, Integer status,
                      com.fasterxml.jackson.databind.JsonNode body, String errorCode, String error, boolean mocked, long start) {
    journal.record(execution.getProcessInstanceId(), new CallRecord(task, method.name(), url, status,
        body == null || body.isMissingNode() ? null : body, errorCode, error, mocked, ms(start), Instant.now()));
  }

  private static String name(DelegateExecution execution) {
    String name = execution.getCurrentActivityName();
    return name != null ? name : execution.getCurrentActivityId();
  }

  private static long ms(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
