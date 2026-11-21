package com.example.operaton.connector.core.request;

import com.example.operaton.connector.core.api.CallContext;
import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.error.RestConnectorException;
import com.example.operaton.connector.http.transport.HttpRequest;
import com.example.operaton.connector.http.params.HttpRequestParams;
import com.example.operaton.connector.schema.CompiledSchema;
import com.example.operaton.connector.schema.JsonComposer;
import com.example.operaton.connector.schema.SchemaCompiler;
import com.example.operaton.connector.schema.SchemaUse;
import com.example.operaton.connector.schema.SchemaViolationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;

/**
 * Builds the outgoing {@link HttpRequest} of a {@link RestCall}: deep-merges the sources, shapes and
 * validates params and body with the pasted schemas, fills the URL, adds headers and the token.
 */
public class RequestFactory {

  private final SchemaCompiler schemas;
  private final JsonComposer composer;

  public RequestFactory(SchemaCompiler schemas, JsonComposer composer) {
    this.schemas = schemas;
    this.composer = composer;
  }

  /** @throws ConnectorError {@link ConnectorError#REQUEST_INVALID} when params / body break their schema */
  public HttpRequest create(RestCall call, CallContext context) {
    List<JsonNode> sources = call.sources().stream().map(composer::toJson).toList();
    HttpRequestParams params = params(call, sources, context);
    Map<String, String> headers = new HashMap<>(call.headers());
    headers.putAll(params.headers());
    if (call.bearerToken() != null) {
      headers.keySet().removeIf(HttpHeaders.AUTHORIZATION::equalsIgnoreCase);   // the token wins
      headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + call.bearerToken());
    }
    return new HttpRequest(call.method(), uri(call.url(), params), headers, body(call, sources, context));
  }

  /** path / query / header params; a param missing in the sources is looked up as a variable. */
  private HttpRequestParams params(RestCall call, List<JsonNode> sources, CallContext context) {
    if (call.paramsSchema() == null) {
      return HttpRequestParams.empty();
    }
    CompiledSchema schema = compile(schemas, call.paramsSchema(), SchemaUse.PARAMS, "paramsSchema", context);
    JsonNode shaped = build(schema, sources, context::evaluate, name -> context.variable(name).orElse(null), "Request params");
    return HttpRequestParams.from(schema.node(), shaped);
  }

  private String body(RestCall call, List<JsonNode> sources, CallContext context) {
    if (call.bodySchema() != null) {
      CompiledSchema schema = compile(schemas, call.bodySchema(), SchemaUse.REQUEST, "bodySchema", context);
      return build(schema, sources, context::evaluate, null, "Request body").toString();
    }
    if (call.sourcesAsBody()) {
      JsonNode merged = composer.merge(sources);
      return merged.isMissingNode() ? null : merged.toString();
    }
    return null;
  }

  private JsonNode build(CompiledSchema schema, List<JsonNode> sources, Function<String, Object> expressions,
                         Function<String, Object> fallback, String what) {
    try {
      JsonNode shaped = composer.shape(schema, sources, expressions, fallback);
      schemas.validate(schema, shaped, what);
      return shaped;
    } catch (SchemaViolationException e) {
      throw new ConnectorError(ConnectorError.REQUEST_INVALID, e.getMessage(), null, e);
    }
  }

  private static URI uri(String url, HttpRequestParams params) {
    try {
      return params.toUri(url);
    } catch (IllegalArgumentException e) {
      throw new RestConnectorException("Cannot build URL from '" + url + "': " + e.getMessage(), e);
    }
  }

  /** Compiles a pasted schema; a broken one is a modelling mistake (exception, not a BPMN error). */
  public static CompiledSchema compile(SchemaCompiler schemas, String text, SchemaUse use, String input, CallContext context) {
    try {
      return schemas.compile(text, use);
    } catch (IllegalArgumentException e) {
      throw new RestConnectorException("Input '" + input + "' on " + context.location() + ": " + e.getMessage(), e);
    }
  }
}
