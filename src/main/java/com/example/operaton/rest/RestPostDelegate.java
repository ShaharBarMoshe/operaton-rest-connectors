package com.example.operaton.rest;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Use with {@code camunda:delegateExpression="${restPost}"}. HTTP POST. */
@Component("restPost")
public class RestPostDelegate extends AbstractRestDelegate {

  public RestPostDelegate(RestClient restConnectorClient, JsonComposer composer, JsonSchemaService schemas) {
    super(restConnectorClient, composer, schemas);
  }

  @Override
  protected HttpMethod method() {
    return HttpMethod.POST;
  }

  @Override
  protected boolean sendsBodyByDefault() {
    return true;
  }
}
