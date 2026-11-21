package com.example.operaton.rest;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Use with {@code camunda:delegateExpression="${restPut}"}. HTTP PUT. */
@Component("restPut")
public class RestPutDelegate extends AbstractRestDelegate {

  public RestPutDelegate(RestClient restConnectorClient, JsonComposer composer, JsonSchemaService schemas) {
    super(restConnectorClient, composer, schemas);
  }

  @Override
  protected HttpMethod method() {
    return HttpMethod.PUT;
  }

  @Override
  protected boolean sendsBodyByDefault() {
    return true;
  }
}
