package com.example.operaton.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestConnectorConfig {

  @Bean
  public RestClient restConnectorClient(
      @Value("${rest-connector.connect-timeout:5s}") Duration connectTimeout,
      @Value("${rest-connector.read-timeout:30s}") Duration readTimeout) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http); // supports PATCH
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(factory).build();
  }

  @Bean
  public JsonComposer jsonComposer() {
    return new JsonComposer(new ObjectMapper()); // private mapper, not exposed as the app's ObjectMapper
  }

  @Bean
  public JsonSchemaService jsonSchemaService(JsonComposer composer) {
    return new JsonSchemaService(composer);
  }
}
