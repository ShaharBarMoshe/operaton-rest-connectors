package com.example.operaton.connector.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.AnnotationKeyword;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.oas.OpenApi30;
import com.networknt.schema.oas.OpenApi31;
import com.networknt.schema.resource.MapSchemaLoader;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link SchemaCompiler} for schemas pasted from OpenAPI / Swagger documents (or plain JSON Schema),
 * JSON or YAML. {@link PastedSchema} finds the schema to use; it is validated as it is with
 * networknt's OpenAPI dialects (3.0 for OpenAPI 3.0 / Swagger 2.0 documents, 3.1 = JSON Schema
 * 2020-12 for 3.1 documents and fragments), which also handle {@code nullable}, {@code $ref}
 * and readOnly / writeOnly per direction ({@code discriminator} is not enforced). Schemas are only loaded from the
 * pasted text, never from a URL. Compiled schemas are cached by text and use.
 */
public class OpenApiSchemaCompiler implements SchemaCompiler {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenApiSchemaCompiler.class);

  private final JsonComposer composer;
  private final JsonSchemaFactory openApi30;
  private final JsonSchemaFactory openApi31;
  /** The pasted documents by their in-memory URI: the only place the validator may load from. */
  private final Map<String, String> documents = new ConcurrentHashMap<>();
  private final Map<String, CompiledSchema> cache = new ConcurrentHashMap<>();
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  public OpenApiSchemaCompiler(JsonComposer composer) {
    this.composer = composer;
    this.openApi30 = factory(OpenApi30.getInstance());
    this.openApi31 = factory(OpenApi31.getInstance());
  }

  /**
   * networknt with an OpenAPI dialect, loading schemas only from {@link #documents}. Keywords the
   * dialect does not know (our x-source / x-in / x-expression / x-flatten, other x- extensions, the
   * document's own keys such as paths or components) are annotations: ignored by validation, no warning.
   */
  private JsonSchemaFactory factory(JsonMetaSchema dialect) {
    JsonMetaSchema meta = JsonMetaSchema.builder(dialect)
        .unknownKeywordFactory((name, context) -> new AnnotationKeyword(name))
        .build();
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, b -> b
        .metaSchema(meta)
        .defaultMetaSchemaIri(meta.getIri())
        .schemaLoaders(loaders -> loaders
            .schemas(documents::get)
            .values(list -> list.removeIf(l -> !(l instanceof MapSchemaLoader)))));   // never http:, file:, classpath:
  }

  private static SchemaValidatorsConfig config(SchemaUse use) {
    return SchemaValidatorsConfig.builder()
        .formatAssertionsEnabled(true)
        .nullableKeywordEnabled(true)
        .readOnly(use != SchemaUse.RESPONSE)      // request: readOnly properties are not required (and not sent)
        .writeOnly(use == SchemaUse.RESPONSE)     // response: writeOnly properties are not required
        .build();
  }

  /**
   * @param text the schema as pasted into the input parameter (never a path): JSON or YAML
   * @param use  params / request body / response – decides readOnly / writeOnly handling
   */
  @Override
  public CompiledSchema compile(String text, SchemaUse use) {
    String trimmed = text.strip();
    CompiledSchema cached = cache.get(use + "\u0000" + trimmed);
    if (cached != null) {
      LOG.debug("Schema ({}): cached", use);
      return cached;
    }
    return cache.computeIfAbsent(use + "\u0000" + trimmed, key -> {
      long start = System.nanoTime();
      PastedSchema pasted = PastedSchema.locate(parse(trimmed), use);
      String json = pasted.document().toString();
      String uri = "urn:pasted:" + sha256(json);
      documents.put(uri, json);
      JsonSchemaFactory factory = pasted.dialect() == PastedSchema.Dialect.OPENAPI_30 ? openApi30 : openApi31;
      JsonSchema validator = factory.getSchema(SchemaLocation.of(uri + "#/" + PastedSchema.TARGET), config(use));
      validator.initializeValidators();          // resolve every $ref now: a broken paste fails at compile time
      LOG.info("Schema ({}): parsed {} chars as {}, dialect {}, compiled in {} ms", use, trimmed.length(),
          pasted.description(), pasted.dialect(), (System.nanoTime() - start) / 1_000_000);
      return new CompiledSchema(pasted.document(), pasted.target(), validator, use);
    });
  }

  private JsonNode parse(String text) {
    if (text.startsWith("{") || text.startsWith("[")) {
      try {
        return composer.parse(text);
      } catch (IllegalArgumentException notJson) {
        try {
          return yaml.readTree(text);     // YAML flow style: {type: object, ...}
        } catch (Exception notYaml) {
          throw new IllegalArgumentException("Schema is not valid JSON: " + notJson.getMessage(), notJson);
        }
      }
    }
    JsonNode node;
    try {
      node = yaml.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("Schema is neither JSON nor valid YAML: " + e.getMessage(), e);
    }
    if (node == null || !node.isContainerNode()) {
      String head = text.length() > 60 ? text.substring(0, 60) + "..." : text;
      throw new IllegalArgumentException("Schema must be a JSON / YAML object or a parameters list, got: " + head);
    }
    return node;
  }

  private static String sha256(String text) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void validate(CompiledSchema schema, JsonNode document, String what) {
    Set<ValidationMessage> errors = schema.validator().validate(document.isMissingNode() ? composer.parse("null") : document);
    if (errors.isEmpty()) {
      LOG.info("{}: valid", what);
    } else {
      throw new SchemaViolationException(what, errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.toList()));
    }
  }

}
