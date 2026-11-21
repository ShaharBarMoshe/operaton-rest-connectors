package com.example.operaton.rest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a shaped "params" document into path / query / header values, using the
 * {@code "x-in"} keyword of each top-level property in the params schema
 * (default: {@code query}).
 *
 * <ul>
 *   <li>arrays → repeated query params ({@code ?role=a&role=b}) / comma joined headers</li>
 *   <li>nested objects → dot-flattened query params ({@code ?filter.theme=dark})</li>
 * </ul>
 */
public record HttpRequestParams(
    Map<String, String> path,
    Map<String, List<String>> query,
    Map<String, String> headers) {

  public static HttpRequestParams empty() {
    return new HttpRequestParams(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
  }

  public static HttpRequestParams from(JsonNode schema, JsonNode params) {
    HttpRequestParams result = empty();
    if (params == null || !params.isObject()) {
      return result;
    }
    params.properties().forEach(e -> {
      String name = e.getKey();
      JsonNode value = e.getValue();
      if (value.isNull()) {
        return;
      }
      String in = schema.path("properties").path(name).path(JsonSchemaService.X_IN).asText("query");
      switch (in) {
        case "path" -> result.path.put(name, scalar(value, name));
        case "header" -> result.headers.put(name, value.isArray() ? joinArray(value) : scalar(value, name));
        case "query" -> flattenQuery(name, value, result.query);
        default -> throw new IllegalArgumentException("Unsupported x-in '" + in + "' on parameter " + name);
      }
    });
    return result;
  }

  private static void flattenQuery(String name, JsonNode value, Map<String, List<String>> out) {
    if (value.isObject()) {
      value.properties().forEach(e -> flattenQuery(name + "." + e.getKey(), e.getValue(), out));
    } else if (value.isArray()) {
      value.forEach(v -> flattenQuery(name, v, out));
    } else if (!value.isNull()) {
      out.computeIfAbsent(name, k -> new ArrayList<>()).add(value.asText());
    }
  }

  private static String joinArray(JsonNode array) {
    List<String> parts = new ArrayList<>();
    array.forEach(v -> parts.add(v.asText()));
    return String.join(",", parts);
  }

  private static String scalar(JsonNode value, String name) {
    if (!value.isValueNode()) {
      throw new IllegalArgumentException("Parameter '" + name + "' must be a scalar, got " + value);
    }
    return value.asText();
  }
}
