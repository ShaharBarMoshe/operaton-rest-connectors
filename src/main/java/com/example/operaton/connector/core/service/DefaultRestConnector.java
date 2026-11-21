package com.example.operaton.connector.core.service;

import com.example.operaton.connector.core.api.CallContext;
import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.request.RequestFactory;
import com.example.operaton.connector.core.response.ResponseMapper;
import com.example.operaton.connector.http.transport.HttpRequest;
import com.example.operaton.connector.http.transport.HttpResponse;
import com.example.operaton.connector.http.transport.HttpTransport;
import com.example.operaton.connector.http.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link RestConnector}: {@link RequestFactory} → {@link HttpTransport} → {@link ResponseMapper}. */
public class DefaultRestConnector implements RestConnector {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultRestConnector.class);

  private final RequestFactory requests;
  private final HttpTransport transport;
  private final ResponseMapper responses;

  public DefaultRestConnector(RequestFactory requests, HttpTransport transport, ResponseMapper responses) {
    this.requests = requests;
    this.transport = transport;
    this.responses = responses;
  }

  @Override
  public RestResult execute(RestCall call, CallContext context) {
    HttpRequest request = requests.create(call, context);
    LOG.info("[{}] {} {}", context.location(), request.method(), request.uri());
    LOG.debug("[{}] body: {}", context.location(), request.body());
    HttpResponse response;
    try {
      response = transport.send(request);
    } catch (TransportException e) {
      throw responses.noResponse(call, e);
    }
    return responses.map(call, response, context);
  }
}
