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
import org.springframework.http.HttpHeaders;
import org.slf4j.LoggerFactory;

/**
 * {@link RestConnector}: {@link RequestFactory} → {@link HttpTransport} → {@link ResponseMapper}.
 * With a {@link com.example.operaton.connector.core.api.MockResponse} (test mode) the transport is
 * skipped and the mock answer goes through the same response handling.
 */
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
    HttpRequest request = requests.create(call, context);     // built + validated in test mode too
    LOG.debug("[{}] body: {}", context.location(), request.body());
    return responses.map(call, call.mockResponse() != null ? mock(call, request, context) : send(call, request, context), context);
  }

  private HttpResponse send(RestCall call, HttpRequest request, CallContext context) {
    LOG.info("[{}] {} {}", context.location(), request.method(), request.uri());
    try {
      return transport.send(request);
    } catch (TransportException e) {
      throw responses.noResponse(call, e);
    }
  }

  /** Test mode: nothing goes over the wire. */
  private static HttpResponse mock(RestCall call, HttpRequest request, CallContext context) {
    LOG.info("[{}] MOCK {} {} - not sent, answering {}", context.location(), request.method(), request.uri(),
        call.mockResponse().status());
    return new HttpResponse(call.mockResponse().status(), call.mockResponse().body(), new HttpHeaders());
  }
}
