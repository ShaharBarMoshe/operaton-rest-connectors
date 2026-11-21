package com.example.operaton.connector.core.error;

/**
 * A business error of a call, with a code a process can catch (the engine adapter throws it as a
 * BPMN error): {@code HTTP_<status>}, {@link #REQUEST_INVALID}, {@link #RESPONSE_INVALID},
 * {@link #CONNECTION_FAILED}.
 */
public class ConnectorError extends RuntimeException {

  public static final String REQUEST_INVALID = "REQUEST_INVALID";
  public static final String RESPONSE_INVALID = "RESPONSE_INVALID";
  public static final String CONNECTION_FAILED = "CONNECTION_FAILED";

  private final String code;
  private final Integer status;
  private final java.net.URI uri;

  public ConnectorError(String code, String message, Integer status, Throwable cause) {
    this(code, message, status, null, cause);
  }

  private ConnectorError(String code, String message, Integer status, java.net.URI uri, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.status = status;
    this.uri = uri;
  }

  /** The same error, knowing the URL that was called. */
  public ConnectorError at(java.net.URI calledUri) {
    ConnectorError e = new ConnectorError(code, getMessage(), status, calledUri, getCause());
    e.setStackTrace(getStackTrace());
    return e;
  }

  /** The URL that was called, or null when the request was never built (REQUEST_INVALID). */
  public java.net.URI uri() {
    return uri;
  }

  public static ConnectorError http(int status, String body) {
    return new ConnectorError("HTTP_" + status, body, status, null);
  }

  public String code() {
    return code;
  }

  /** The HTTP status when there was a response, else null. */
  public Integer status() {
    return status;
  }
}
