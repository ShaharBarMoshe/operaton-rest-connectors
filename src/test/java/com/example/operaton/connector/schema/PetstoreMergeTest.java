package com.example.operaton.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Two inputs merged into one body with a real, unmodified third-party API description: the Swagger
 * Petstore (src/test/resources/openapi, Apache 2.0). Its Pet has a nested object (category →
 * Category) and an array of nested objects (tags → Tag), both by $ref.
 */
class PetstoreMergeTest {

  final JsonComposer composer = new JsonComposer(new ObjectMapper());
  final SchemaCompiler schemas = new OpenApiSchemaCompiler(composer);

  /** From one service: the pet's core data, with that service's own extra fields. */
  static final String INVENTORY = """
      {"id": "10", "name": "doggie", "category": {"id": 1, "legacyCode": "D1"},
       "photoUrls": ["https://img.example/1.png"], "internalId": "x-99"}""";

  /** From another service: catalogue data about the same pet. */
  static final String CATALOGUE = """
      {"category": {"name": "Dogs"}, "tags": [{"id": 7, "name": "friendly", "color": "blue"}],
       "status": "available", "photoUrls": ["https://img.example/2.png"], "_links": {"self": "/pets/10"}}""";

  /** The whole document pasted, plus the line naming what to use (JSON: one more key in front). */
  static String paste(String document, String ref) {
    String json = TestSchemas.read("openapi/" + document).strip();
    return "{\"$ref\": \"" + ref + "\", " + json.substring(1);
  }

  static Stream<Arguments> pastes() {
    return Stream.of(
        Arguments.of("OpenAPI 3.0, component", paste("petstore-openapi3.json", "#/components/schemas/Pet")),
        Arguments.of("OpenAPI 3.0, POST /pet", paste("petstore-openapi3.json", "#/paths/~1pet/post")),
        Arguments.of("Swagger 2.0, definition", paste("petstore-swagger2.json", "#/definitions/Pet")),
        Arguments.of("Swagger 2.0, POST /pet", paste("petstore-swagger2.json", "#/paths/~1pet/post")));
  }

  JsonNode body(String pasted, String... sources) {
    CompiledSchema schema = schemas.compile(pasted, SchemaUse.REQUEST);
    List<JsonNode> inputs = Stream.of(sources).map(composer::parse).toList();
    JsonNode body = composer.shape(schema, inputs, null);
    schemas.validate(schema, body, "Request body");
    return body;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pastes")
  void mergesTwoInputsIntoTheNestedPet(String name, String pasted) {
    JsonNode body = body(pasted, INVENTORY, CATALOGUE);

    // category: id from the first input + name from the second (objects merge at every level);
    // photoUrls: arrays are replaced, the later input wins; tags[]: each item shaped by Tag;
    // fields the schema does not declare (internalId, legacyCode, color, _links) are not sent;
    // "10" is coerced to the declared integer
    assertThat(body).isEqualTo(composer.parse("""
        {"id": 10, "name": "doggie", "category": {"id": 1, "name": "Dogs"},
         "photoUrls": ["https://img.example/2.png"], "tags": [{"id": 7, "name": "friendly"}],
         "status": "available"}"""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pastes")
  void orderOfTheInputsDecidesConflicts(String name, String pasted) {
    JsonNode body = body(pasted, CATALOGUE, INVENTORY);

    assertThat(body.get("photoUrls")).isEqualTo(composer.parse("[\"https://img.example/1.png\"]"));
    assertThat(body.get("category")).isEqualTo(composer.parse("{\"id\": 1, \"name\": \"Dogs\"}"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pastes")
  void mergedResultIsValidatedAgainstThePetSchema(String name, String pasted) {
    // the catalogue alone has no name (required)
    assertThatThrownBy(() -> body(pasted, CATALOGUE))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("name");
    // status outside the enum of the pasted schema
    assertThatThrownBy(() -> body(pasted, INVENTORY, CATALOGUE, "{\"status\": \"lost\"}"))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("status");
    // nested: Category.id must be an integer, checked through the $ref
    assertThatThrownBy(() -> body(pasted, INVENTORY, CATALOGUE, "{\"category\": {\"id\": \"abc\"}}"))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("/category/id");
    // nested array item: Tag.id must be an integer
    assertThatThrownBy(() -> body(pasted, INVENTORY, CATALOGUE, "{\"tags\": [{\"id\": \"x\"}]}"))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("/tags/0/id");
  }
}
