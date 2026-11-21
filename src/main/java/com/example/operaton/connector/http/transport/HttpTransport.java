package com.example.operaton.connector.http.transport;

/**
 * Sends HTTP requests. Implementations return every response (also 4xx / 5xx) and throw
 * {@link TransportException} only when no response was received (timeout, connection refused, ...).
 */
public interface HttpTransport {

  HttpResponse send(HttpRequest request) throws TransportException;
}
