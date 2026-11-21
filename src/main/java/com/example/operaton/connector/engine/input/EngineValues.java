package com.example.operaton.connector.engine.input;

import org.operaton.spin.json.SpinJsonNode;

/** Turns engine-specific variable values into plain ones before they leave the engine layer. */
final class EngineValues {

  private EngineValues() {
  }

  /** Spin {@code Json} values → their JSON text; everything else unchanged. */
  static Object plain(Object value) {
    return value instanceof SpinJsonNode spin ? spin.toString() : value;
  }
}
