package com.example.operaton.rest;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Use with {@code camunda:delegateExpression="${restDelete}"}. HTTP DELETE. */
@Component("restDelete")
public class RestDeleteDelegate extends AbstractRestDelegate {

  public RestDeleteDelegate(RestClient restConnectorClient, JsonComposer composer, JsonSchemaService schemas) {
    super(restConnectorClient, composer, schemas);
  }

  @Override
  protected HttpMethod method() {
    return HttpMethod.DELETE;
  }

}
