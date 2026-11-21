package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.boot.test.context.SpringBootTest;

/** customer-snapshot.bpmn: 5 parallel GETs → POST of their schema-shaped merge, against the nginx mock. */
@SpringBootTest
class CustomerSnapshotNginxTest extends NginxMockSupport {

  /** JWT-shaped test token (the mock checks the shape only). */
  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvcGVyYXRvbi10ZXN0In0.c2lnbmF0dXJl";

  static final List<String> RESOURCES = List.of("profile", "contact", "address", "account", "loyalty");

  static final String EXPECTED_SNAPSHOT = """
      {
        "customerId": 501,
        "name": {"first": "Noa", "last": "Levi"},
        "birthDate": "1990-04-12",
        "contact": {"email": "noa.levi@example.com", "phone": "+972-50-555-0101"},
        "address": {"street": "Herzl 10", "city": "Tel Aviv", "zip": "6100000", "country": "IL"},
        "account": {"status": "active", "tier": "platinum"},
        "loyalty": {"points": 1280, "level": "gold"},
        "snapshotBy": "noa.admin"
      }""";

  @Test
  void postsSchemaShapedMergeOfTenGetResponses() throws Exception {
    ProcessInstanceWithVariables pi = start("customer-snapshot", Map.of(
        "apiBaseUrl", baseUrl() + "/api",
        "customerId", 501,
        "accessToken", TOKEN,
        "initiator", "noa.admin"));

    JsonNode calls = calls();
    List<String> gets = new ArrayList<>();
    calls.forEach(c -> { if ("GET".equals(c.get("method").asText())) gets.add(c.get("uri").asText()); });
    assertThat(gets).containsExactlyInAnyOrderElementsOf(
        RESOURCES.stream().map(r -> "/api/customers/501/" + r).toList());

    // every one of the 6 calls carries the token from the accessToken variable (read by the connector, no task input)
    calls.forEach(c -> assertThat(c.at("/headers/Authorization").asText()).isEqualTo("Bearer " + TOKEN));

    JsonNode post = calls.get(calls.size() - 1);
    assertThat(post.get("method").asText()).isEqualTo("POST");
    assertThat(post.get("uri").asText()).isEqualTo("/api/customer-snapshots");
    // merged from all 5 responses; noise (_links, internalNotes, geo, openedAt, ...) dropped;
    // account.tier comes from the later "loyalty" source (platinum overrides gold)
    assertThat(post.get("body")).isEqualTo(MAPPER.readTree(EXPECTED_SNAPSHOT));

    Map<String, Object> vars = variables(pi);
    assertThat(vars).containsKeys(RESOURCES.toArray(String[]::new)).containsEntry("postStatus", 201);
    // results are Spin "Json" variables: Cockpit / REST show the JSON value itself
    for (String r : RESOURCES) {
      assertThat(pi.getVariables().getValueTyped(r).getType().getName()).as(r).isEqualTo("json");
    }
    assertThat(pi.getVariables().getValueTyped("snapshot").getType().getName()).isEqualTo("json");
    // the token (start variable) and all other variables are gone and were never written to history
    assertNoVariablesStored(pi);
    assertThat(asMap(vars.get("snapshot"))).containsEntry("id", "s-1");
    assertThat(endActivity(pi)).isEqualTo("end");
  }

  @Test
  void withoutTokenVariableNoAuthorizationIsSentAndTheApiRejects() throws Exception {
    var pi = start("customer-snapshot", Map.of("apiBaseUrl", baseUrl() + "/api", "customerId", 501));

    calls().forEach(c -> assertThat(c.at("/headers/Authorization").isMissingNode()).isTrue());
    assertThat(variables(pi)).containsEntry("errorCode", "HTTP_401");
    assertThat(endActivity(pi)).isEqualTo("errEndAuth");
  }

  @Test
  void snapshotByDefaultsWhenNoInitiator() throws Exception {
    runtimeService.startProcessInstanceByKey("customer-snapshot", Map.of(
        "apiBaseUrl", baseUrl() + "/api", "customerId", 501, "accessToken", TOKEN));

    JsonNode calls = calls();
    assertThat(calls.get(calls.size() - 1).at("/body/snapshotBy").asText()).isEqualTo("system");
  }
}
