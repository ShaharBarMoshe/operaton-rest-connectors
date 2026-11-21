package com.example.operaton.connector.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.operaton.connector.http.params.HttpRequestParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Schemas pasted from another service's OpenAPI / Swagger document. */
class OpenApiSchemaCompilerTest {

  private final JsonComposer composer = new JsonComposer(new ObjectMapper());
  private final SchemaCompiler schemas = new OpenApiSchemaCompiler(composer);

  private JsonNode json(String s) {
    return composer.parse(s);
  }

  private JsonNode build(String pasted, SchemaUse use, String data) {
    var schema = schemas.compile(pasted, use);
    JsonNode shaped = composer.shape(schema, List.of(json(data)), null);
    schemas.validate(schema, shaped, "test");
    return shaped;
  }

  private static String spec() throws Exception {
    return Files.readString(Path.of("mock/openapi/customer-api.yaml"));
  }

  @Test
  void wholeDocumentWithRootRef_readOnlyLeftOutOfRequest_onlyDeclaredFieldsSent() throws Exception {
    String pasted = "$ref: '#/components/schemas/CustomerSnapshot'\n" + spec();
    JsonNode body = build(pasted, SchemaUse.REQUEST, """
        {"id":"s-9","customerId":501,"name":{"first":"Noa","last":"Levi","nick":"N"},
         "contact":{"email":"noa@example.com"},
         "address":{"city":"Tel Aviv","country":"IL","geo":{"lat":1}},
         "account":{"status":"active","tier":"gold","openedAt":"2019"},
         "loyalty":{"points":"12"},"birthDate":null}""");

    assertThat(body).isEqualTo(json("""
        {"customerId":501,"name":{"first":"Noa","last":"Levi"},"birthDate":null,
         "contact":{"email":"noa@example.com"},"address":{"city":"Tel Aviv","country":"IL"},
         "account":{"status":"active","tier":"gold"},"loyalty":{"points":12},"snapshotBy":"system"}"""));
  }

  @Test
  void sameSchemaAsResponse_requiresReadOnlyId() throws Exception {
    String pasted = "$ref: '#/components/schemas/CustomerSnapshot'\n" + spec();
    String withoutId = """
        {"customerId":501,"name":{"first":"N","last":"L"},"contact":{"email":"n@example.com"},
         "address":{"city":"c","country":"IL"},"account":{"status":"active","tier":"gold"},"loyalty":{}}""";
    assertThatThrownBy(() -> build(pasted, SchemaUse.RESPONSE, withoutId))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("id");
    JsonNode ok = build(pasted, SchemaUse.RESPONSE, withoutId.replaceFirst("\\{", "{\"id\":\"s-1\","));
    assertThat(ok.get("id").asText()).isEqualTo("s-1");
  }

  @Test
  void documentWithoutRootRefIsRejectedWithAHint() throws Exception {
    assertThatThrownBy(() -> schemas.compile(spec(), SchemaUse.REQUEST))
        .hasMessageContaining("add a top-level \"$ref\"");
  }

  @Test
  void unresolvedRefTellsToPasteTheComponents() {
    assertThatThrownBy(() -> schemas.compile("""
        type: object
        properties:
          address: {$ref: '#/components/schemas/Address'}""", SchemaUse.REQUEST))
        .hasMessageContaining("paste the referenced components too");
  }

  @Test
  void requestBodyAndResponsesAreUnwrapped() {
    String requestBody = """
        {"required": true, "content": {"application/json": {"schema":
          {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}}}}}""";
    assertThat(build(requestBody, SchemaUse.REQUEST, "{\"name\":\"a\",\"x\":1}")).isEqualTo(json("{\"name\":\"a\"}"));

    String responses = """
        '404': {description: not found}
        '200':
          description: ok
          content:
            application/json:
              schema: {type: object, properties: {id: {type: integer}}}""";
    assertThat(build(responses, SchemaUse.RESPONSE, "{\"id\":\"7\",\"_links\":{}}")).isEqualTo(json("{\"id\":7}"));
  }

  @Test
  void operationParametersBecomeParams_swagger2AndOpenApi3() {
    String oas3 = """
        - name: customerId
          in: path
          required: true
          schema: {type: integer, minimum: 1}
        - name: fields
          in: query
          schema: {type: array, items: {type: string}}
        - name: X-Tenant
          in: header
          schema: {type: string, default: acme}""";
    var schema = schemas.compile(oas3, SchemaUse.PARAMS);
    JsonNode shaped = composer.shape(schema, List.of(json("{\"fields\":[\"a\",\"b\"]}")), null,
        Map.of("customerId", (Object) "501")::get);   // missing in sources → process variable
    schemas.validate(schema, shaped, "params");
    HttpRequestParams params = HttpRequestParams.from(schema.node(), shaped);
    assertThat(params.path()).containsEntry("customerId", "501");
    assertThat(params.query()).containsEntry("fields", List.of("a", "b"));
    assertThat(params.headers()).containsEntry("X-Tenant", "acme");

    String swagger2 = """
        [{"name": "limit", "in": "query", "type": "integer", "maximum": 100},
         {"name": "body", "in": "body", "schema": {"type": "object"}}]""";
    var s2 = schemas.compile(swagger2, SchemaUse.PARAMS);
    assertThat(s2.node().at("/properties/limit")).isEqualTo(json("{\"type\":\"integer\",\"maximum\":100,\"x-in\":\"query\"}"));
    assertThat(s2.node().at("/properties").has("body")).isFalse();
    assertThatThrownBy(() -> schemas.validate(s2, json("{\"limit\":500}"), "params")).isInstanceOf(SchemaViolationException.class);
  }

  @Test
  void openApi30KeywordsKeepTheirMeaning() {
    String pasted = """
        type: object
        properties:
          price: {type: number, minimum: 0, exclusiveMinimum: true, example: 9.5}
          note: {type: string, nullable: true, x-internal-id: 17}
          state: {type: string, enum: [a, b], nullable: true}
          password: {type: string, writeOnly: true}
        xml: {name: Item}""";
    var schema = schemas.compile(pasted, SchemaUse.RESPONSE);
    assertThatThrownBy(() -> schemas.validate(schema, json("{\"price\":0}"), "r"))     // exclusive bound
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("price");
    schemas.validate(schema, json("{\"price\":1,\"note\":null,\"state\":null}"), "r");  // nullable
    assertThatThrownBy(() -> schemas.validate(schema, json("{\"note\":7}"), "r")).isInstanceOf(SchemaViolationException.class);
    var swagger2Fragment = schemas.compile("{type: object, properties: {tag: {type: string, x-nullable: true}}}", SchemaUse.REQUEST);
    schemas.validate(swagger2Fragment, json("{\"tag\":null}"), "r");
    // writeOnly is not kept in a response; example / xml / x-internal-id are just ignored
    JsonNode shaped = build(pasted, SchemaUse.RESPONSE, "{\"price\":\"2.5\",\"password\":\"x\",\"other\":1}");
    assertThat(shaped.properties()).hasSize(1);
    assertThat(shaped.get("price").decimalValue()).isEqualByComparingTo("2.5");
  }

  @Test
  void wholeOpenApi30DocumentUsesTheOpenApi30Dialect() throws Exception {
    // OpenAPI 3.0 meaning of "nullable" + "$ref" siblings, checked through a nested $ref
    String pasted = "$ref: '#/components/schemas/CustomerSnapshot'\n" + spec();
    var schema = schemas.compile(pasted, SchemaUse.RESPONSE);
    String ok = """
        {"id":"s-1","customerId":1,"name":{"first":"a","last":"b"},"birthDate":null,"contact":{"email":"a@b.co"},
         "address":{"city":"c","country":"IL"},"account":{"status":"active","tier":"gold"},"loyalty":{}}""";
    schemas.validate(schema, json(ok), "r");
    assertThatThrownBy(() -> schemas.validate(schema, json(ok.replace("\"IL\"", "\"ISR\"")), "r"))
        .hasMessageContaining("/address/country");
    assertThatThrownBy(() -> schemas.validate(schema, json(ok.replace("a@b.co", "nope")), "r"))
        .hasMessageContaining("/contact/email");
  }

  @Test
  void allOfInheritanceIsShapedWithTheMembersProperties() {
    String pasted = """
        $ref: '#/components/schemas/Dog'
        components:
          schemas:
            Pet:
              type: object
              required: [name]
              properties: {name: {type: string}}
            Dog:
              allOf:
                - $ref: '#/components/schemas/Pet'
                - type: object
                  properties: {barks: {type: boolean}}""";
    assertThat(build(pasted, SchemaUse.REQUEST, "{\"name\":\"Rex\",\"barks\":\"true\",\"chip\":1}"))
        .isEqualTo(json("{\"name\":\"Rex\",\"barks\":true}"));
    assertThatThrownBy(() -> build(pasted, SchemaUse.REQUEST, "{\"barks\":true}"))
        .isInstanceOf(SchemaViolationException.class).hasMessageContaining("name");
  }

  @Test
  void plainJsonSchemaStillWorks() {
    String plain = """
        {"type":"object","additionalProperties":true,"properties":{"a":{"$ref":"#/$defs/n"}},"$defs":{"n":{"type":"integer"}}}""";
    assertThat(build(plain, SchemaUse.REQUEST, "{\"a\":\"3\",\"b\":true}")).isEqualTo(json("{\"a\":3,\"b\":true}"));
  }

  @Test
  void wholeSwagger2DocumentIsConvertedBySwaggerParser() {
    String swagger2 = """
        $ref: '#/paths/~1pets/post'
        swagger: '2.0'
        info: {title: Pets, version: '1'}
        paths:
          /pets:
            post:
              parameters:
                - name: body
                  in: body
                  schema: {$ref: '#/definitions/Pet'}
              responses:
                '201': {description: created, schema: {$ref: '#/definitions/Pet'}}
        definitions:
          Pet:
            type: object
            required: [name]
            properties:
              id: {type: integer, readOnly: true}
              name: {type: string, x-source: /petName}
              tag: {type: string, x-nullable: true}""";
    assertThat(build(swagger2, SchemaUse.REQUEST, "{\"id\":1,\"petName\":\"Rex\",\"tag\":null,\"x\":0}"))
        .isEqualTo(json("{\"name\":\"Rex\",\"tag\":null}"));
    JsonNode response = schemas.compile(swagger2.replace("~1pets/post", "~1pets/post/responses"), SchemaUse.RESPONSE).node();
    assertThat(response.get("required")).isEqualTo(json("[\"name\"]"));
    assertThat(response.at("/properties").has("id")).isTrue();          // readOnly stays in the response
  }

  @Test
  void remoteRefsAreNeverFetched() {
    assertThatThrownBy(() -> schemas.compile("""
        $ref: '#/components/schemas/A'
        openapi: 3.0.3
        info: {title: t, version: '1'}
        paths: {}
        components:
          schemas:
            A: {$ref: 'http://169.254.169.254/latest/meta-data#/x'}""", SchemaUse.REQUEST))
        .hasMessageContaining("Only references inside the pasted text");
  }
}
