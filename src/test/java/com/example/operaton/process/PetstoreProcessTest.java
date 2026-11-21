package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.spin.Spin.JSON;

import com.example.operaton.connector.schema.TestSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A REST POST task whose bodySchema is the unmodified Swagger Petstore document (17 KB, OpenAPI 3.0,
 * src/test/resources/openapi) plus the "$ref" naming POST /pet; two process variables from different
 * services are merged into the nested Pet and sent to the nginx mock.
 */
@SpringBootTest
class PetstoreProcessTest extends NginxMockSupport {

  @Autowired RepositoryService repositoryService;

  @Test
  void mergesTwoVariablesIntoThePetstorePet() throws Exception {
    String document = TestSchemas.read("openapi/petstore-openapi3.json").strip();
    String bodySchema = "{\"$ref\": \"#/paths/~1pet/post\", " + document.substring(1);

    BpmnModelInstance model = Bpmn.createExecutableProcess("petstore-merge")
        .operatonHistoryTimeToLive(1)
        .startEvent()
        .serviceTask("addPet").operatonDelegateExpression("${restPost}")
          .operatonInputParameter("url", "${apiBaseUrl}/contacts")        // the mock echoes any POSTed body
          .operatonInputParameter("sources", "inventoryPet, cataloguePet")
          .operatonInputParameter("bodySchema", bodySchema)
          .operatonInputParameter("statusVariable", "addStatus")
        .endEvent("end")
        .done();
    repositoryService.createDeployment().addModelInstance("petstore-merge.bpmn", model).deploy();

    var pi = start("petstore-merge", Map.of(
        "apiBaseUrl", baseUrl() + "/api",
        "inventoryPet", JSON("""
            {"id": "10", "name": "doggie", "category": {"id": 1, "legacyCode": "D1"},
             "photoUrls": ["https://img.example/1.png"], "internalId": "x-99"}"""),
        "cataloguePet", JSON("""
            {"category": {"name": "Dogs"}, "tags": [{"id": 7, "name": "friendly", "color": "blue"}],
             "status": "available", "_links": {"self": "/pets/10"}}""")));

    JsonNode post = calls().get(0);
    assertThat(post.get("method").asText()).isEqualTo("POST");
    assertThat(post.get("body")).isEqualTo(MAPPER.readTree("""
        {"id": 10, "name": "doggie", "category": {"id": 1, "name": "Dogs"},
         "photoUrls": ["https://img.example/1.png"], "tags": [{"id": 7, "name": "friendly"}],
         "status": "available"}"""));
    assertThat(variables(pi)).containsEntry("addStatus", 201);
    assertThat(endActivity(pi)).isEqualTo("end");
    assertNoVariablesStored(pi);
  }
}
