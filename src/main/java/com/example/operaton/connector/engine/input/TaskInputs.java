package com.example.operaton.connector.engine.input;

import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.error.RestConnectorException;
import com.example.operaton.connector.core.api.TechnicalErrors;
import com.example.operaton.connector.engine.auth.TokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.springframework.http.HttpMethod;

/**
 * Reads the input parameters of a REST service task ({@code camunda:inputOutput}) into a
 * {@link RestCall}. The names are the ones of the element templates
 * ({@code .camunda/element-templates/rest-connectors.json}).
 */
public class TaskInputs {

  public static final String URL = "url";
  public static final String SOURCES = "sources";
  public static final String PARAMS_SCHEMA = "paramsSchema";
  public static final String BODY_SCHEMA = "bodySchema";
  public static final String RESPONSE_SCHEMA = "responseSchema";
  public static final String HEADERS = "headers";
  public static final String TECHNICAL_ERRORS = "technicalErrors";
  public static final String RESULT_VARIABLE = "resultVariable";
  public static final String STATUS_VARIABLE = "statusVariable";

  private final TokenProvider tokens;
  private final ObjectMapper mapper = new ObjectMapper();

  public TaskInputs(TokenProvider tokens) {
    this.tokens = tokens;
  }

  public RestCall read(DelegateExecution execution, HttpMethod method, boolean sourcesAsBody) {
    TechnicalErrors technicalErrors;
    try {
      technicalErrors = TechnicalErrors.fromInput(optional(execution, TECHNICAL_ERRORS));
    } catch (IllegalArgumentException e) {
      throw new RestConnectorException("Input '" + TECHNICAL_ERRORS + "' on " + execution.getCurrentActivityId() + " " + e.getMessage());
    }
    String token;
    try {
      token = tokens.token(execution).orElse(null);
    } catch (RestConnectorException e) {
      throw new RestConnectorException(e.getMessage() + " (" + execution.getCurrentActivityId() + ")", e);
    }
    return new RestCall(method, required(execution, URL), sources(execution),
        blankToNull(optional(execution, PARAMS_SCHEMA)), blankToNull(optional(execution, BODY_SCHEMA)),
        blankToNull(optional(execution, RESPONSE_SCHEMA)), headers(execution), token, sourcesAsBody, technicalErrors);
  }

  public String resultVariable(DelegateExecution execution) {
    return blankToNull(optional(execution, RESULT_VARIABLE));
  }

  public String statusVariable(DelegateExecution execution) {
    return blankToNull(optional(execution, STATUS_VARIABLE));
  }

  /**
   * {@code sources}: a comma separated string of variable names ({@code currentUser, userChanges}),
   * or a {@code camunda:list} of names, JSON literals or {@code ${...}} values. Prefer names: then the
   * engine never stores copies of the objects in the {@code sources} local variable.
   */
  private List<?> sources(DelegateExecution execution) {
    Object value = execution.getVariableLocal(SOURCES);
    if (value == null) {
      return List.of();
    }
    List<?> items;
    if (value instanceof List<?> list) {
      items = list;
    } else if (value instanceof String s && !looksLikeJson(s)) {
      items = Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    } else {
      items = List.of(value);
    }
    return items.stream().map(item -> resolveSource(execution, item)).toList();
  }

  private static Object resolveSource(DelegateExecution execution, Object item) {
    if (item instanceof String s && !looksLikeJson(s)) {
      String name = s.trim();
      if (!execution.hasVariable(name)) {
        throw new RestConnectorException("Source variable '" + name + "' does not exist (" + execution.getCurrentActivityId() + ")");
      }
      return EngineValues.plain(execution.getVariable(name));
    }
    return EngineValues.plain(item);           // camunda:list item: JSON literal or ${...} value
  }

  /** {@code headers}: a {@code camunda:map}, or a JSON object string {@code {"X-A": "b"}}. */
  private Map<String, String> headers(DelegateExecution execution) {
    Object value = execution.getVariableLocal(HEADERS);
    Map<String, String> result = new HashMap<>();
    if (value instanceof String s && !s.isBlank()) {
      JsonNode json;
      try {
        json = mapper.readTree(s);
      } catch (Exception e) {
        throw new RestConnectorException("Input 'headers' must be a JSON object, got: " + s, e);
      }
      if (!json.isObject()) {
        throw new RestConnectorException("Input 'headers' must be a JSON object, got: " + s);
      }
      json.properties().forEach(e -> result.put(e.getKey(), e.getValue().asText()));
    } else if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
    }
    return result;
  }

  private static boolean looksLikeJson(String s) {
    String t = s.trim();
    return t.startsWith("{") || t.startsWith("[");
  }

  private static String required(DelegateExecution execution, String name) {
    String value = optional(execution, name);
    if (value == null || value.isBlank()) {
      throw new RestConnectorException("Missing input parameter '" + name + "' on " + execution.getCurrentActivityId());
    }
    return value;
  }

  private static String optional(DelegateExecution execution, String name) {
    Object value = execution.getVariableLocal(name);
    return value == null ? null : value.toString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
