package com.example.operaton.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.operaton.connector.http.params.HttpRequestParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonComposerTest {

  public static final String OBJECT_1 = """
      {"user":{"id":1042,"name":"Maya Lin",
        "contact":{"email":"maya.lin@example.com","phone":"+1-555-0192"},
        "preferences":{"theme":"light","notifications":{"email":true,"sms":false}}}}""";

  public static final String OBJECT_2 = """
      {"user":{"contact":{"phone":"+1-555-0148"},
        "preferences":{"theme":"dark","notifications":{"sms":true,"push":true}},
        "roles":["editor","reviewer"]}}""";

  public static final String EXPECTED_BODY = """
      {"user":{"id":1042,"name":"Maya Lin",
        "contact":{"email":"maya.lin@example.com","phone":"+1-555-0148"},
        "preferences":{"theme":"dark","notifications":{"email":true,"sms":true,"push":true}},
        "roles":["editor","reviewer"]}}""";

  final JsonComposer composer = new JsonComposer(new ObjectMapper());
  final SchemaCompiler schemas = new OpenApiSchemaCompiler(composer);

  @Test
  void mergesObjectsIntoBodyDescribedBySchema() {
    var schema = schemas.compile(TestSchemas.read("schemas/merged-user.schema.json"), SchemaUse.REQUEST);
    JsonNode merged = composer.merge(List.of(OBJECT_1, OBJECT_2));

    JsonNode body = composer.shape(schema, merged, null);
    schemas.validate(schema, body, "body");

    assertThat(body).isEqualTo(composer.parse(EXPECTED_BODY));
  }

  @Test
  void dropsUnknownFieldsAndCoercesTypes() {
    var schema = schemas.compile(TestSchemas.read("schemas/merged-user.schema.json"), SchemaUse.REQUEST);
    Map<String, Object> extra = Map.of("user", Map.of("id", "1042", "internalScore", 99), "debug", true);

    JsonNode body = composer.shape(schema, composer.merge(List.of(OBJECT_1, OBJECT_2, extra)), null);

    assertThat(body.at("/user/id").isInt()).isTrue();
    assertThat(body.at("/user/internalScore").isMissingNode()).isTrue();
    assertThat(body.has("debug")).isFalse();
  }

  @Test
  void rejectsBodyThatViolatesSchema() {
    var schema = schemas.compile(TestSchemas.read("schemas/merged-user.schema.json"), SchemaUse.REQUEST);
    JsonNode body = composer.shape(schema, composer.merge(List.of(OBJECT_2)), null); // no id / name / email

    assertThatThrownBy(() -> schemas.validate(schema, body, "body"))
        .isInstanceOf(SchemaViolationException.class)
        .hasMessageContaining("id")
        .hasMessageContaining("name")
        .hasMessageContaining("email");
  }

  @Test
  void rejectsSchemaGivenAsPath() {
    assertThatThrownBy(() -> schemas.compile("schemas/merged-user.schema.json", SchemaUse.REQUEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a JSON / YAML object");
  }

  @Test
  void xExpressionIsEvaluatedWithoutDollarBraceInTheSchema() {
    var schema = schemas.compile("{\"type\":\"object\",\"properties\":{\"by\":{\"type\":\"string\",\"x-expression\":\"initiator\"}}}", SchemaUse.REQUEST);
    JsonNode shaped = composer.shape(schema, List.of(), expr -> "${initiator}".equals(expr) ? "maya" : null);
    assertThat(shaped).isEqualTo(composer.parse("{\"by\":\"maya\"}"));
  }

  @Test
  void buildsGetParamsFromSchema() {
    var schema = schemas.compile(TestSchemas.read("schemas/get-user.params.schema.json"), SchemaUse.PARAMS);
    JsonNode merged = composer.merge(List.of(OBJECT_2));

    JsonNode shaped = composer.shape(schema, merged, expr -> "${userId}".equals(expr) ? "1042" : null);
    schemas.validate(schema, shaped, "params");
    HttpRequestParams params = HttpRequestParams.from(schema.node(), shaped);

    assertThat(params.path()).containsEntry("userId", "1042");
    assertThat(params.query())
        .containsEntry("fields", List.of("contact", "preferences"))
        .containsEntry("filter.theme", List.of("dark"))
        .containsEntry("filter.phone", List.of("+1-555-0148"));
    assertThat(params.headers()).containsEntry("X-Tenant", "acme");
  }
}
