package com.example.operaton.connector.core.service;

import com.example.operaton.connector.core.api.CallContext;
import com.example.operaton.connector.core.api.RestCall;
import com.example.operaton.connector.core.api.RestConnector;
import com.example.operaton.connector.core.api.RestResult;
import com.example.operaton.connector.core.error.ConnectorError;
import com.example.operaton.connector.core.request.RequestFactory;
import com.example.operaton.connector.core.response.ResponseMapper;
import com.example.operaton.connector.http.transport.HttpRequest;
import com.example.operaton.connector.http.transport.HttpResponse;
import com.example.operaton.connector.http.transport.HttpTransport;
import com.example.operaton.connector.http.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

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
    boolean mocked = call.mockResponse() != null;
    HttpResponse response = mocked ? mock(call, request) : send(call, request);
    try {
      return responses.map(call, response, context).withCall(request.uri(), mocked);
    } catch (ConnectorError e) {
      throw e.at(request.uri());
    }
  }

  private HttpResponse send(RestCall call, HttpRequest request) {
    LOG.info("HTTP {} {}", request.method(), request.uri());
    LOG.debug("HTTP request body: {}", request.body());
    long start = System.nanoTime();
    try {
      HttpResponse response = transport.send(request);
      LOG.info("HTTP {} answered {} in {} ms ({} bytes)", request.method(), response.status(),
          (System.nanoTime() - start) / 1_000_000, response.body() == null ? 0 : response.body().length());
      LOG.debug("HTTP response body: {}", response.body());
      return response;
    } catch (TransportException e) {
      LOG.warn("HTTP {} {} failed after {} ms: {}", request.method(), request.uri(),
          (System.nanoTime() - start) / 1_000_000, e.getMessage());
      throw responses.noResponse(call, e);
    }
  }

  /** Test mode: nothing goes over the wire. */
  private static HttpResponse mock(RestCall call, HttpRequest request) {
    LOG.info("MOCK {} {} - not sent, answering {}", request.method(), request.uri(), call.mockResponse().status());
    LOG.debug("MOCK request body: {}", request.body());
    return new HttpResponse(call.mockResponse().status(), call.mockResponse().body(), new HttpHeaders());
  }
}
