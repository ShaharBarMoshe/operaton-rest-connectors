package com.example.operaton.connector.schema;

import com.fasterxml.jackson.databind.JsonNode;

/** Turns the schema text pasted into a task into something that can shape and validate JSON. */
public interface SchemaCompiler {

  /**
   * @param text the schema as pasted (never a path): OpenAPI / Swagger or JSON Schema, JSON or YAML
   * @throws IllegalArgumentException when the text is not a usable schema
   */
  CompiledSchema compile(String text, SchemaUse use);

  /** @throws SchemaViolationException listing every violation */
  void validate(CompiledSchema schema, JsonNode document, String what);
}
