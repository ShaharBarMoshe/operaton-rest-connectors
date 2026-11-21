package com.example.operaton.connector.http.transport;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpMethod;

/** One outgoing call. {@code body} is JSON text or null. */
public record HttpRequest(HttpMethod method, URI uri, Map<String, String> headers, String body) {
}
