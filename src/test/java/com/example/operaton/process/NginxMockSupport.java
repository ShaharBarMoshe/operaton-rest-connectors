package com.example.operaton.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Starts the nginx mock (mock/nginx) once per JVM and gives tests access to its recorded calls. */
abstract class NginxMockSupport {

  @SuppressWarnings("resource")
  static final GenericContainer<?> NGINX = new GenericContainer<>("nginx:1.29.3-alpine")
      .withCopyFileToContainer(MountableFile.forHostPath("mock/nginx/nginx.conf"), "/etc/nginx/nginx.conf")
      .withCopyFileToContainer(MountableFile.forHostPath("mock/nginx/njs/mock.js"), "/etc/nginx/njs/mock.js")
      .withCopyFileToContainer(MountableFile.forHostPath("mock/openapi/customer-api.yaml"), "/etc/nginx/openapi/customer-api.yaml")
      .withExposedPorts(8080)
      .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  static {
    NGINX.start(); // shared by all subclasses, stopped by Testcontainers' reaper at JVM exit
  }

  static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired RuntimeService runtimeService;
  @Autowired HistoryService historyService;

  RestClient mock;

  static String baseUrl() {
    return "http://" + NGINX.getHost() + ":" + NGINX.getMappedPort(8080);
  }

  @BeforeEach
  void resetMock() {
    mock = RestClient.create(baseUrl());
    mock.delete().uri("/__mock/calls").retrieve().toBodilessEntity();
  }

  JsonNode calls() throws Exception {
    return MAPPER.readTree(mock.get().uri("/__mock/calls").retrieve().body(String.class));
  }

  /** The end event the instance finished at (also inside the error event subprocess). */
  String endActivity(ProcessInstance pi) {
    return StoredVariables.endEvent(historyService, pi.getId());
  }

  /**
   * Starts a process and gets its final variables back in memory. Variables are not kept in history
   * (history level "activity") and are deleted when the instance completes, so this is the only way
   * to look at them afterwards.
   */
  ProcessInstanceWithVariables start(String processKey, Map<String, Object> variables) {
    return runtimeService.createProcessInstanceByKey(processKey).setVariables(variables).executeWithVariablesInReturn();
  }

  Map<String, Object> variables(ProcessInstanceWithVariables pi) {
    return StoredVariables.plain(pi.getVariables());
  }

  /** Nothing of the instance's variables (the token included) is left in runtime or history tables. */
  void assertNoVariablesStored(ProcessInstance pi) {
    StoredVariables.assertNone(runtimeService, historyService, pi.getId());
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }
}
