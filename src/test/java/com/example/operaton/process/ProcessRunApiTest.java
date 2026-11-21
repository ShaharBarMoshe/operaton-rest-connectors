package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/** POST /api/process/{key}/run: start, wait for the end, report SUCCESS (+ last response) or FAILURE. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ProcessRunApiTest extends NginxMockSupport {

  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl";

  @LocalServerPort int port;
  @Autowired RepositoryService repositoryService;

  JsonNode run(String key, Map<String, Object> variables) throws Exception {
    return run(key, variables, "");
  }

  JsonNode run(String key, Map<String, Object> variables, String query) throws Exception {
    String body = RestClient.create("http://localhost:" + port).post().uri("/api/process/" + key + "/run" + query)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(MAPPER.writeValueAsString(Map.of("variables", variables)))
        .retrieve().body(String.class);
    return MAPPER.readTree(body);
  }

  Map<String, Object> snapshotVars(Object customerId) {
    return Map.of("apiBaseUrl", baseUrl() + "/api", "customerId", customerId, "accessToken", TOKEN, "initiator", "demo");
  }

  @Test
  void successReturnsTheLastCallsResponse() throws Exception {
    JsonNode result = run("customer-snapshot", snapshotVars(501));

    assertThat(result.get("status").asText()).isEqualTo("SUCCESS");
    assertThat(result.get("endEvent").asText()).isEqualTo("end");
    JsonNode response = result.get("response");
    assertThat(response.get("task").asText()).isEqualTo("postSnapshot");
    assertThat(response.get("method").asText()).isEqualTo("POST");
    assertThat(response.get("url").asText()).endsWith("/api/customer-snapshots");
    assertThat(response.get("httpStatus").asInt()).isEqualTo(201);
    assertThat(response.at("/body/id").asText()).isEqualTo("s-1");
    assertThat(response.at("/body/account/tier").asText()).isEqualTo("platinum");
    assertThat(result.has("error")).isFalse();
  }

  @Test
  void mockModeRunsWithoutTheService() throws Exception {
    Map<String, Object> vars = new java.util.HashMap<>(snapshotVars(501));
    vars.put("useMockData", true);
    JsonNode result = run("customer-snapshot", vars);

    assertThat(result.get("status").asText()).isEqualTo("SUCCESS");
    assertThat(result.at("/response/body/id").asText()).isEqualTo("s-9001");
    assertThat(result.at("/response/mocked").asBoolean()).isTrue();
    assertThat(calls()).isEmpty();
  }

  @Test
  void bpmnErrorIsAFailure() throws Exception {
    JsonNode result = run("customer-snapshot", snapshotVars(999));     // unknown customer → 404

    assertThat(result.get("status").asText()).isEqualTo("FAILURE");
    assertThat(result.get("reason").asText()).isEqualTo("BPMN_ERROR");
    assertThat(result.get("endEvent").asText()).isEqualTo("errEndNotFound");
    assertThat(result.at("/error/code").asText()).isEqualTo("HTTP_404");
    assertThat(result.at("/error/message").asText()).contains("no profile for customer 999");
    assertThat(result.at("/lastCall/httpStatus").asInt()).isEqualTo(404);
    assertThat(result.has("response")).isFalse();
  }

  @Test
  void errorBoundaryEventIsAFailureToo() throws Exception {
    JsonNode result = run("user-sync", Map.of("apiBaseUrl", baseUrl() + "/api", "userId", 7, "accessToken", TOKEN,
        "userChanges", Map.of("user", Map.of("name", "x"))));     // a JSON object → Json variable

    assertThat(result.get("status").asText()).isEqualTo("FAILURE");
    assertThat(result.get("endEvent").asText()).isEqualTo("endNotFound");
    assertThat(result.at("/error/code").asText()).isEqualTo("HTTP_404");
    assertThat(result.at("/error/task").asText()).isEqualTo("getUser");
  }

  @Test
  void technicalErrorAtStartIsAFailure() throws Exception {
    JsonNode result = run("customer-snapshot", Map.of("customerId", 501));     // no apiBaseUrl

    assertThat(result.get("status").asText()).isEqualTo("FAILURE");
    assertThat(result.get("reason").asText()).isEqualTo("EXCEPTION");
    assertThat(result.at("/error/message").asText()).contains("apiBaseUrl");
    assertThat(result.has("processInstanceId")).isFalse();
  }

  @Test
  void waitsForAsynchronousContinuations() throws Exception {
    // same model, but the POST runs in a job: the start returns before the instance has ended
    String model = new String(getClass().getResourceAsStream("/bpmn/customer-snapshot.bpmn").readAllBytes())
        .replace("id=\"customer-snapshot\"", "id=\"customer-snapshot-async\"")
        .replace("<bpmn:serviceTask id=\"postSnapshot\"", "<bpmn:serviceTask id=\"postSnapshot\" camunda:asyncBefore=\"true\"");
    repositoryService.createDeployment().addString("customer-snapshot-async.bpmn", model).deploy();

    JsonNode result = run("customer-snapshot-async", snapshotVars(501));

    assertThat(result.get("status").asText()).isEqualTo("SUCCESS");
    assertThat(result.at("/response/task").asText()).isEqualTo("postSnapshot");
    assertThat(result.at("/response/body/id").asText()).startsWith("s-");
  }

  @Test
  void notEndedInTimeIsAFailure() throws Exception {
    repositoryService.createDeployment().addModelInstance("waits.bpmn", Bpmn.createExecutableProcess("waits-an-hour")
        .operatonHistoryTimeToLive(1).startEvent().intermediateCatchEvent().timerWithDuration("PT1H").endEvent().done()).deploy();

    JsonNode result = run("waits-an-hour", Map.of(), "?timeoutSeconds=1");

    assertThat(result.get("status").asText()).isEqualTo("FAILURE");
    assertThat(result.get("reason").asText()).isEqualTo("TIMEOUT");
    runtimeService.deleteProcessInstance(result.get("processInstanceId").asText(), "test done");
  }

  @Test
  void unknownProcessIs404() {
    HttpStatusCode status = RestClient.create("http://localhost:" + port).post().uri("/api/process/nope/run")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}")
        .exchange((req, res) -> res.getStatusCode());
    assertThat(status.value()).isEqualTo(404);
  }

  @Test
  void everyStepOfATaskIsLogged(CapturedOutput output) throws Exception {
    // a schema nobody compiled before, so its parsing is logged too
    String schema = "{type: object, description: '" + UUID.randomUUID() + "', required: [name], "
        + "properties: {name: {type: string}, city: {type: string}}}";
    repositoryService.createDeployment().addModelInstance("logged.bpmn", Bpmn.createExecutableProcess("logged")
        .operatonHistoryTimeToLive(1).startEvent()
        .serviceTask("postContact").name("POST contact").operatonDelegateExpression("${restPost}")
          .operatonInputParameter("url", "${apiBaseUrl}/contacts")
          .operatonInputParameter("sources", "a, b")
          .operatonInputParameter("bodySchema", schema)
          .operatonInputParameter("resultVariable", "contact")
        .endEvent().done()).deploy();

    JsonNode result = run("logged", Map.of("apiBaseUrl", baseUrl() + "/api", "accessToken", TOKEN,
        "a", Map.of("name", "Noa", "noise", 1), "b", Map.of("city", "Haifa")));
    assertThat(result.get("status").asText()).isEqualTo("SUCCESS");

    String pi = result.get("processInstanceId").asText();
    String log = output.getOut();
    assertThat(log).contains(
        "[" + pi + " postContact] ",                                   // MDC: instance + task on each line
        "START POST 'POST contact'",
        "Merge: 2 source(s) -> object {",                             // key order of Map.of varies
        "Schema (REQUEST): parsed",
        "Request body: valid",
        "Body: built by bodySchema -> object {name, city}",
        "HTTP POST http://",
        "answered 201",
        "Response 201: object {id, name, city}",
        "END POST 'POST contact' 201",
        "RUN logged " + pi + ": SUCCESS");
    assertThat(log).doesNotContain(TOKEN);

    // a rejected request is logged with its reason
    run("logged", Map.of("apiBaseUrl", baseUrl() + "/api", "a", Map.of("city", "x"), "b", Map.of()));
    assertThat(output.getOut()).contains("Request body rejected, not sent:", "required property 'name' not found",
        "END POST 'POST contact' with BPMN error REQUEST_INVALID");
  }
}
