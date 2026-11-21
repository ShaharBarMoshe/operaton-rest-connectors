package com.example.operaton.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Compiles (and caches) JSON Schemas given as inline JSON strings and validates documents
 * against them.
 */
public class JsonSchemaService {

  /** Our custom annotation keywords – declared so the validator does not warn about them. */
  public static final String X_SOURCE = JsonComposer.X_SOURCE;
  public static final String X_IN = JsonComposer.X_IN;
  public static final String X_FLATTEN = JsonComposer.X_FLATTEN;
  public static final String X_EXPRESSION = JsonComposer.X_EXPRESSION;

  private final JsonComposer composer;
  private final JsonSchemaFactory factory;
  private final SchemaValidatorsConfig config;
  private final Map<String, Loaded> cache = new ConcurrentHashMap<>();

  public record Loaded(JsonNode node, JsonSchema schema) {}

  public JsonSchemaService(JsonComposer composer) {
    this.composer = composer;
    JsonMetaSchema meta = JsonMetaSchema.builder(JsonMetaSchema.getV202012())
        .keyword(new NonValidationKeyword(X_SOURCE))
        .keyword(new NonValidationKeyword(X_IN))
        .keyword(new NonValidationKeyword(X_FLATTEN))
        .keyword(new NonValidationKeyword(X_EXPRESSION))
        .build();
    this.factory = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
        .metaSchema(meta)
        .defaultMetaSchemaIri(meta.getIri())
        .build();
    this.config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  }

  /**
   * @param json the schema itself as a JSON string (an input parameter value), never a path
   */
  public Loaded load(String json) {
    String trimmed = json.trim();
    if (!trimmed.startsWith("{")) {
      String head = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
      throw new IllegalArgumentException("JSON Schema must be given as an inline JSON object string, got: " + head);
    }
    return cache.computeIfAbsent(trimmed, r -> {
      JsonNode node;
      try {
        node = composer.parse(r);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("JSON Schema is not valid JSON: " + e.getMessage(), e);
      }
      return new Loaded(node, factory.getSchema(node, config));
    });
  }

  public void validate(Loaded schema, JsonNode document, String what) {
    Set<ValidationMessage> errors = schema.schema().validate(document.isMissingNode() ? composer.parse("null") : document);
    if (!errors.isEmpty()) {
      throw new SchemaViolationException(what, errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.toList()));
    }
  }

}
