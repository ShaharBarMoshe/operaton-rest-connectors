package com.example.operaton.connector.http.transport;

/** No HTTP response at all: timeout, DNS, connection refused, broken connection. */
public class TransportException extends RuntimeException {

  public TransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
