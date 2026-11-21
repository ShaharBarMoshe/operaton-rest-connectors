package com.example.operaton.rest;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Use with {@code camunda:delegateExpression="${restUpdate}"}. HTTP PATCH. */
@Component("restUpdate")
public class RestUpdateDelegate extends AbstractRestDelegate {

  public RestUpdateDelegate(RestClient restConnectorClient, JsonComposer composer, JsonSchemaService schemas) {
    super(restConnectorClient, composer, schemas);
  }

  @Override
  protected HttpMethod method() {
    return HttpMethod.PATCH;
  }

  @Override
  protected boolean sendsBodyByDefault() {
    return true;
  }
}
