package com.example.operaton.connector.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;

/**
 * A pasted schema, ready to shape and validate JSON.
 *
 * @param document  the pasted document (Swagger 2 converted to OpenAPI 3); local {@code $ref}s resolve against it
 * @param node      the schema to use, inside {@code document} (for params: built from the parameters list)
 * @param validator networknt validator for {@code node}, in the document's OpenAPI dialect
 * @param use       params / request / response: the shaping leaves out readOnly (request) or writeOnly
 *                  (response) properties, the validator does not require them
 */
public record CompiledSchema(JsonNode document, JsonNode node, JsonSchema validator, SchemaUse use) {

  /** The property flag that makes a property not belong in this direction. */
  public String excludedFlag() {
    return use == SchemaUse.RESPONSE ? "writeOnly" : "readOnly";
  }
}
