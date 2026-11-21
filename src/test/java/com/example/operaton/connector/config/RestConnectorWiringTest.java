package com.example.operaton.connector.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.operaton.connector.engine.delegate.RestConnectorDelegate;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The models call ${restGet} ... ${restDelete}: one delegate bean per HTTP method. */
@SpringBootTest
class RestConnectorWiringTest {

  @Autowired ApplicationContext context;

  @Test
  void delegateBeanPerMethod() {
    Map<String, String> methods = context.getBeansOfType(RestConnectorDelegate.class).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().method().name()));
    assertThat(methods).containsExactlyInAnyOrderEntriesOf(Map.of(
        "restGet", "GET", "restPut", "PUT", "restUpdate", "PATCH", "restPost", "POST", "restDelete", "DELETE"));
  }

  @Test
  void propertiesAreBound(@Autowired RestConnectorProperties properties) {
    assertThat(properties.jwtVariable()).isEqualTo("accessToken");
    assertThat(properties.readTimeout()).hasSeconds(30);
  }
}
