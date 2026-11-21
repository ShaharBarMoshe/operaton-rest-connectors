package com.example.operaton.connector.http.restclient;

import com.example.operaton.connector.http.transport.HttpRequest;
import com.example.operaton.connector.http.transport.HttpResponse;
import com.example.operaton.connector.http.transport.HttpTransport;
import com.example.operaton.connector.http.transport.TransportException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** {@link HttpTransport} on Spring's {@link RestClient}; JSON in, JSON accepted. */
public class RestClientHttpTransport implements HttpTransport {

  private final RestClient restClient;

  public RestClientHttpTransport(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public HttpResponse send(HttpRequest request) {
    try {
      RestClient.RequestBodySpec spec = restClient.method(request.method()).uri(request.uri())
          .accept(MediaType.APPLICATION_JSON)
          .headers(h -> request.headers().forEach(h::set));
      if (request.body() != null) {
        spec = spec.contentType(MediaType.APPLICATION_JSON).body(request.body());
      }
      return spec.exchange((req, res) -> new HttpResponse(res.getStatusCode().value(), readBody(res), res.getHeaders()), true);
    } catch (ResourceAccessException e) {
      throw new TransportException(request.method() + " " + request.uri() + " failed: " + e.getMessage(), e);
    }
  }

  private static String readBody(ClientHttpResponse res) {
    try {
      return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }
}
