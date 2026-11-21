package com.example.operaton.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.variable.Variables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Generic REST service-task connector. Configure it with input parameters on the service task
 * ({@code camunda:inputOutput}):
 *
 * <table>
 *   <tr><td>url</td><td>required. May contain {placeholders} filled from params with x-in=path</td></tr>
 *   <tr><td>sources</td><td>variable names ("a, b"), or a list of names / JSON literals / ${expr} values; deep-merged in order</td></tr>
 *   <tr><td>bodySchema</td><td>inline JSON Schema (string) used to build + validate the body</td></tr>
 *   <tr><td>paramsSchema</td><td>inline JSON Schema used to build path/query/header params (x-in)</td></tr>
 *   <tr><td>responseSchema</td><td>optional inline JSON Schema: shape + validate the response</td></tr>
 *   <tr><td>headers</td><td>optional static headers: camunda:map or JSON object string</td></tr>
 *   <tr><td>jwt</td><td>optional bearer token → {@code Authorization: Bearer <token>}: the name of the variable
 *       holding it (preferred, no copy of the token is stored), or the token itself</td></tr>
 *   <tr><td>resultVariable</td><td>optional: process variable receiving the (JSON) response</td></tr>
 *   <tr><td>statusVariable</td><td>optional: process variable receiving the HTTP status</td></tr>
 * </table>
 *
 * Errors: 4xx → {@code BpmnError("HTTP_<status>")}, schema problems → {@code BpmnError("REQUEST_INVALID")}
 * / {@code BpmnError("RESPONSE_INVALID")}; 5xx / I/O → exception (incident, async retries apply).
 */
public abstract class AbstractRestDelegate implements JavaDelegate {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractRestDelegate.class);

  public static final String URL = "url";
  public static final String SOURCES = "sources";
  public static final String BODY_SCHEMA = "bodySchema";
  public static final String PARAMS_SCHEMA = "paramsSchema";
  public static final String RESPONSE_SCHEMA = "responseSchema";
  public static final String HEADERS = "headers";
  public static final String JWT = "jwt";
  public static final String RESULT_VARIABLE = "resultVariable";
  public static final String STATUS_VARIABLE = "statusVariable";

  protected final RestClient restClient;
  protected final JsonComposer composer;
  protected final JsonSchemaService schemas;

  protected AbstractRestDelegate(RestClient restClient, JsonComposer composer, JsonSchemaService schemas) {
    this.restClient = restClient;
    this.composer = composer;
    this.schemas = schemas;
  }

  protected abstract HttpMethod method();

  /** Whether the merged sources are sent as body when no bodySchema is configured. */
  protected boolean sendsBodyByDefault() {
    return false;
  }

  @Override
  public void execute(DelegateExecution execution) {
    String url = requiredString(execution, URL);
    List<JsonNode> sources = sourcesOf(execution).stream().map(composer::toJson).toList();
    JsonNode merged = composer.merge(sources);

    // ---- params (path / query / header)
    HttpRequestParams params = HttpRequestParams.empty();
    String paramsSchemaRef = optionalString(execution, PARAMS_SCHEMA);
    if (paramsSchemaRef != null) {
      JsonSchemaService.Loaded schema = schemas.load(paramsSchemaRef);
      JsonNode shaped = buildAndValidate(schema, sources, execution, "Request params");
      params = HttpRequestParams.from(schema.node(), shaped);
    }

    // ---- body
    String body = null;
    String bodySchemaRef = optionalString(execution, BODY_SCHEMA);
    if (bodySchemaRef != null) {
      body = buildAndValidate(schemas.load(bodySchemaRef), sources, execution, "Request body").toString();
    } else if (sendsBodyByDefault() && !merged.isMissingNode()) {
      body = merged.toString();
    }

    URI uri = buildUri(url, params);
    Map<String, String> headers = new HashMap<>(staticHeaders(execution));
    headers.putAll(params.headers());
    String token = bearerToken(execution);
    if (token != null) {
      headers.keySet().removeIf(HttpHeaders.AUTHORIZATION::equalsIgnoreCase); // jwt wins
      headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    LOG.info("[{}] {} {}", execution.getCurrentActivityId(), method(), uri);
    LOG.debug("[{}] body: {}", execution.getCurrentActivityId(), body);

    RawResponse response = send(uri, headers, body);
    handleResponse(execution, response);
  }

  // ------------------------------------------------------------------ building

  private JsonNode buildAndValidate(JsonSchemaService.Loaded schema, List<JsonNode> sources,
                                    DelegateExecution execution, String what) {
    try {
      JsonNode shaped = composer.shape(schema.node(), sources, expr -> evaluate(expr, execution));
      schemas.validate(schema, shaped, what);
      return shaped;
    } catch (SchemaViolationException e) {
      throw new BpmnError("REQUEST_INVALID", e.getMessage(), e);
    }
  }

  private URI buildUri(String url, HttpRequestParams params) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
    Map<String, Object> vars = new HashMap<>(params.path());
    int i = 0;
    for (Map.Entry<String, List<String>> q : params.query().entrySet()) {
      for (String v : q.getValue()) {
        String key = "__q" + i++;       // values as URI vars → strictly encoded ('+', '&', ...)
        vars.put(key, v);
        builder.queryParam(q.getKey(), "{" + key + "}");
      }
    }
    try {
      return builder.encode().buildAndExpand(vars).toUri();
    } catch (IllegalArgumentException e) {
      throw new RestConnectorException("Cannot build URL from '" + url + "': " + e.getMessage(), e);
    }
  }

  // ------------------------------------------------------------------ http

  protected record RawResponse(int status, String body, HttpHeaders headers) {}

  private RawResponse send(URI uri, Map<String, String> headers, String body) {
    try {
      RestClient.RequestBodySpec spec = restClient.method(method()).uri(uri)
          .accept(MediaType.APPLICATION_JSON)
          .headers(h -> headers.forEach(h::set));
      if (body != null) {
        spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return spec.exchange((req, res) -> new RawResponse(
          res.getStatusCode().value(),
          readBody(res),
          res.getHeaders()), true);
    } catch (ResourceAccessException e) {
      throw new RestConnectorException(method() + " " + uri + " failed: " + e.getMessage(), e);
    }
  }

  private static String readBody(org.springframework.http.client.ClientHttpResponse res) {
    try {
      return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  protected void handleResponse(DelegateExecution execution, RawResponse response) {
    String statusVariable = optionalString(execution, STATUS_VARIABLE);
    if (statusVariable != null) {
      execution.setVariable(statusVariable, response.status());
    }
    int status = response.status();
    if (status >= 500) {
      throw new RestConnectorException(method() + " returned " + status + ": " + response.body());
    }
    if (status >= 400) {
      throw new BpmnError("HTTP_" + status, response.body());
    }

    JsonNode json = parseResponse(response);
    String responseSchemaRef = optionalString(execution, RESPONSE_SCHEMA);
    if (responseSchemaRef != null) {
      JsonSchemaService.Loaded schema = schemas.load(responseSchemaRef);
      json = composer.shape(schema.node(), json, expr -> evaluate(expr, execution));
      try {
        schemas.validate(schema, json, "Response");
      } catch (SchemaViolationException e) {
        throw new BpmnError("RESPONSE_INVALID", e.getMessage(), e);
      }
    }

    String resultVariable = optionalString(execution, RESULT_VARIABLE);
    if (resultVariable != null) {
      Object value = composer.toJava(json);
      if (value instanceof Map || value instanceof Collection) {
        // stored as JSON (spin) → readable in Cockpit/REST, usable as ${var.field} and as a source again
        execution.setVariable(resultVariable, Variables.objectValue(value)
            .serializationDataFormat(Variables.SerializationDataFormats.JSON).create());
      } else {
        execution.setVariable(resultVariable, value);
      }
    }
  }

  private JsonNode parseResponse(RawResponse response) {
    String body = response.body();
    if (body == null || body.isBlank()) {
      return MissingNode.getInstance();
    }
    try {
      return composer.parse(body);
    } catch (IllegalArgumentException notJson) {
      return composer.toJson(body); // plain text
    }
  }

  // ------------------------------------------------------------------ inputs

  /**
   * {@code sources} may be
   * <ul>
   *   <li>a comma separated string of variable names: {@code currentUser, userChanges}</li>
   *   <li>a {@code camunda:list} whose items are variable names, JSON literals, or values of
   *       {@code ${...}} expressions</li>
   * </ul>
   * Prefer variable names: then the engine never stores copies of the objects in the
   * {@code sources} local variable (and Spin {@code Json} variables, which are not
   * Java-serializable inside a list, work).
   */
  private List<?> sourcesOf(DelegateExecution execution) {
    Object value = execution.getVariableLocal(SOURCES);
    if (value == null) {
      return List.of();
    }
    List<?> items;
    if (value instanceof List<?> list) {
      items = list;
    } else if (value instanceof String s && !looksLikeJson(s)) {
      items = java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    } else {
      items = List.of(value);
    }
    return items.stream().map(item -> resolveSourceItem(execution, item)).toList();
  }

  private Object resolveSourceItem(DelegateExecution execution, Object item) {
    if (item instanceof String s && !looksLikeJson(s)) {
      String name = s.trim();
      if (!execution.hasVariable(name)) {
        throw new RestConnectorException("Source variable '" + name + "' does not exist (" + execution.getCurrentActivityId() + ")");
      }
      return execution.getVariable(name);
    }
    return item;
  }

  private static boolean looksLikeJson(String s) {
    String t = s.trim();
    return t.startsWith("{") || t.startsWith("[");
  }

  /** {@code headers}: a {@code camunda:map}, or a JSON object string {@code {"X-A": "b"}}. */
  @SuppressWarnings("unchecked")
  private Map<String, String> staticHeaders(DelegateExecution execution) {
    Object value = execution.getVariableLocal(HEADERS);
    if (value instanceof String s && !s.isBlank()) {
      JsonNode json = composer.parse(s);
      if (!json.isObject()) {
        throw new RestConnectorException("Input 'headers' must be a JSON object, got: " + s);
      }
      Map<String, String> result = new HashMap<>();
      json.properties().forEach(e -> result.put(e.getKey(), e.getValue().asText()));
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, String> result = new HashMap<>();
      ((Map<Object, Object>) map).forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
      return result;
    }
    return Map.of();
  }

  private static final java.util.regex.Pattern VARIABLE_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /**
   * {@code jwt}: a variable name ({@code accessToken}) → the token is read from that variable now,
   * so the engine never stores a copy of it in the task's input (history); otherwise the value
   * itself is the token (a literal or the result of an expression). A leading "Bearer " is accepted.
   */
  private String bearerToken(DelegateExecution execution) {
    String value = optionalString(execution, JWT);
    if (value == null || value.isBlank()) {
      return null;
    }
    String token = value.trim();
    if (VARIABLE_NAME.matcher(token).matches()) {   // a JWT always contains dots, a variable name never
      if (!execution.hasVariable(token)) {
        throw new RestConnectorException("JWT variable '" + token + "' does not exist (" + execution.getCurrentActivityId() + ")");
      }
      Object v = execution.getVariable(token);
      token = v == null ? "" : v.toString().trim();
      if (token.isEmpty()) {
        throw new RestConnectorException("JWT variable '" + value.trim() + "' is empty (" + execution.getCurrentActivityId() + ")");
      }
    }
    if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
      token = token.substring(7).trim();
    }
    if (token.isEmpty() || token.chars().anyMatch(c -> c <= ' ')) {
      throw new RestConnectorException("Input 'jwt' on " + execution.getCurrentActivityId() + " is not a valid bearer token");
    }
    return token;
  }

  private Object evaluate(String expression, DelegateExecution execution) {
    return Context.getProcessEngineConfiguration().getExpressionManager()
        .createExpression(expression).getValue(execution);
  }

  private static String requiredString(DelegateExecution execution, String name) {
    String value = optionalString(execution, name);
    if (value == null || value.isBlank()) {
      throw new RestConnectorException("Missing input parameter '" + name + "' on " + execution.getCurrentActivityId());
    }
    return value;
  }

  private static String optionalString(DelegateExecution execution, String name) {
    Object value = execution.getVariableLocal(name);
    return value == null ? null : value.toString();
  }
}
