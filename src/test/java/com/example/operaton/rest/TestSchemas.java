package com.example.operaton.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test fixtures: reads a schema from src/test/resources so it can be passed inline, like in a model. */
public final class TestSchemas {
  private TestSchemas() {}

  public static String read(String path) {
    try (InputStream in = TestSchemas.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) throw new IllegalArgumentException("No test resource " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
