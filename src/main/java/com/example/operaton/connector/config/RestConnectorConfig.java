package com.example.operaton.connector.config;

import com.example.operaton.connector.core.service.DefaultRestConnector;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.request.RequestFactory;
import com.example.operaton.connector.core.response.ResponseMapper;
import com.example.operaton.connector.engine.delegate.RestConnectorDelegate;
import com.example.operaton.connector.engine.input.TaskInputs;
import com.example.operaton.connector.engine.output.ResultVariables;
import com.example.operaton.connector.engine.trace.CallJournal;
import com.example.operaton.connector.engine.trace.InMemoryCallJournal;
import com.example.operaton.connector.engine.auth.TokenProvider;
import com.example.operaton.connector.engine.auth.VariableTokenProvider;
import com.example.operaton.connector.http.transport.HttpTransport;
import com.example.operaton.connector.http.restclient.RestClientHttpTransport;
import com.example.operaton.connector.schema.JsonComposer;
import com.example.operaton.connector.schema.OpenApiSchemaCompiler;
import com.example.operaton.connector.schema.SchemaCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the connector layers:
 * <pre>
 *   engine   RestConnectorDelegate ×5 (restGet ...): TaskInputs (+ TokenProvider) → RestConnector → ResultVariables
 *   core     RestConnector = RequestFactory → HttpTransport → ResponseMapper
 *   schema   SchemaCompiler, JsonComposer
 *   http     HttpTransport
 * </pre>
 * Every collaborator is an interface-typed bean: to change one (another HTTP client, a token
 * provider that fetches OAuth2 tokens, ...) declare your own bean of that type with {@code @Primary}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestConnectorProperties.class)
public class RestConnectorConfig {

  // ------------------------------------------------------------------ http

  @Bean
  public RestClient restConnectorClient(RestConnectorProperties properties) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http); // supports PATCH
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }

  @Bean
  public HttpTransport httpTransport(RestClient restConnectorClient) {
    return new RestClientHttpTransport(restConnectorClient);
  }

  // ------------------------------------------------------------------ schema

  @Bean
  public JsonComposer jsonComposer() {
    return new JsonComposer(new ObjectMapper()); // private mapper, not exposed as the app's ObjectMapper
  }

  @Bean
  public SchemaCompiler schemaCompiler(JsonComposer composer) {
    return new OpenApiSchemaCompiler(composer);
  }

  // ------------------------------------------------------------------ core

  @Bean
  public RequestFactory requestFactory(SchemaCompiler schemas, JsonComposer composer) {
    return new RequestFactory(schemas, composer);
  }

  @Bean
  public ResponseMapper responseMapper(SchemaCompiler schemas, JsonComposer composer) {
    return new ResponseMapper(schemas, composer);
  }

  @Bean
  public RestConnector restConnector(RequestFactory requests, HttpTransport transport, ResponseMapper responses) {
    return new DefaultRestConnector(requests, transport, responses);
  }

  // ------------------------------------------------------------------ engine

  @Bean
  public TokenProvider tokenProvider(RestConnectorProperties properties) {
    return new VariableTokenProvider(properties.jwtVariable());
  }

  @Bean
  public CallJournal callJournal(RestConnectorProperties properties) {
    return new InMemoryCallJournal(properties.journalSize());
  }

  @Bean
  public ResultVariables resultVariables(JsonComposer composer) {
    return new ResultVariables(composer);
  }

  @Bean
  public TaskInputs taskInputs(TokenProvider tokens) {
    return new TaskInputs(tokens);
  }

  /** {@code camunda:delegateExpression="${restGet}"}: params only, no body. */
  @Bean
  public RestConnectorDelegate restGet(TaskInputs inputs, RestConnector connector, ResultVariables results,
                                           CallJournal journal) {
    return new RestConnectorDelegate(HttpMethod.GET, false, inputs, connector, results, journal);
  }

  /** {@code ${restPut}}: replace; without bodySchema the merged sources are the body. */
  @Bean
  public RestConnectorDelegate restPut(TaskInputs inputs, RestConnector connector, ResultVariables results,
                                           CallJournal journal) {
    return new RestConnectorDelegate(HttpMethod.PUT, true, inputs, connector, results, journal);
  }

  /** {@code ${restUpdate}}: PATCH; without bodySchema the merged sources are the body. */
  @Bean
  public RestConnectorDelegate restUpdate(TaskInputs inputs, RestConnector connector, ResultVariables results,
                                           CallJournal journal) {
    return new RestConnectorDelegate(HttpMethod.PATCH, true, inputs, connector, results, journal);
  }

  /** {@code ${restPost}}: create; without bodySchema the merged sources are the body. */
  @Bean
  public RestConnectorDelegate restPost(TaskInputs inputs, RestConnector connector, ResultVariables results,
                                           CallJournal journal) {
    return new RestConnectorDelegate(HttpMethod.POST, true, inputs, connector, results, journal);
  }

  /** {@code ${restDelete}}: params only, no body. */
  @Bean
  public RestConnectorDelegate restDelete(TaskInputs inputs, RestConnector connector, ResultVariables results,
                                           CallJournal journal) {
    return new RestConnectorDelegate(HttpMethod.DELETE, false, inputs, connector, results, journal);
  }
}
