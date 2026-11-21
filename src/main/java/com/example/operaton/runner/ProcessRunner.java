package com.example.operaton.runner;

import com.example.operaton.connector.config.RestConnectorProperties;
import com.example.operaton.connector.engine.trace.CallJournal;
import com.example.operaton.connector.engine.trace.CallRecord;
import com.example.operaton.runner.RunResult.Call;
import com.example.operaton.runner.RunResult.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.operaton.bpm.engine.ActivityTypes;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.spin.plugin.variable.SpinValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts a process, waits until the instance has ended (or the timeout passed) and reports
 * SUCCESS with the response of its last REST call, or FAILURE with the error.
 *
 * <p>FAILURE when: the start threw (a technical error, nothing was kept), an error event ran (error
 * boundary event, error start event of an event subprocess, error end event), an incident was
 * raised, the instance was terminated, or it did not end within the timeout. The last call comes
 * from the {@link CallJournal}, because the instance's variables are deleted when it completes.
 */
@Service
public class ProcessRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessRunner.class);
  private static final Set<String> ERROR_EVENTS = Set.of(
      ActivityTypes.BOUNDARY_ERROR, ActivityTypes.START_EVENT_ERROR, ActivityTypes.END_EVENT_ERROR);
  private static final Duration POLL = Duration.ofMillis(100);

  private final RuntimeService runtimeService;
  private final HistoryService historyService;
  private final RepositoryService repositoryService;
  private final CallJournal journal;
  private final Duration defaultTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public ProcessRunner(RuntimeService runtimeService, HistoryService historyService, RepositoryService repositoryService,
                       CallJournal journal, RestConnectorProperties properties) {
    this.runtimeService = runtimeService;
    this.historyService = historyService;
    this.repositoryService = repositoryService;
    this.journal = journal;
    this.defaultTimeout = properties.runTimeout();
  }

  public boolean exists(String processKey) {
    return repositoryService.createProcessDefinitionQuery().processDefinitionKey(processKey).latestVersion().count() > 0;
  }

  /**
   * @param variables plain JSON values; objects and arrays become Spin Json variables
   * @param timeout   null: rest-connector.run-timeout
   */
  public RunResult run(String processKey, Map<String, Object> variables, Duration timeout) {
    long start = System.nanoTime();
    LOG.info("RUN {}: starting with variables {}", processKey, variables.keySet());
    ProcessInstanceWithVariables started;
    try {
      started = runtimeService.createProcessInstanceByKey(processKey).setVariables(engineVariables(variables))
          .executeWithVariablesInReturn();
    } catch (RuntimeException e) {
      LOG.warn("RUN {}: FAILURE, the start failed: {}", processKey, e.getMessage());
      return new RunResult(Status.FAILURE, "EXCEPTION", processKey, null, null, ms(start),
          null, new RunResult.Error("EXCEPTION", rootMessage(e), null), null);
    }
    String id = started.getId();
    HistoricProcessInstance instance = waitForEnd(id, timeout == null ? defaultTimeout : timeout);
    boolean incident = runtimeService.createIncidentQuery().processInstanceId(id).count() > 0;
    if (instance == null || instance.getEndTime() == null) {
      String reason = incident ? "INCIDENT" : "TIMEOUT";
      LOG.warn("RUN {} {}: FAILURE ({}), instance still running", processKey, id, reason);
      return new RunResult(Status.FAILURE, reason, processKey, id, null, ms(start), null,
          new RunResult.Error(reason, incident ? incidentMessage(id) : "not ended within the timeout", null), null);
    }

    List<HistoricActivityInstance> activities = historyService.createHistoricActivityInstanceQuery()
        .processInstanceId(id).list();
    String endEvent = activities.stream().filter(a -> ActivityTypes.END_EVENT_NONE.equals(a.getActivityType())
            || ActivityTypes.END_EVENT_ERROR.equals(a.getActivityType()))
        .max(Comparator.comparing(HistoricActivityInstance::getEndTime)).map(HistoricActivityInstance::getActivityId).orElse(null);
    boolean errorEvent = activities.stream().anyMatch(a -> ERROR_EVENTS.contains(a.getActivityType()));
    Optional<CallRecord> last = journal.take(id);
    String state = instance.getState();

    if (!errorEvent && !incident && HistoricProcessInstance.STATE_COMPLETED.equals(state)) {
      LOG.info("RUN {} {}: SUCCESS at '{}' in {} ms, last call {}", processKey, id, endEvent, ms(start),
          last.map(c -> c.task() + " " + c.httpStatus()).orElse("none"));
      return new RunResult(Status.SUCCESS, null, processKey, id, endEvent, ms(start), last.map(this::call).orElse(null), null, null);
    }
    String reason = errorEvent ? "BPMN_ERROR" : incident ? "INCIDENT" : "TERMINATED";
    RunResult.Error error = error(started.getVariables(), last, reason);
    LOG.warn("RUN {} {}: FAILURE ({}) at '{}' in {} ms: {} {}", processKey, id, reason, endEvent, ms(start), error.code(), error.message());
    return new RunResult(Status.FAILURE, reason, processKey, id, endEvent, ms(start), null, error, last.map(this::call).orElse(null));
  }

  /** The error the process caught (errorCode / errorMessage variables), else what the last call recorded. */
  private RunResult.Error error(VariableMap variables, Optional<CallRecord> last, String reason) {
    Object code = variables == null ? null : variables.get("errorCode");
    Object message = variables == null ? null : variables.get("errorMessage");
    Optional<CallRecord> failed = last.filter(c -> !c.succeeded());
    return new RunResult.Error(
        code != null ? code.toString() : failed.map(CallRecord::errorCode).orElse(reason),
        message != null ? message.toString() : failed.map(CallRecord::error).orElse(null),
        failed.map(CallRecord::task).orElse(null));
  }

  private HistoricProcessInstance waitForEnd(String id, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
      boolean incident = runtimeService.createIncidentQuery().processInstanceId(id).count() > 0;
      if ((instance != null && instance.getEndTime() != null) || incident || System.nanoTime() > deadline) {
        return instance;
      }
      try {
        Thread.sleep(POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return instance;
      }
    }
  }

  private String incidentMessage(String id) {
    return runtimeService.createIncidentQuery().processInstanceId(id).list().stream()
        .map(i -> i.getActivityId() + ": " + i.getIncidentMessage()).findFirst().orElse(null);
  }

  private Call call(CallRecord c) {
    return new Call(c.task(), c.method(), c.url(), c.httpStatus(),
        c.body() == null ? null : mapper.convertValue(c.body(), Object.class), c.mocked() ? Boolean.TRUE : null);
  }

  private Map<String, Object> engineVariables(Map<String, Object> variables) {
    Map<String, Object> result = new HashMap<>();
    variables.forEach((name, value) -> {
      if (value instanceof Map<?, ?> || value instanceof List<?>) {
        try {
          result.put(name, SpinValues.jsonValue(mapper.writeValueAsString(value)).create());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          throw new IllegalArgumentException("Variable '" + name + "' is not JSON: " + e.getMessage(), e);
        }
      } else {
        result.put(name, value);
      }
    });
    return result;
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) t = t.getCause();
    return t == e ? e.getMessage() : e.getMessage() + " (" + t.getMessage() + ")";
  }

  private static long ms(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
