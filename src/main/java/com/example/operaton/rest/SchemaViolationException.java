package com.example.operaton.rest;

import java.util.List;

public class SchemaViolationException extends RuntimeException {
  private final List<String> errors;

  public SchemaViolationException(String what, List<String> errors) {
    super(what + " does not match schema: " + String.join("; ", errors));
    this.errors = errors;
  }

  public List<String> getErrors() {
    return errors;
  }
}
