package com.example.operaton.connector.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

/**
 * A successful (1xx-3xx) call.
 *
 * @param status HTTP status (the mock status in test mode)
 * @param body   response body shaped by the response schema; MissingNode when empty
 * @param uri    the URL that was called (or would have been, in test mode)
 * @param mocked true when test mode answered instead of the service
 */
public record RestResult(int status, JsonNode body, URI uri, boolean mocked) {

  public RestResult(int status, JsonNode body) {
    this(status, body, null, false);
  }

  public RestResult withCall(URI calledUri, boolean wasMocked) {
    return new RestResult(status, body, calledUri, wasMocked);
  }
}
