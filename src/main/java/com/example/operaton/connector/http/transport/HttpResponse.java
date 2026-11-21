package com.example.operaton.connector.http.transport;

import org.springframework.http.HttpHeaders;

/** What came back, whatever the status: error statuses are interpreted by the caller. */
public record HttpResponse(int status, String body, HttpHeaders headers) {
}
