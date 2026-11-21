package com.example.operaton.connector.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.operaton.connector.core.api.CallContext;
import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.api.TechnicalErrors;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.error.RestConnectorException;
import com.example.operaton.connector.core.request.RequestFactory;
import com.example.operaton.connector.core.response.ResponseMapper;
import com.example.operaton.connector.http.transport.HttpRequest;
import com.example.operaton.connector.http.transport.HttpResponse;
import com.example.operaton.connector.http.transport.HttpTransport;
import com.example.operaton.connector.http.transport.TransportException;
import com.example.operaton.connector.schema.JsonComposer;
import com.example.operaton.connector.schema.OpenApiSchemaCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** The core use case alone: a fake {@link HttpTransport}, no Spring context, no engine. */
class DefaultRestConnectorTest {

  final JsonComposer composer = new JsonComposer(new ObjectMapper());
  final List<HttpRequest> sent = new ArrayList<>();
  Function<HttpRequest, HttpResponse> answer = r -> new HttpResponse(200, "{\"ok\":true}", new HttpHeaders());

  final HttpTransport transport = request -> {
    sent.add(request);
    return answer.apply(request);
  };
  final OpenApiSchemaCompiler schemas = new OpenApiSchemaCompiler(composer);
  final RestConnector connector = new DefaultRestConnector(
      new RequestFactory(schemas, composer), transport, new ResponseMapper(schemas, composer));

  final CallContext context = new CallContext() {
    @Override public Object evaluate(String expression) { return "demo"; }
    @Override public Optional<Object> variable(String name) { return "customerId".equals(name) ? Optional.of(501) : Optional.empty(); }
    @Override public String location() { return "task"; }
  };

  RestCall call(HttpMethod method, String params, String body, String token, TechnicalErrors errors, List<?> sources) {
    return new RestCall(method, params == null ? "http://api/customers" : "http://api/customers/{customerId}", sources, params, body, null,
        Map.of("Authorization", "Basic old", "X-A", "1"), token, true, errors);
  }

  static final String PARAMS = """
      - {name: customerId, in: path, required: true, schema: {type: integer}}
      - {name: verbose, in: query, schema: {type: boolean, default: true}}""";

  @Test
  void buildsUriFromParamsAndVariables_tokenReplacesAuthorization() {
    RestResult result = connector.execute(call(HttpMethod.GET, PARAMS, null, "t.o.k", null, List.of()), context);

    HttpRequest request = sent.get(0);
    assertThat(request.uri()).hasToString("http://api/customers/501?verbose=true");
    assertThat(request.headers()).containsEntry("Authorization", "Bearer t.o.k").containsEntry("X-A", "1");
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().get("ok").asBoolean()).isTrue();
  }

  @Test
  void bodyFromSchema_orMergedSources() {
    String body = "{type: object, properties: {name: {type: string}, by: {type: string, x-expression: initiator}}}";
    connector.execute(call(HttpMethod.POST, null, body, null, null, List.of("{\"name\":\"a\",\"x\":1}")), context);
    assertThat(sent.get(0).body()).isEqualTo("{\"name\":\"a\",\"by\":\"demo\"}");
    assertThat(sent.get(0).headers()).containsEntry("Authorization", "Basic old");   // no token: static header kept

    connector.execute(call(HttpMethod.PUT, null, null, null, null, List.of("{\"a\":{\"b\":1}}", "{\"a\":{\"c\":2}}")), context);
    assertThat(sent.get(1).body()).isEqualTo("{\"a\":{\"b\":1,\"c\":2}}");
  }

  @Test
  void requestSchemaViolationIsABusinessError() {
    String body = "{type: object, required: [name], properties: {name: {type: string}}}";
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.POST, null, body, null, null, List.of()), context))
        .isInstanceOfSatisfying(ConnectorError.class, e -> assertThat(e.code()).isEqualTo(ConnectorError.REQUEST_INVALID));
    assertThat(sent).isEmpty();
  }

  @Test
  void statusMapping() {
    answer = r -> new HttpResponse(404, "{\"message\":\"no\"}", new HttpHeaders());
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.GET, null, null, null, null, List.of()), context))
        .isInstanceOfSatisfying(ConnectorError.class, e -> {
          assertThat(e.code()).isEqualTo("HTTP_404");
          assertThat(e.status()).isEqualTo(404);
        });

    answer = r -> new HttpResponse(503, "", new HttpHeaders());
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.GET, null, null, null, TechnicalErrors.EXCEPTION, List.of()), context))
        .isInstanceOf(RestConnectorException.class);
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.GET, null, null, null, TechnicalErrors.BPMN_ERROR, List.of()), context))
        .isInstanceOfSatisfying(ConnectorError.class, e -> assertThat(e.code()).isEqualTo("HTTP_503"));
  }

  @Test
  void noResponseAtAll() {
    answer = r -> { throw new TransportException("connect timed out", null); };
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.GET, null, null, null, TechnicalErrors.EXCEPTION, List.of()), context))
        .isInstanceOf(RestConnectorException.class).hasMessageContaining("connect timed out");
    assertThatThrownBy(() -> connector.execute(call(HttpMethod.GET, null, null, null, TechnicalErrors.BPMN_ERROR, List.of()), context))
        .isInstanceOfSatisfying(ConnectorError.class, e -> assertThat(e.code()).isEqualTo(ConnectorError.CONNECTION_FAILED));
  }
}
