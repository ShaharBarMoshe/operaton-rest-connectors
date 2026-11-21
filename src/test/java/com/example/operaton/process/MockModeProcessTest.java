package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.spin.Spin.JSON;

import com.example.operaton.connector.schema.JsonComposerTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test mode: every REST task of the sample models has mockEnabled =
 * ${execution.getVariable('useMockData') == true} plus a mockStatus / mockResponse. Started with
 * useMockData = true, the processes run to their normal end without one request reaching the
 * service (the nginx mock records every request it gets).
 */
@SpringBootTest
class MockModeProcessTest extends NginxMockSupport {

  @org.springframework.beans.factory.annotation.Autowired org.operaton.bpm.engine.RepositoryService repositoryService;

  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl";

  Map<String, Object> vars(Object... keyValues) {
    Map<String, Object> vars = new HashMap<>(Map.of("apiBaseUrl", baseUrl() + "/api", "useMockData", true));
    for (int i = 0; i < keyValues.length; i += 2) vars.put((String) keyValues[i], keyValues[i + 1]);
    return vars;
  }

  @Test
  void customerSnapshotRunsOnMockData() throws Exception {
    var pi = start("customer-snapshot", vars("customerId", 501, "accessToken", TOKEN, "initiator", "demo"));

    assertThat(calls()).isEmpty();
    assertThat(endActivity(pi)).isEqualTo("end");
    Map<String, Object> v = variables(pi);
    assertThat(asMap(v.get("snapshot"))).containsEntry("id", "s-9001");            // from postSnapshot's mockResponse
    assertThat(v).containsEntry("postStatus", 201);
    assertThat(asMap(asMap(v.get("contact")).get("customer"))).containsEntry("email", "noa.levi@example.com");
    assertNoVariablesStored(pi);
  }

  @Test
  void userSyncRunsOnMockData() throws Exception {
    var pi = start("user-sync", vars("userId", 1042, "accessToken", TOKEN,
        "userChanges", JsonComposerTest.OBJECT_2, "initiator", "maya.admin", "deleteReason", "test"));

    assertThat(calls()).isEmpty();
    assertThat(endActivity(pi)).isEqualTo("end");
    assertThat(variables(pi)).containsEntry("putStatus", 200).containsEntry("deleteStatus", 204);
  }

  @Test
  void userOnboardingRunsOnMockData() throws Exception {
    var pi = start("user-onboarding", vars("userId", 1042,
        "userChanges", JSON(JsonComposerTest.OBJECT_2),
        "contactA", JSON("{\"name\":\"ll\",\"phone\":89}"),
        "contactB", JSON("{\"address\":{\"city\":\"khg\",\"name\":\"ff\",\"street\":\"adf\"}}"),
        "initiator", "maya.admin", "deleteReason", "test"));

    assertThat(calls()).isEmpty();
    assertThat(endActivity(pi)).isEqualTo("onbEnd");
    assertThat(variables(pi)).containsEntry("postStatus", 201).containsEntry("patchStatus", 204);
    assertThat(asMap(variables(pi).get("createdContact"))).containsEntry("id", "c-mock-1");
  }

  @Test
  void mockAnswersAreCheckedLikeRealOnes() throws Exception {
    // the POST's mock body is validated by the pasted response schema too: an id breaking its pattern
    // ends in the error subprocess like a real invalid response would
    String model = new String(getClass().getResourceAsStream("/bpmn/customer-snapshot.bpmn").readAllBytes())
        .replace("\"id\": \"s-9001\"", "\"id\": \"snap-1\"").replace("id=\"customer-snapshot\"", "id=\"customer-snapshot-badmock\"");
    repositoryService.createDeployment().addString("customer-snapshot-badmock.bpmn", model).deploy();

    var pi = start("customer-snapshot-badmock", vars("customerId", 501, "accessToken", TOKEN));

    assertThat(calls()).isEmpty();
    assertThat(variables(pi)).containsEntry("errorCode", "RESPONSE_INVALID");
    assertThat(endActivity(pi)).isEqualTo("errEndInvalid");
  }

  @Test
  void withoutTheSwitchTheSameModelCallsTheService() throws Exception {
    var pi = start("customer-snapshot", vars("customerId", 501, "accessToken", TOKEN, "useMockData", false));

    assertThat(calls()).hasSize(6);                                                 // 5 GETs + the POST
    assertThat(asMap(variables(pi).get("snapshot"))).containsEntry("id", "s-1");     // from the service
  }
}
