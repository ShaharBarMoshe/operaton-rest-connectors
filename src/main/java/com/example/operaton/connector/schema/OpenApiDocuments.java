package com.example.operaton.connector.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.List;

/**
 * Reads a whole pasted OpenAPI 3.0 / 3.1 or Swagger 2.0 document with swagger-parser and returns it
 * as an OpenAPI 3 document (Swagger 2 is converted: definitions → components.schemas, body
 * parameters → requestBody, ...). References are NOT resolved by the parser: it would fetch remote
 * {@code $ref} URLs from the engine; {@link PastedSchema} only accepts local ones.
 */
final class OpenApiDocuments {

  private OpenApiDocuments() {
  }

  static JsonNode normalize(JsonNode document) {
    ObjectNode copy = ((ObjectNode) document).deepCopy();
    copy.remove("$ref");                               // ours, not part of the document
    ParseOptions options = new ParseOptions();
    options.setResolve(false);
    options.setResolveFully(false);
    options.setFlatten(false);
    SwaggerParseResult result = new OpenAPIParser().readContents(copy.toString(), null, options);
    OpenAPI api = result.getOpenAPI();
    if (api == null) {
      List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
      throw new IllegalArgumentException("Not a valid OpenAPI / Swagger document: " + String.join("; ", messages));
    }
    boolean v31 = api.getOpenapi() != null && api.getOpenapi().startsWith("3.1");
    return (v31 ? Json31.mapper() : Json.mapper()).valueToTree(api);
  }

  /** Swagger 2 pointers → their OpenAPI 3 place after conversion. */
  static String v3Ref(String ref) {
    return ref.replaceFirst("^#/definitions/", "#/components/schemas/")
        .replaceFirst("^#/parameters/", "#/components/parameters/")
        .replaceFirst("^#/responses/", "#/components/responses/");
  }
}
