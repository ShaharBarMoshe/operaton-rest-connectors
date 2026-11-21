package com.example.operaton.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlattenTest {

  final JsonComposer composer = new JsonComposer(new ObjectMapper());
  final SchemaCompiler schemas = new OpenApiSchemaCompiler(composer);

  private JsonNode build(String schemaRef, String... sources) {
    var schema = schemas.compile(schemaRef.trim().startsWith("{") ? schemaRef : TestSchemas.read(schemaRef), SchemaUse.REQUEST);
    List<JsonNode> nodes = java.util.Arrays.stream(sources).map(composer::parse).toList();
    JsonNode result = composer.shape(schema, nodes, null);
    schemas.validate(schema, result, "body");
    return result;
  }

  @Test
  void flattensAndKeepsOnlySchemaProperties() {
    JsonNode body = build("schemas/contact.flat.schema.json",
        "{\"name\":\"ll\",\"phone\":89}",
        "{\"address\":{\"city\":\"khg\",\"name\":\"ff\",\"street\":\"adf\"}}");

    assertThat(body).isEqualTo(composer.parse("{\"name\":\"ff\",\"phone\":89,\"city\":\"khg\"}"));
  }

  @Test
  void laterSourceWinsEvenOverDeeperKeyOfEarlierSource() {
    JsonNode body = build("schemas/contact.flat.schema.json",
        "{\"address\":{\"name\":\"deep-A\",\"city\":\"x\"},\"phone\":1}",
        "{\"name\":\"top-B\"}");

    assertThat(body.get("name").asText()).isEqualTo("top-B");
  }

  @Test
  void deeperKeyWinsInsideOneSource() {
    JsonNode body = build("schemas/contact.flat.schema.json",
        "{\"name\":\"outer\",\"phone\":1,\"a\":{\"city\":\"c\",\"b\":{\"name\":\"deepest\"}}}");

    assertThat(body.get("name").asText()).isEqualTo("deepest");
  }

  @Test
  void coercesTypesAfterFlattening() {
    JsonNode body = build("schemas/contact.flat.schema.json",
        "{\"name\":\"n\",\"contact\":{\"phone\":\"89\"},\"address\":{\"city\":\"c\"}}");

    assertThat(body.get("phone").isNumber()).isTrue();
    assertThat(body.get("phone").asInt()).isEqualTo(89);
  }

  @Test
  void flattenOnNestedPropertyAndObjectPropertiesStayNested() {
    String schema = """
        {"type":"object","additionalProperties":false,"properties":{
          "customer":{"type":"object","x-flatten":true,"additionalProperties":false,"properties":{
            "name":{"type":"string"},
            "city":{"type":"string"},
            "geo":{"type":"object","properties":{"lat":{"type":"number"}}}}}}}""";

    JsonNode body = build(schema,
        "{\"customer\":{\"name\":\"a\",\"address\":{\"city\":\"c\",\"geo\":{\"lat\":1.5,\"lng\":2}}}}");

    // geo declares only lat: undeclared lng is not taken over (no additionalProperties: true)
    assertThat(body).isEqualTo(composer.parse(
        "{\"customer\":{\"name\":\"a\",\"city\":\"c\",\"geo\":{\"lat\":1.5}}}"));
  }
}
