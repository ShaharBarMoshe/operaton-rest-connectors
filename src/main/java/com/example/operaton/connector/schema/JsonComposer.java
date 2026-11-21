package com.example.operaton.connector.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds a JSON document from execution objects + a JSON Schema.
 *
 * <ol>
 *   <li><b>merge</b> – deep-merges the sources in order. Objects merge recursively, everything
 *       else (scalars, arrays) is replaced by the later source.</li>
 *   <li><b>shape</b> – walks the schema and picks values out of the merged document.
 *       Supported extras per property:
 *       <ul>
 *         <li>{@code "x-source": "/json/pointer"} – take the value from that pointer in the merged
 *             document instead of the property with the same name</li>
 *         <li>{@code "x-expression": "userId"} – evaluate an Operaton expression, written without
 *             the ${ } wrapper (process
 *             variables, beans, literals)</li>
 *         <li>{@code const} / {@code default} – used when no value was found</li>
 *         <li>{@code "x-flatten": true} on an object schema – nested input objects are flattened
 *             first ({@code address.city} → {@code city}); see {@link #flatten}</li>
 *       </ul>
 *       With {@code additionalProperties: false} unknown fields are dropped. Scalars are coerced
 *       to the schema type when possible ("1042" → 1042, 7 → "7", "true" → true).</li>
 * </ol>
 *
 * Validation itself is done afterwards by {@link SchemaCompiler}.
 */
public class JsonComposer {

  public static final String X_SOURCE = "x-source";
  /** Operaton expression WITHOUT the ${ } wrapper, e.g. "userId" – the engine would already
   *  evaluate a literal ${...} inside an input-parameter string before the connector sees it. */
  public static final String X_EXPRESSION = "x-expression";
  public static final String X_IN = "x-in";
  public static final String X_FLATTEN = "x-flatten";

  private final ObjectMapper mapper;

  public JsonComposer(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  // ---------------------------------------------------------------- conversion

  /** Converts a value (JSON string, Map, List, POJO, JsonNode) to a JsonNode. Engine types are converted by the engine adapter first. */
  public JsonNode toJson(Object value) {
    if (value == null) {
      return MissingNode.getInstance();
    }
    if (value instanceof JsonNode node) {
      return node;
    }
    if (value instanceof CharSequence text) {
      String s = text.toString().trim();
      if (s.startsWith("{") || s.startsWith("[")) {
        return parse(s);
      }
      return TextNode.valueOf(text.toString());
    }
    return mapper.valueToTree(value);
  }

  public JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  /** JsonNode → plain Java (LinkedHashMap / ArrayList / String / Number / Boolean). */
  public Object toJava(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return mapper.convertValue(node, Object.class);
  }

  // ---------------------------------------------------------------- merge

  public JsonNode merge(List<?> sources) {
    JsonNode result = MissingNode.getInstance();
    for (Object source : sources) {
      result = merge(result, toJson(source));
    }
    return result;
  }

  public JsonNode merge(JsonNode base, JsonNode override) {
    if (override == null || override.isMissingNode()) {
      return base;
    }
    if (base == null || base.isMissingNode() || !base.isObject() || !override.isObject()) {
      return override.deepCopy();
    }
    ObjectNode result = ((ObjectNode) base).deepCopy();
    override.properties().forEach(e -> result.set(e.getKey(), merge(result.get(e.getKey()), e.getValue())));
    return result;
  }

  // ---------------------------------------------------------------- shape

  /** Shapes a single document (e.g. a response). */
  public JsonNode shape(CompiledSchema schema, JsonNode data, Function<String, Object> resolver) {
    return shape(schema, List.of(data == null ? MissingNode.getInstance() : data), resolver);
  }

  /**
   * Shapes the deep-merge of {@code sources}. The individual sources are kept so that
   * {@code x-flatten} can flatten each one before merging (later source wins over earlier,
   * deeper key wins over outer key within one source).
   *
   * @param resolver evaluates {@code ${...}} expressions built from {@code x-expression} (may be null)
   */
  public JsonNode shape(CompiledSchema schema, List<JsonNode> sources, Function<String, Object> resolver) {
    return shape(schema, sources, resolver, null);
  }

  /**
   * @param fallback looks up a top-level property that the sources do not have (params: the process
   *                 variable with the same name); may be null
   */
  public JsonNode shape(CompiledSchema schema, List<JsonNode> sources, Function<String, Object> resolver,
                        Function<String, Object> fallback) {
    JsonNode merged = merge(sources);
    return new Shaper(schema.document(), schema.excludedFlag(), merged, sources, resolver, fallback)
        .shape(schema.node(), merged, "");
  }

  /**
   * Flattens one object: every leaf (non-object value; arrays count as leaves) is promoted to the
   * top level under its own key. On collisions the deeper key wins; at equal depth the later one
   * in document order wins. Keys listed in {@code keepObjects} are not descended into.
   */
  public ObjectNode flatten(JsonNode node, Set<String> keepObjects) {
    Map<String, Integer> depthOf = new java.util.HashMap<>();
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (node != null && node.isObject()) {
      flattenInto(node, 0, keepObjects, depthOf, out);
    }
    return out;
  }

  private void flattenInto(JsonNode node, int depth, Set<String> keep, Map<String, Integer> depthOf, ObjectNode out) {
    node.properties().forEach(e -> {
      JsonNode v = e.getValue();
      if (v.isObject() && !keep.contains(e.getKey())) {
        flattenInto(v, depth + 1, keep, depthOf, out);
      } else if (depth >= depthOf.getOrDefault(e.getKey(), -1)) {
        depthOf.put(e.getKey(), depth);
        out.set(e.getKey(), v.deepCopy());
      }
    });
  }

  private final class Shaper {
    /** the pasted document: local $refs resolve against it */
    private final JsonNode rootSchema;
    /** readOnly (request) / writeOnly (response): properties with this flag are left out */
    private final String excludedFlag;
    private final JsonNode rootData;
    private final List<JsonNode> sources;
    private final Function<String, Object> resolver;
    private final Function<String, Object> fallback;

    Shaper(JsonNode rootSchema, String excludedFlag, JsonNode rootData, List<JsonNode> sources,
           Function<String, Object> resolver, Function<String, Object> fallback) {
      this.rootSchema = rootSchema;
      this.excludedFlag = excludedFlag;
      this.rootData = rootData == null ? MissingNode.getInstance() : rootData;
      this.sources = sources;
      this.resolver = resolver;
      this.fallback = fallback;
    }

    /**
     * @param path JSON pointer of {@code value} inside the merged document, or null when the value
     *             did not come from the sources (e.g. an expression)
     */
    JsonNode shape(JsonNode schema, JsonNode value, String path) {
      schema = combined(deref(schema));
      if (schema == null || schema.isBoolean()) {
        return value == null ? MissingNode.getInstance() : value;
      }
      if (schema.path(X_FLATTEN).asBoolean(false)) {
        value = flattenValue(schema, value, path);
        path = null; // flattened values no longer map 1:1 to source paths
      }
      if (isMissing(value)) {
        if (schema.has("const")) return schema.get("const").deepCopy();
        if (schema.has("default")) return schema.get("default").deepCopy();
      }
      if (isType(schema, "object") || schema.has("properties")) {
        return shapeObject(schema, value, path);
      }
      if (isType(schema, "array") || schema.has("items")) {
        return shapeArray(schema, value, path);
      }
      return isMissing(value) ? MissingNode.getInstance() : coerce(schema, value);
    }

    private JsonNode flattenValue(JsonNode schema, JsonNode value, String path) {
      // properties that are themselves objects in the target schema stay nested
      Set<String> keep = new java.util.HashSet<>();
      schema.path("properties").properties().forEach(p -> {
        JsonNode ps = deref(p.getValue());
        if (ps != null && (isType(ps, "object") || ps.has("properties"))) keep.add(p.getKey());
      });
      if (path == null) {
        return value != null && value.isObject() ? flatten(value, keep) : value;
      }
      // flatten each source separately, then merge: later source wins over earlier
      JsonNode result = MissingNode.getInstance();
      for (JsonNode source : sources) {
        JsonNode part = source.at(path);
        if (part.isObject()) {
          result = merge(result, flatten(part, keep));
        }
      }
      return result;
    }

    private JsonNode shapeObject(JsonNode schema, JsonNode value, String path) {
      if (!isMissing(value) && !value.isObject()) {
        return value; // wrong type: leave it to the validator to report
      }
      ObjectNode in = isMissing(value) ? null : (ObjectNode) value;
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      JsonNode props = schema.path("properties");

      Iterator<Map.Entry<String, JsonNode>> it = props.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> p = it.next();
        JsonNode propSchema = deref(p.getValue());
        if (combined(propSchema) != null && combined(propSchema).path(excludedFlag).asBoolean(false)) {
          continue;                       // readOnly in a request / writeOnly in a response: not ours to send / keep
        }
        JsonNode raw;
        String childPath;
        if (propSchema != null && propSchema.has(X_EXPRESSION)) {
          raw = evaluate("${" + propSchema.get(X_EXPRESSION).asText() + "}");
          childPath = null;
        } else if (propSchema != null && propSchema.has(X_SOURCE)) {
          String source = propSchema.get(X_SOURCE).asText();
          if (isExpression(source)) {
            raw = evaluate(source);
            childPath = null;
          } else {
            childPath = source.isEmpty() || source.startsWith("/") ? source : "/" + source;
            raw = rootData.at(childPath);
          }
        } else {
          raw = in == null ? MissingNode.getInstance() : in.path(p.getKey());
          childPath = child(path, p.getKey());
          if (isMissing(raw) && fallback != null && "".equals(path)) {
            Object v = fallback.apply(p.getKey());
            raw = toJson(v);
            childPath = null;
          }
        }
        JsonNode shaped = shape(propSchema, raw, childPath);
        if (!isMissing(shaped)) {
          out.set(p.getKey(), shaped);
        }
      }

      // only the declared properties are taken over, unless additionalProperties allows more
      // (schemas pasted from OpenAPI rarely say additionalProperties: false); a free-form object
      // (no properties declared) keeps everything
      JsonNode additional = schema.get("additionalProperties");
      boolean keepExtra = additional == null ? props.isEmpty()
          : !(additional.isBoolean() && !additional.asBoolean());
      if (in != null && keepExtra) {
        in.properties().forEach(e -> {
          if (!props.has(e.getKey())) {
            out.set(e.getKey(), additional != null && additional.isObject()
                ? shape(additional, e.getValue(), child(path, e.getKey())) : e.getValue());
          }
        });
      }
      return out.isEmpty() && in == null ? MissingNode.getInstance() : out;
    }

    private JsonNode shapeArray(JsonNode schema, JsonNode value, String path) {
      if (isMissing(value)) {
        return MissingNode.getInstance();
      }
      ArrayNode in;
      if (value.isArray()) {
        in = (ArrayNode) value;
      } else {
        in = JsonNodeFactory.instance.arrayNode().add(value); // single value → one-element array
        path = null;
      }
      JsonNode itemSchema = schema.get("items");
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      Set<JsonNode> seen = new LinkedHashSet<>();
      boolean unique = schema.path("uniqueItems").asBoolean(false);
      for (int i = 0; i < in.size(); i++) {
        JsonNode item = in.get(i);
        JsonNode shaped = itemSchema == null ? item : shape(itemSchema, item, child(path, String.valueOf(i)));
        if (!isMissing(shaped) && (!unique || seen.add(shaped))) {
          out.add(shaped);
        }
      }
      return out;
    }

    private String child(String path, String key) {
      return path == null ? null : path + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private JsonNode evaluate(String expression) {
      if (resolver == null) {
        throw new IllegalStateException("x-expression without resolver: " + expression);
      }
      return toJson(resolver.apply(expression));
    }

    private JsonNode coerce(JsonNode schema, JsonNode v) {
      String type = schema.path("type").isTextual() ? schema.get("type").asText() : null;
      if (schema.path("type").isArray()) {                 // e.g. ["string", "null"] from nullable
        for (JsonNode t : schema.get("type")) {
          if (!"null".equals(t.asText())) { type = t.asText(); break; }
        }
      }
      if (type == null || v.isNull()) {
        return v;
      }
      try {
        switch (type) {
          case "string":
            if (v.isValueNode() && !v.isTextual()) return TextNode.valueOf(v.asText());
            break;
          case "integer":
            if (v.isTextual()) return integer(new BigDecimal(v.asText().trim()));
            if (v.isNumber() && !v.isIntegralNumber()) return integer(v.decimalValue());
            break;
          case "number":
            if (v.isTextual()) return JsonNodeFactory.instance.numberNode(new BigDecimal(v.asText().trim()));
            break;
          case "boolean":
            if (v.isTextual() && ("true".equalsIgnoreCase(v.asText()) || "false".equalsIgnoreCase(v.asText()))) {
              return JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(v.asText()));
            }
            break;
          default:
        }
      } catch (ArithmeticException | NumberFormatException ignored) {
        // not coercible – validator reports it
      }
      return v;
    }

    /**
     * allOf / anyOf / oneOf (common in OpenAPI: inheritance, nullable refs): for shaping, the
     * properties of all members count as declared; validation still applies the real semantics.
     */
    private JsonNode combined(JsonNode schema) {
      if (schema == null || !schema.isObject()
          || !(schema.has("allOf") || schema.has("anyOf") || schema.has("oneOf"))) {
        return schema;
      }
      ObjectNode out = ((ObjectNode) schema).deepCopy();
      ObjectNode props = out.has("properties") ? (ObjectNode) out.get("properties") : JsonNodeFactory.instance.objectNode();
      boolean open = false;
      boolean object = isType(out, "object") || out.has("properties");
      for (String k : List.of("allOf", "anyOf", "oneOf")) {
        for (JsonNode member : out.path(k)) {
          JsonNode m = combined(deref(member));
          if (m == null || !m.isObject()) continue;
          if (isType(m, "object") || m.has("properties")) object = true;
          m.path("properties").properties().forEach(p -> { if (!props.has(p.getKey())) props.set(p.getKey(), p.getValue()); });
          JsonNode ap = m.get("additionalProperties");
          if (ap != null && !(ap.isBoolean() && !ap.asBoolean())) open = true;
          if (!out.has("type") && m.has("type") && !isType(m, "null")) out.set("type", m.get("type"));
          if (!out.has("items") && m.has("items")) out.set("items", m.get("items"));
        }
        out.remove(k);
      }
      if (object) {
        out.set("properties", props);
        if (open && !out.has("additionalProperties")) out.put("additionalProperties", true);
      }
      return out;
    }

    /** Resolves local "$ref": "#/$defs/..." references. */
    private JsonNode deref(JsonNode schema) {
      int guard = 0;
      while (schema != null && schema.has("$ref") && guard++ < 32) {
        String ref = schema.get("$ref").asText();
        if (!ref.startsWith("#")) {
          throw new IllegalArgumentException("Only local $ref supported, got " + ref);
        }
        schema = rootSchema.at(ref.substring(1));
      }
      return schema;
    }
  }

  private static JsonNode integer(BigDecimal d) {
    java.math.BigInteger i = d.toBigIntegerExact();
    if (i.bitLength() < 32) return JsonNodeFactory.instance.numberNode(i.intValue());
    if (i.bitLength() < 64) return JsonNodeFactory.instance.numberNode(i.longValue());
    return JsonNodeFactory.instance.numberNode(i);
  }

  static boolean isExpression(String s) {
    return s.startsWith("${") || s.startsWith("#{");
  }

  static boolean isMissing(JsonNode n) {
    return n == null || n.isMissingNode();
  }

  static boolean isType(JsonNode schema, String type) {
    JsonNode t = schema.get("type");
    if (t == null) return false;
    if (t.isTextual()) return type.equals(t.asText());
    for (JsonNode x : t) if (type.equals(x.asText())) return true;
    return false;
  }
}
