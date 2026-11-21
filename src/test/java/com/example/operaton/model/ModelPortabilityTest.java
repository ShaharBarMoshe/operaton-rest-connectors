package com.example.operaton.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.operaton.connector.schema.JsonComposer;
import com.example.operaton.connector.schema.OpenApiSchemaCompiler;
import com.example.operaton.connector.schema.SchemaCompiler;
import com.example.operaton.connector.schema.SchemaUse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter;

/**
 * Models must open in any Modeler (Camunda Modeler, Miragon VS Code BPMN Modeler, Operaton Modeler),
 * also WITHOUT the REST connector element templates: Camunda 7 namespace, no template link,
 * every connector input present, documentation present.
 * Fix failures with: scripts/make-portable.py
 */
class ModelPortabilityTest {

  private static final SchemaCompiler SCHEMAS = new OpenApiSchemaCompiler(new JsonComposer(new ObjectMapper()));

  static final String FIX = " -> run scripts/make-portable.py";

  static Stream<Path> models() throws Exception {
    return Files.list(Path.of("src/main/resources/bpmn")).filter(p -> p.toString().endsWith(".bpmn")).sorted();
  }

  /** delegateExpression → input names, read from the templates file (single source of truth). */
  static Map<String, List<String>> connectorInputs() throws Exception {
    JsonNode templates = new ObjectMapper().readTree(new File(".camunda/element-templates/rest-connectors.json"));
    Map<String, List<String>> result = new HashMap<>();
    for (JsonNode t : templates) {
      String delegate = null;
      List<String> inputs = new ArrayList<>();
      for (JsonNode p : t.get("properties")) {
        JsonNode b = p.get("binding");
        if ("camunda:delegateExpression".equals(b.path("name").asText())) delegate = p.get("value").asText();
        if ("camunda:inputParameter".equals(b.get("type").asText())) inputs.add(b.get("name").asText());
      }
      result.put(delegate, inputs);
    }
    return result;
  }

  @ParameterizedTest
  @MethodSource("models")
  void usesCamundaNamespace(Path model) throws Exception {
    String xml = Files.readString(model);
    assertThat(xml).as(model.getFileName() + " uses the Operaton namespace, which Camunda Modeler / VS Code modelers do not show" + FIX)
        .doesNotContain("http://operaton.org/schema/1.0/bpmn", "http://operaton.org/schema/modeler/1.0")
        .contains("xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
        .contains("modeler:executionPlatform=\"Camunda Platform\"");
  }

  @ParameterizedTest
  @MethodSource("models")
  void opensWithoutElementTemplates(Path model) throws Exception {
    Map<String, List<String>> connectorInputs = connectorInputs();
    BpmnModelInstance instance = Bpmn.readModelFromFile(model.toFile());
    int connectorTasks = 0;

    for (ServiceTask task : instance.getModelElementsByType(ServiceTask.class)) {
      List<String> expected = connectorInputs.get(task.getOperatonDelegateExpression());
      if (expected == null) {
        continue; // not a REST connector task
      }
      String where = model.getFileName() + "#" + task.getId();
      connectorTasks++;

      assertThat(task.getAttributeValueNs("http://camunda.org/schema/1.0/bpmn", "modelerTemplate"))
          .as(where + " is linked to an element template" + FIX).isNull();
      assertThat(task.getDocumentations()).as(where + " has no documentation" + FIX).isNotEmpty();

      List<String> actual = task.getExtensionElements() == null ? List.of()
          : task.getExtensionElements().getElementsQuery().filterByType(
                  org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput.class).list().stream()
              .flatMap(io -> io.getOperatonInputParameters().stream())
              .map(OperatonInputParameter::getOperatonName).toList();
      assertThat(actual).as(where + " is missing connector inputs" + FIX).containsAll(expected);

      // schema inputs: pasted OpenAPI / JSON Schema (JSON or YAML) that compiles, and no ${..}
      // (the engine would evaluate it before the connector runs)
      for (OperatonInputParameter p : task.getExtensionElements().getElementsQuery()
          .filterByType(org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput.class).singleResult()
          .getOperatonInputParameters()) {
        String text = p.getTextContent() == null ? "" : p.getTextContent().trim();
        if (!p.getOperatonName().endsWith("Schema") || text.isEmpty()) continue;
        assertThat(text).as(where + " " + p.getOperatonName() + " contains ${..}; use \"x-expression\"").doesNotContain("${", "#{");
        SchemaUse use = switch (p.getOperatonName()) {
          case "paramsSchema" -> SchemaUse.PARAMS;
          case "responseSchema" -> SchemaUse.RESPONSE;
          default -> SchemaUse.REQUEST;
        };
        SCHEMAS.compile(text, use); // parses + converts + compiles, throws on errors
      }
    }
    assertThat(connectorTasks).as(model.getFileName() + ": no REST connector task found").isPositive();
  }
}
