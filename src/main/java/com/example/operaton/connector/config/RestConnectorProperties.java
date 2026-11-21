package com.example.operaton.connector.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rest-connector.*} in application.yaml.
 *
 * @param connectTimeout TCP connect timeout of every call
 * @param readTimeout    response timeout of every call
 * @param jwtVariable    execution variable holding the bearer token sent on every call; empty: none
 */
@ConfigurationProperties("rest-connector")
public record RestConnectorProperties(
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("accessToken") String jwtVariable) {
}
