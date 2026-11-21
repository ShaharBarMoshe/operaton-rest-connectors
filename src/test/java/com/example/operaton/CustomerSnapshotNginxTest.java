package com.example.operaton;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.boot.test.context.SpringBootTest;

/** customer-snapshot.bpmn: 10 parallel GETs → POST of their schema-shaped merge, against the nginx mock. */
@SpringBootTest
class CustomerSnapshotNginxTest extends NginxMockSupport {

  /** JWT-shaped test token (the mock checks the shape only). */
  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvcGVyYXRvbi10ZXN0In0.c2lnbmF0dXJl";

  static final List<String> RESOURCES = List.of(
      "profile", "contact", "address", "preferences", "account",
      "orders", "loyalty", "billing", "shipping", "consents");

  static final String EXPECTED_SNAPSHOT = """
      {
        "customerId": 501,
        "name": {"first": "Noa", "last": "Levi"},
        "birthDate": "1990-04-12",
        "contact": {"email": "noa.levi@example.com", "phone": "+972-50-555-0101"},
        "address": {"street": "Herzl 10", "city": "Tel Aviv", "zip": "6100000", "country": "IL"},
        "preferences": {"language": "he", "currency": "ILS", "newsletter": true},
        "account": {"status": "active", "tier": "platinum"},
        "orders": {"count": 42, "totalSpent": 1534.5},
        "loyalty": {"points": 1280, "level": "gold"},
        "billing": {"method": "card", "last4": "4242"},
        "shipping": {"carrier": "DHL"},
        "consents": {"marketing": true, "analytics": false},
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

    // every one of the 11 calls carries the token from the accessToken variable (jwt input)
    calls.forEach(c -> assertThat(c.at("/headers/Authorization").asText()).isEqualTo("Bearer " + TOKEN));

    JsonNode post = calls.get(calls.size() - 1);
    assertThat(post.get("method").asText()).isEqualTo("POST");
    assertThat(post.get("uri").asText()).isEqualTo("/api/customer-snapshots");
    // merged from all 10 responses; noise (_links, internalNotes, geo, cardToken, ...) dropped;
    // account.tier comes from the later "loyalty" source; "1534.50" coerced to a number
    assertThat(post.get("body")).isEqualTo(MAPPER.readTree(EXPECTED_SNAPSHOT));

    Map<String, Object> vars = variables(pi);
    assertThat(vars).containsKeys(RESOURCES.toArray(String[]::new)).containsEntry("postStatus", 201);
    // the token (start variable) and all other variables are gone and were never written to history
    assertNoVariablesStored(pi);
    assertThat(asMap(vars.get("snapshot"))).containsEntry("id", "s-1");
    assertThat(endActivity(pi)).isEqualTo("end");
  }

  @Test
  void rejectedTokenStopsBeforePost() throws Exception {
    // the mock only accepts header.payload.signature tokens → 401 → BpmnError HTTP_401 on the GETs;
    // the model has no boundary event for it, so the branches end and the POST is never sent
    ProcessInstanceWithVariables pi = start("customer-snapshot", Map.of(
        "apiBaseUrl", baseUrl() + "/api", "customerId", 501, "accessToken", "not-a-jwt"));

    JsonNode calls = calls();
    assertThat(calls).isNotEmpty().allSatisfy(c -> assertThat(c.get("method").asText()).isEqualTo("GET"));
    assertThat(variables(pi)).doesNotContainKey("snapshot");
    assertNoVariablesStored(pi);
  }

  @Test
  void missingTokenVariableFails() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
            "customer-snapshot", Map.of("apiBaseUrl", baseUrl() + "/api", "customerId", 501)))
        .hasMessageContaining("JWT variable 'accessToken' does not exist");
  }

  @Test
  void snapshotByDefaultsWhenNoInitiator() throws Exception {
    runtimeService.startProcessInstanceByKey("customer-snapshot", Map.of(
        "apiBaseUrl", baseUrl() + "/api", "customerId", 501, "accessToken", TOKEN));

    JsonNode calls = calls();
    assertThat(calls.get(calls.size() - 1).at("/body/snapshotBy").asText()).isEqualTo("system");
  }
}
