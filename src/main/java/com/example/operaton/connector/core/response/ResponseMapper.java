package com.example.operaton.connector.core.response;

import com.example.operaton.connector.core.api.CallContext;
import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.api.TechnicalErrors;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.error.RestConnectorException;
import com.example.operaton.connector.core.request.RequestFactory;
import com.example.operaton.connector.http.transport.HttpResponse;
import com.example.operaton.connector.http.transport.TransportException;
import com.example.operaton.connector.schema.CompiledSchema;
import com.example.operaton.connector.schema.JsonComposer;
import com.example.operaton.connector.schema.SchemaCompiler;
import com.example.operaton.connector.schema.SchemaUse;
import com.example.operaton.connector.schema.SchemaViolationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Interprets what came back:
 * <ul>
 *   <li>4xx → {@link ConnectorError} {@code HTTP_<status>}</li>
 *   <li>5xx / no response → {@link RestConnectorException}, or {@code HTTP_5xx} / {@code CONNECTION_FAILED}
 *       with {@link TechnicalErrors#BPMN_ERROR}</li>
 *   <li>else the JSON body, shaped + validated with the response schema ({@code RESPONSE_INVALID})</li>
 * </ul>
 */
public class ResponseMapper {

  private final SchemaCompiler schemas;
  private final JsonComposer composer;

  public ResponseMapper(SchemaCompiler schemas, JsonComposer composer) {
    this.schemas = schemas;
    this.composer = composer;
  }

  public RestResult map(RestCall call, HttpResponse response, CallContext context) {
    int status = response.status();
    if (status >= 500) {
      if (call.technicalErrors() == TechnicalErrors.BPMN_ERROR) {
        throw ConnectorError.http(status, response.body());
      }
      throw new RestConnectorException(call.method() + " returned " + status + ": " + response.body());
    }
    if (status >= 400) {
      throw ConnectorError.http(status, response.body());
    }

    JsonNode json = parse(response.body());
    if (call.responseSchema() != null) {
      CompiledSchema schema = RequestFactory.compile(schemas, call.responseSchema(), SchemaUse.RESPONSE, "responseSchema", context);
      json = composer.shape(schema, json, context::evaluate);
      try {
        schemas.validate(schema, json, "Response");
      } catch (SchemaViolationException e) {
        throw new ConnectorError(ConnectorError.RESPONSE_INVALID, e.getMessage(), status, e);
      }
    }
    return new RestResult(status, json);
  }

  /** No response at all (timeout, connection refused, ...). */
  public RuntimeException noResponse(RestCall call, TransportException e) {
    if (call.technicalErrors() == TechnicalErrors.BPMN_ERROR) {
      return new ConnectorError(ConnectorError.CONNECTION_FAILED, e.getMessage(), null, e);
    }
    return new RestConnectorException(e.getMessage(), e);
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return MissingNode.getInstance();
    }
    try {
      return composer.parse(body);
    } catch (IllegalArgumentException notJson) {
      return composer.toJson(body); // plain text
    }
  }
}
