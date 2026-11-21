package com.example.operaton.connector.engine.input;

import com.example.operaton.connector.core.api.CallContext;
import java.util.Optional;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.context.Context;

/** {@link CallContext} backed by the running execution: engine expressions and process variables. */
public final class ExecutionCallContext implements CallContext {

  private final DelegateExecution execution;

  public ExecutionCallContext(DelegateExecution execution) {
    this.execution = execution;
  }

  @Override
  public Object evaluate(String expression) {
    return EngineValues.plain(Context.getProcessEngineConfiguration().getExpressionManager()
        .createExpression(expression).getValue(execution));
  }

  @Override
  public Optional<Object> variable(String name) {
    return execution.hasVariable(name) ? Optional.ofNullable(EngineValues.plain(execution.getVariable(name))) : Optional.empty();
  }

  @Override
  public String location() {
    return execution.getCurrentActivityId();
  }
}
