package com.example.operaton.connector.core.api;

import com.fasterxml.jackson.databind.JsonNode;

/** A successful (1xx-3xx) call: the status and the response body (shaped by the response schema), MissingNode when empty. */
public record RestResult(int status, JsonNode body) {
}
