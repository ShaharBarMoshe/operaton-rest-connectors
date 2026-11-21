package com.example.operaton.connector.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the schema to use in what a modeler pasted, without translating it: the validator
 * (networknt, OpenAPI dialect) and the shaper both read the pasted document itself, so {@code $ref}s,
 * {@code nullable}, {@code readOnly} and the rest keep their OpenAPI meaning.
 *
 * <p>Accepted (JSON or YAML, OpenAPI 3.1, 3.0 or Swagger 2.0):
 * <ul>
 *   <li>a schema object, e.g. {@code components.schemas.Customer} (plain JSON Schema works too)</li>
 *   <li>a {@code parameters} list of an operation (for paramsSchema)</li>
 *   <li>a {@code requestBody} / response object ({@code content → application/json → schema}),
 *       a {@code responses} map (the first 2xx is used), or a whole operation object</li>
 *   <li>any of these plus the referenced {@code components} (or the whole document) and a root
 *       {@code "$ref": "#/components/schemas/Customer"} naming what to use</li>
 * </ul>
 *
 * The result is a copy of the document with the target (or, for parameters, the params schema built
 * from them) placed under {@value #TARGET}, so every {@code $ref} in it still resolves against the
 * document. References must stay inside the pasted text.
 */
final class PastedSchema {

  /** Where the target sits in {@link #document()}. */
  static final String TARGET = "x-connector-target";

  enum Dialect { OPENAPI_30, OPENAPI_31 }

  private static final Pattern STATUS = Pattern.compile("[1-5]([0-9]{2}|XX)|default");
  /** Swagger 2 parameter fields that describe the value itself (no nested "schema"). */
  private static final Set<String> PARAM_VALUE_FIELDS = Set.of(
      "type", "format", "items", "enum", "default", "minimum", "maximum", "exclusiveMinimum",
      "exclusiveMaximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
      "multipleOf", "nullable", "x-nullable");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final ObjectNode document;
  private final Dialect dialect;

  private PastedSchema(ObjectNode document, Dialect dialect) {
    this.document = document;
    this.dialect = dialect;
  }

  ObjectNode document() {
    return document;
  }

  JsonNode target() {
    return document.get(TARGET);
  }

  Dialect dialect() {
    return dialect;
  }

  static PastedSchema locate(JsonNode pasted, SchemaUse use) {
    JsonNode root = pasted;
    JsonNode target = pasted;
    Dialect dialect = Dialect.OPENAPI_31;              // fragments: JSON Schema 2020-12 + OpenAPI vocabulary
    if (root.isObject() && (root.has("openapi") || root.has("swagger") || root.has("paths")
        || (root.has("$ref") && (root.has("components") || root.has("definitions"))))) {
      if (!root.has("$ref")) {
        throw new IllegalArgumentException("A whole OpenAPI document was pasted: add a top-level "
            + "\"$ref\" naming what to use, e.g. \"$ref\": \"#/components/schemas/Customer\"");
      }
      String ref = root.get("$ref").asText();
      if (root.has("openapi") || root.has("swagger")) {
        // a real document: let swagger-parser read it (Swagger 2.0 → OpenAPI 3.0, spec errors)
        root = OpenApiDocuments.normalize(root);
        ref = OpenApiDocuments.v3Ref(ref);
        dialect = root.path("openapi").asText().startsWith("3.1") ? Dialect.OPENAPI_31 : Dialect.OPENAPI_30;
      }
      target = resolve(root, ref);
    }
    Locator locator = new Locator(root, use);
    target = locator.unwrap(target);
    JsonNode schema = target.isArray() ? locator.parameters((ArrayNode) target) : target.deepCopy();

    ObjectNode document = root.isObject() ? ((ObjectNode) root).deepCopy() : F.objectNode();
    document.set(TARGET, schema);
    document.remove("$ref");                           // ours (names the target), not part of the schema
    checkReferences(document, schema, new HashSet<>());
    if (dialect == Dialect.OPENAPI_31) {
      olderKeywords(document);                         // OpenAPI 3.0 / Swagger 2 style in a fragment
    }
    return new PastedSchema(document, dialect);
  }

  // ------------------------------------------------------------- finding the target

  private record Locator(JsonNode root, SchemaUse use) {

    /** requestBody / response / responses map / operation / Swagger 2 body parameter → schema. */
    JsonNode unwrap(JsonNode node) {
      for (int guard = 0; guard < 8; guard++) {
        node = deref(root, node);
        if (!node.isObject() || isSchemaLike(node)) {
          return node;
        }
        if (node.has("operationId") || node.has("responses") || node.has("requestBody")
            || (node.has("parameters") && node.get("parameters").isArray())) {   // an operation
          node = switch (use) {
            case PARAMS -> require(node, "parameters", "operation has no parameters");
            case REQUEST -> node.has("requestBody") ? node.get("requestBody") : swagger2Body(node);
            case RESPONSE -> require(node, "responses", "operation has no responses");
          };
        } else if (node.get("content") != null && node.get("content").isObject()) {
          node = mediaTypeSchema(node.get("content"));
        } else if (node.has("schema") && (node.has("description") || "body".equals(node.path("in").asText()))) {
          node = node.get("schema");                                          // Swagger 2 response / body param
        } else if (isResponsesMap(node)) {
          node = successResponse(node);
        } else {
          return node;
        }
      }
      throw new IllegalArgumentException("Could not find a schema in the pasted OpenAPI fragment");
    }

    private JsonNode successResponse(JsonNode responses) {
      JsonNode fallback = null;
      for (Iterator<Map.Entry<String, JsonNode>> it = responses.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode r = deref(root, e.getValue());
        boolean hasBody = r.has("content") || r.has("schema");
        if (e.getKey().startsWith("2") && hasBody) return r;
        if (e.getKey().equals("default") && hasBody) fallback = r;
      }
      if (fallback == null) {
        throw new IllegalArgumentException("The pasted responses have no 2xx (or default) response with a body schema");
      }
      return fallback;
    }

    private JsonNode swagger2Body(JsonNode operation) {
      for (JsonNode p : operation.path("parameters")) {
        JsonNode param = deref(root, p);
        if ("body".equals(param.path("in").asText())) return param.get("schema");
      }
      throw new IllegalArgumentException("The pasted operation has no requestBody");
    }

    /** An operation's parameters → one object schema, each param a property with its x-in. */
    ObjectNode parameters(ArrayNode params) {
      ObjectNode schema = F.objectNode().put("type", "object").put("additionalProperties", false);
      ArrayNode required = F.arrayNode();
      ObjectNode props = F.objectNode();
      for (JsonNode p : params) {
        JsonNode param = deref(root, p);
        String name = param.path("name").asText(null);
        String in = param.path("in").asText("query");
        if (name == null) throw new IllegalArgumentException("Parameter without name: " + param);
        if (in.equals("body") || in.equals("formData")) continue;           // Swagger 2: not a URL / header param
        if (in.equals("cookie")) throw new IllegalArgumentException("Cookie parameter '" + name + "' is not supported");

        ObjectNode value;
        if (param.has("schema")) {
          value = F.objectNode().set("allOf", F.arrayNode().add(param.get("schema").deepCopy()));
        } else if (param.has("content") && param.get("content").isObject()) {
          value = F.objectNode().set("allOf", F.arrayNode().add(mediaTypeSchema(param.get("content")).deepCopy()));
        } else {                                                             // Swagger 2: fields on the parameter
          value = F.objectNode();
          ObjectNode v = value;
          param.properties().forEach(e -> { if (PARAM_VALUE_FIELDS.contains(e.getKey())) v.set(e.getKey(), e.getValue().deepCopy()); });
        }
        if (value.has("allOf") && value.get("allOf").get(0).isObject() && !value.get("allOf").get(0).has("$ref")) {
          value = (ObjectNode) value.get("allOf").get(0);                    // inline schema: no wrapper needed
        }
        ObjectNode v = value;
        param.properties().forEach(e -> {                                    // x-expression / x-source on the parameter
          if (e.getKey().startsWith("x-") && !e.getKey().equals("x-nullable")) v.set(e.getKey(), e.getValue());
        });
        value.put(JsonComposer.X_IN, in);
        props.set(name, value);
        if (param.path("required").asBoolean(false) || in.equals("path")) required.add(name);
      }
      if (!required.isEmpty()) schema.set("required", required);
      schema.set("properties", props);
      return schema;
    }
  }

  private static boolean isSchemaLike(JsonNode n) {
    return n.has("type") || n.has("properties") || n.has("allOf") || n.has("oneOf") || n.has("anyOf")
        || n.has("enum") || n.has("const") || n.has("items");
  }

  private static boolean isResponsesMap(JsonNode n) {
    if (n.isEmpty()) return false;
    for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
      if (!STATUS.matcher(it.next()).matches()) return false;
    }
    return true;
  }

  private static JsonNode mediaTypeSchema(JsonNode content) {
    JsonNode chosen = content.get("application/json");
    for (Iterator<Map.Entry<String, JsonNode>> it = content.fields(); it.hasNext() && chosen == null; ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getKey().contains("json")) chosen = e.getValue();
    }
    if (chosen == null) chosen = content.get("*/*");                       // Swagger 2 without consumes / produces
    if (chosen == null && content.size() == 1) chosen = content.elements().next();
    if (chosen == null || !chosen.has("schema")) {
      throw new IllegalArgumentException("The pasted content has no JSON media type with a schema: "
          + content.properties().stream().map(Map.Entry::getKey).toList());
    }
    return chosen.get("schema");
  }

  private static JsonNode require(JsonNode node, String field, String message) {
    if (!node.has(field)) throw new IllegalArgumentException("Cannot use the pasted OpenAPI fragment: " + message);
    return node.get(field);
  }

  // ------------------------------------------------------------- references

  private static JsonNode deref(JsonNode root, JsonNode node) {
    for (int guard = 0; node != null && node.isObject() && node.has("$ref") && node.size() == 1 && guard < 32; guard++) {
      node = resolve(root, node.get("$ref").asText());
    }
    return node;
  }

  private static JsonNode resolve(JsonNode root, String ref) {
    if (!ref.startsWith("#")) {
      throw new IllegalArgumentException("Only references inside the pasted text are supported, got \"" + ref
          + "\" - paste the referenced schema too");
    }
    JsonNode target = root.at(ref.substring(1));
    if (target.isMissingNode()) {
      throw new IllegalArgumentException("Unresolved \"$ref\": \"" + ref + "\" - paste the referenced "
          + "components too (e.g. the whole components section or the whole document)");
    }
    return target;
  }

  /** Every $ref reachable from the target must point inside the pasted text (no remote loading). */
  private static void checkReferences(JsonNode document, JsonNode node, Set<String> seen) {
    if (node == null || !node.isContainerNode()) return;
    JsonNode ref = node.get("$ref");
    if (ref != null && ref.isTextual() && seen.add(ref.asText())) {
      checkReferences(document, resolve(document, ref.asText()), seen);
    }
    node.forEach(child -> checkReferences(document, child, seen));
  }

  /**
   * Fragments are validated with the 3.1 dialect (JSON Schema 2020-12, nullable switched on). Two
   * older spellings are rewritten: OpenAPI 3.0 {@code exclusiveMinimum: true} next to {@code minimum}
   * → the number form, Swagger 2 {@code x-nullable} → {@code nullable}.
   */
  private static void olderKeywords(JsonNode node) {
    if (node == null || !node.isContainerNode()) return;
    if (node instanceof ObjectNode o) {
      if (o.has("x-nullable") && !o.has("nullable")) o.set("nullable", o.remove("x-nullable"));
      for (String[] k : new String[][] {{"exclusiveMinimum", "minimum"}, {"exclusiveMaximum", "maximum"}}) {
        JsonNode flag = o.get(k[0]);
        if (flag != null && flag.isBoolean()) {
          if (flag.asBoolean() && o.has(k[1])) o.set(k[0], o.remove(k[1])); else o.remove(k[0]);
        }
      }
    }
    node.forEach(PastedSchema::olderKeywords);
  }
}
