package com.example.operaton.connector.schema;

/** Which side of a call a schema describes: decides readOnly / writeOnly handling. */
public enum SchemaUse {
  /** path / query / header parameters (readOnly properties are left out, like in a request) */
  PARAMS,
  /** request body: readOnly properties are left out */
  REQUEST,
  /** response body: writeOnly properties are left out */
  RESPONSE
}
