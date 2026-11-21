package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The most copy-paste friendly way: the service's whole OpenAPI document (here &gt; 4000 characters,
 * fetched from the mock's /openapi.yaml) pasted into bodySchema, plus one "$ref" line naming the
 * schema to send.
 */
@SpringBootTest
class PastedOpenApiDocumentTest extends NginxMockSupport {

  static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl";

  @Autowired RepositoryService repositoryService;

  @Test
  void wholeDocumentAsBodySchema() throws Exception {
    String document = mock.get().uri("/openapi.yaml").retrieve().body(String.class);
    assertThat(document).hasSizeGreaterThan(4000);

    BpmnModelInstance model = Bpmn.createExecutableProcess("pasted-document")
        .operatonHistoryTimeToLive(1)
        .startEvent()
        .serviceTask("post").operatonDelegateExpression("${restPost}")
          .operatonInputParameter("url", "${apiBaseUrl}/customer-snapshots")
          .operatonInputParameter("sources", "snapshotData")
          .operatonInputParameter("bodySchema", "$ref: '#/components/schemas/CustomerSnapshot'\n" + document)
          .operatonInputParameter("responseSchema", "$ref: '#/paths/~1customer-snapshots/post/responses'\n" + document)
          .operatonInputParameter("resultVariable", "snapshot")
        .endEvent("end")
        .done();
    repositoryService.createDeployment().addModelInstance("pasted-document.bpmn", model).deploy();

    String data = """
        {"id": "ignored", "customerId": 7, "name": {"first": "A", "last": "B"}, "contact": {"email": "a@example.com"},
         "address": {"city": "Haifa", "country": "IL", "geo": {"lat": 1}}, "account": {"status": "active", "tier": "gold"},
         "loyalty": {"points": 3}, "internal": true}""";
    var pi = start("pasted-document", Map.of("apiBaseUrl", baseUrl() + "/api", "accessToken", TOKEN,
        "snapshotData", org.operaton.spin.Spin.JSON(data)));

    JsonNode post = calls().get(0);
    assertThat(post.get("body")).isEqualTo(MAPPER.readTree("""
        {"customerId": 7, "name": {"first": "A", "last": "B"}, "contact": {"email": "a@example.com"},
         "address": {"city": "Haifa", "country": "IL"}, "account": {"status": "active", "tier": "gold"},
         "loyalty": {"points": 3}, "snapshotBy": "system"}"""));          // readOnly id, undeclared fields: not sent
    assertThat(post.at("/headers/Authorization").asText()).isEqualTo("Bearer " + TOKEN);
    assertThat(asMap(variables(pi).get("snapshot"))).containsEntry("id", "s-1");
    assertThat(endActivity(pi)).isEqualTo("end");
    assertNoVariablesStored(pi);
  }
}
