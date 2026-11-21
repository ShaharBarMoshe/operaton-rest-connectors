package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.spin.Spin.JSON;

import com.example.operaton.connector.schema.JsonComposerTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs the user-onboarding model against the real nginx mock (mock/nginx) started in Docker. */
@SpringBootTest
class UserOnboardingNginxTest extends NginxMockSupport {

  ProcessInstanceWithVariables start(int userId) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("apiBaseUrl", baseUrl() + "/api");
    vars.put("userId", userId);
    // Spin Json variables - what callers of the engine REST API typically send ("type": "Json")
    vars.put("userChanges", JSON(JsonComposerTest.OBJECT_2));
    vars.put("contactA", JSON("{\"name\":\"ll\",\"phone\":89}"));
    vars.put("contactB", JSON("{\"address\":{\"city\":\"khg\",\"name\":\"ff\",\"street\":\"adf\"}}"));
    vars.put("initiator", "maya.admin");
    vars.put("deleteReason", "onboarding done");
    return start("user-onboarding", vars);
  }

  @Test
  void onboardsUserThroughAllConnectors() throws Exception {
    ProcessInstanceWithVariables pi = start(1042);

    JsonNode calls = calls();
    assertThat(calls.findValuesAsText("method")).containsExactly("GET", "PUT", "PATCH", "POST", "DELETE", "GET");

    JsonNode get = calls.get(0);
    assertThat(get.get("uri").asText()).isEqualTo("/api/users/1042");
    assertThat(get.get("args").asText()).isEqualTo("fields=contact&fields=preferences&filter.theme=dark&filter.phone=%2B1-555-0148");
    assertThat(get.at("/headers/X-Tenant").asText()).isEqualTo("acme");

    JsonNode put = calls.get(1);
    assertThat(put.get("uri").asText()).isEqualTo("/api/users/1042");
    assertThat(put.get("body")).isEqualTo(MAPPER.readTree(JsonComposerTest.EXPECTED_BODY));
    assertThat(put.at("/headers/Content-Type").asText()).startsWith("application/json");

    JsonNode patch = calls.get(2);
    assertThat(patch.get("uri").asText()).isEqualTo("/api/users/1042/preferences");
    assertThat(patch.get("body")).isEqualTo(MAPPER.readTree(
        "{\"theme\":\"dark\",\"smsEnabled\":true,\"pushEnabled\":true,\"updatedBy\":\"maya.admin\"}"));
    assertThat(patch.at("/headers/X-Correlation-Id").asText()).isEqualTo(pi.getId());

    JsonNode post = calls.get(3);
    assertThat(post.get("uri").asText()).isEqualTo("/api/contacts");
    assertThat(post.get("body")).isEqualTo(MAPPER.readTree("{\"name\":\"ff\",\"phone\":89,\"city\":\"khg\"}"));

    JsonNode delete = calls.get(4);
    assertThat(delete.get("uri").asText()).isEqualTo("/api/users/1042/sessions");
    assertThat(delete.get("args").asText()).isEqualTo("reason=onboarding%20done");
    assertThat(delete.get("body").isNull()).isTrue();

    Map<String, Object> vars = variables(pi);
    assertThat(vars).containsEntry("putStatus", 200).containsEntry("patchStatus", 204)
        .containsEntry("postStatus", 201).containsEntry("deleteStatus", 204);
    assertThat(asMap(vars.get("createdContact"))).containsEntry("id", "c-1").containsEntry("city", "khg");
    // responseSchema on the first GET strips fields the schema does not know
    assertThat(asMap(asMap(vars.get("currentUser")).get("user"))).doesNotContainKey("internalScore");
    // the read-back sees what PUT + PATCH stored in the mock
    Map<String, Object> finalUser = asMap(asMap(vars.get("finalUser")).get("user"));
    assertThat(finalUser).containsEntry("updatedBy", "maya.admin").containsEntry("roles", List.of("editor", "reviewer"));
    assertThat(asMap(finalUser.get("contact"))).containsEntry("phone", "+1-555-0148");
    assertThat(asMap(asMap(finalUser.get("preferences")).get("notifications")))
        .containsEntry("email", true).containsEntry("sms", true).containsEntry("push", true);

    assertThat(endActivity(pi)).isEqualTo("onbEnd");
  }

  @Test
  void unknownUserTakesTheNotFoundPath() throws Exception {
    ProcessInstanceWithVariables pi = start(7);

    assertThat(calls().findValuesAsText("method")).containsExactly("GET");
    assertThat(endActivity(pi)).isEqualTo("onbEndNotFound");
  }


  @Test
  void rejectedPatchEndsInErrorSubprocess() throws Exception {
    mock.put().uri("/__mock/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body("{\"method\":\"PATCH\",\"uri\":\"/api/users/1042/preferences\",\"status\":422,"
            + "\"body\":{\"message\":\"theme not allowed\"}}").retrieve().toBodilessEntity();

    ProcessInstanceWithVariables pi = start(1042);

    assertThat(endActivity(pi)).isEqualTo("errEndRejected");
    assertThat(variables(pi)).containsEntry("errorCode", "HTTP_422");
    JsonNode calls = calls();
    assertThat(calls).extracting(c -> c.get("method").asText()).containsExactly("GET", "PUT", "PATCH");
    assertNoVariablesStored(pi);
  }
}
