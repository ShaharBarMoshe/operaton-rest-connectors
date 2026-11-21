package com.example.operaton.connector.core.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

/**
 * Everything one REST task asks for, as read from its inputs.
 *
 * @param sources           values deep-merged in order (variable values, JSON literals, ...)
 * @param paramsSchema      pasted schema / OpenAPI parameters for path, query and header params, or null
 * @param bodySchema        pasted schema for the request body, or null
 * @param responseSchema    pasted schema the response is shaped + validated with, or null
 * @param headers           static headers
 * @param bearerToken       sent as {@code Authorization: Bearer}, replacing other Authorization headers; or null
 * @param sourcesAsBody     without a body schema, send the merged sources as body (POST / PUT / PATCH)
 * @param mockResponse      test mode: answer with this instead of sending the request; null = real call
 */
public record RestCall(
    HttpMethod method,
    String url,
    List<?> sources,
    String paramsSchema,
    String bodySchema,
    String responseSchema,
    Map<String, String> headers,
    String bearerToken,
    boolean sourcesAsBody,
    TechnicalErrors technicalErrors,
    MockResponse mockResponse) {

  public RestCall {
    sources = sources == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sources)); // may hold nulls
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    technicalErrors = technicalErrors == null ? TechnicalErrors.EXCEPTION : technicalErrors;
  }
}
