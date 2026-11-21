package com.example.operaton.connector.engine.delegate;

import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.engine.input.ExecutionCallContext;
import com.example.operaton.connector.engine.input.TaskInputs;
import com.example.operaton.connector.engine.output.ResultVariables;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.http.HttpMethod;

/**
 * The engine side of a REST service task: reads the task inputs ({@link TaskInputs}), runs the call
 * ({@link RestConnector}), stores status and result ({@link ResultVariables}) and turns
 * {@link ConnectorError}s into BPMN errors. One bean per HTTP method ({@code ${restGet}},
 * {@code ${restPut}}, {@code ${restUpdate}}, {@code ${restPost}}, {@code ${restDelete}}), see
 * {@code RestConnectorConfig}.
 */
public class RestConnectorDelegate implements JavaDelegate {

  private final HttpMethod method;
  private final boolean sourcesAsBody;
  private final TaskInputs inputs;
  private final RestConnector connector;
  private final ResultVariables results;

  /**
   * @param sourcesAsBody without a body schema, send the merged sources as body (POST / PUT / PATCH)
   */
  public RestConnectorDelegate(HttpMethod method, boolean sourcesAsBody, TaskInputs inputs,
                               RestConnector connector, ResultVariables results) {
    this.method = method;
    this.sourcesAsBody = sourcesAsBody;
    this.inputs = inputs;
    this.connector = connector;
    this.results = results;
  }

  public HttpMethod method() {
    return method;
  }

  @Override
  public void execute(DelegateExecution execution) {
    RestCall call = inputs.read(execution, method, sourcesAsBody);
    String statusVariable = inputs.statusVariable(execution);
    RestResult result;
    try {
      result = connector.execute(call, new ExecutionCallContext(execution));
    } catch (ConnectorError e) {
      results.status(execution, statusVariable, e.status());
      throw new BpmnError(e.code(), e.getMessage(), e);
    }
    results.status(execution, statusVariable, result.status());
    results.body(execution, inputs.resultVariable(execution), result.body());
  }
}
