package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.operaton.connector.schema.JsonComposerTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UserSyncProcessTest.FakeUserApi.class)
class UserSyncProcessTest {

  record Call(String method, String uri, String query, Map<String, String> headers, String body) {}

  static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  static final ObjectMapper MAPPER = new ObjectMapper();

  /** Stands in for the real user service. */
  @TestConfiguration
  @RestController
  @RequestMapping("/fake-api/users/{id}")
  static class FakeUserApi {

    @GetMapping
    ResponseEntity<String> get(@PathVariable long id, HttpServletRequest req) throws Exception {
      record(req, null);
      if (id == 404) {
        return ResponseEntity.status(404).body("{\"message\":\"no such user\"}");
      }
      if (id == 500) {
        return ResponseEntity.status(500).body("{\"message\":\"database down\"}");
      }
      JsonNode user = MAPPER.readTree(JsonComposerTest.OBJECT_1);
      ((com.fasterxml.jackson.databind.node.ObjectNode) user.get("user")).put("internalScore", 99); // stripped by responseSchema
      return ResponseEntity.ok(user.toString());
    }

    @PutMapping
    String put(@RequestBody String body, HttpServletRequest req) {
      record(req, body);
      return body;
    }

    @PatchMapping("/preferences")
    ResponseEntity<Void> patch(@RequestBody String body, HttpServletRequest req) {
      record(req, body);
      return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    ResponseEntity<Void> delete(HttpServletRequest req) {
      record(req, null);
      return ResponseEntity.noContent().build();
    }

    private void record(HttpServletRequest req, String body) {
      Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      java.util.Collections.list(req.getHeaderNames()).forEach(h -> headers.put(h, req.getHeader(h)));
      CALLS.add(new Call(req.getMethod(), req.getRequestURI(), req.getQueryString(), headers, body));
    }
  }

  @LocalServerPort int port;
  @Autowired RuntimeService runtimeService;
  @Autowired HistoryService historyService;
  @Autowired RepositoryService repositoryService;

  @BeforeEach
  void reset() {
    CALLS.clear();
  }

  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLXN5bmMifQ.c2ln";

  @Test
  void runsGetPutUpdateDelete() throws Exception {
    runSync("user-sync", TOKEN);
  }

  /**
   * The same model in the Operaton namespace (what Operaton Modeler writes on save) runs as well;
   * scripts/make-portable.py converts such a file back to the camunda namespace.
   */
  @Test
  void runsOperatonNamespaceCopy() throws Exception {
    String xml = Files.readString(Path.of("src/main/resources/bpmn/user-sync.bpmn"))
        .replace("http://camunda.org/schema/1.0/bpmn", "http://operaton.org/schema/1.0/bpmn")
        .replace("xmlns:camunda=", "xmlns:operaton=")
        .replace("camunda:", "operaton:")
        .replace("id=\"user-sync\"", "id=\"user-sync-operaton\"");
    assertThat(xml).doesNotContain("camunda.org/schema/1.0/bpmn");
    repositoryService.createDeployment().addString("user-sync-operaton.bpmn", xml).deploy();
    runSync("user-sync-operaton", "Bearer " + TOKEN); // a "Bearer " prefix in the variable is not doubled
  }

  @Test
  void http500EndsInErrorSubprocess() {
    ProcessInstanceWithVariables pi = runtimeService.createProcessInstanceByKey("user-sync").setVariables(Map.of(
        "apiBaseUrl", "http://localhost:" + port + "/fake-api",
        "userId", 500,
        "accessToken", TOKEN,
        "userChanges", JsonComposerTest.OBJECT_2)).executeWithVariablesInReturn();

    assertThat(CALLS).extracting(Call::method).containsExactly("GET");
    assertThat(StoredVariables.plain(pi.getVariables())).containsEntry("errorCode", "HTTP_500");
    assertThat((String) StoredVariables.plain(pi.getVariables()).get("errorMessage")).contains("database down");
    assertThat(StoredVariables.endEvent(historyService, pi.getId())).isEqualTo("errEndUnavailable");
    StoredVariables.assertNone(runtimeService, historyService, pi.getId());
  }

  private void runSync(String processKey, String accessToken) throws Exception {
    ProcessInstanceWithVariables pi = runtimeService.createProcessInstanceByKey(processKey).setVariables(Map.of(
        "apiBaseUrl", "http://localhost:" + port + "/fake-api",
        "accessToken", accessToken,
        "userId", 1042,
        "userChanges", JsonComposerTest.OBJECT_2,
        "initiator", "maya.admin",
        "deleteReason", "profile updated")).executeWithVariablesInReturn();

    assertThat(CALLS).extracting(Call::method).containsExactly("GET", "PUT", "PATCH", "DELETE");
    assertThat(CALLS).allSatisfy(c -> assertThat(c.headers()).containsEntry("Authorization", "Bearer " + TOKEN));

    Call get = CALLS.get(0);
    assertThat(get.uri()).isEqualTo("/fake-api/users/1042");
    assertThat(java.net.URLDecoder.decode(get.query(), "UTF-8"))
        .isEqualTo("fields=contact&fields=preferences&filter.theme=dark&filter.phone=+1-555-0148");
    assertThat(get.query()).contains("filter.phone=%2B1-555-0148"); // '+' strictly encoded
    assertThat(get.headers()).containsEntry("X-Tenant", "acme");

    Call put = CALLS.get(1);
    assertThat(put.uri()).isEqualTo("/fake-api/users/1042");
    assertThat(put.query()).isNull();
    assertThat(MAPPER.readTree(put.body())).isEqualTo(MAPPER.readTree(JsonComposerTest.EXPECTED_BODY));

    Call patch = CALLS.get(2);
    assertThat(patch.uri()).isEqualTo("/fake-api/users/1042/preferences");
    assertThat(MAPPER.readTree(patch.body())).isEqualTo(MAPPER.readTree(
        "{\"theme\":\"dark\",\"smsEnabled\":true,\"pushEnabled\":true,\"updatedBy\":\"maya.admin\"}"));
    assertThat(patch.headers()).containsEntry("X-Correlation-Id", pi.getId());

    Call delete = CALLS.get(3);
    assertThat(delete.uri()).isEqualTo("/fake-api/users/1042/sessions");
    assertThat(delete.query()).isEqualTo("reason=profile%20updated");
    assertThat(delete.body()).isNull();

    Map<String, Object> vars = StoredVariables.plain(pi.getVariables());
    assertThat(vars).containsEntry("putStatus", 200).containsEntry("deleteStatus", 204);
    @SuppressWarnings("unchecked")
    Map<String, Object> currentUser = (Map<String, Object>) ((Map<String, Object>) vars.get("currentUser")).get("user");
    assertThat(currentUser).containsEntry("id", 1042).doesNotContainKey("internalScore");
    assertThat(StoredVariables.endEvent(historyService, pi.getId())).isEqualTo("end");
    StoredVariables.assertNone(runtimeService, historyService, pi.getId());
  }

  @Test
  void http404OnGetEndsInErrorPath() {
    ProcessInstance pi = runtimeService.startProcessInstanceByKey("user-sync", Map.of(
        "apiBaseUrl", "http://localhost:" + port + "/fake-api",
        "userId", 404,
        "accessToken", TOKEN,
        "userChanges", JsonComposerTest.OBJECT_2));

    assertThat(CALLS).extracting(Call::method).containsExactly("GET");
    assertThat(StoredVariables.endEvent(historyService, pi.getId())).isEqualTo("endNotFound");
    StoredVariables.assertNone(runtimeService, historyService, pi.getId());
  }


}
