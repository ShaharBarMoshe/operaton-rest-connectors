package com.example.operaton.rest;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Use with {@code camunda:delegateExpression="${restGet}"}. HTTP GET. */
@Component("restGet")
public class RestGetDelegate extends AbstractRestDelegate {

  public RestGetDelegate(RestClient restConnectorClient, JsonComposer composer, JsonSchemaService schemas) {
    super(restConnectorClient, composer, schemas);
  }

  @Override
  protected HttpMethod method() {
    return HttpMethod.GET;
  }

}
