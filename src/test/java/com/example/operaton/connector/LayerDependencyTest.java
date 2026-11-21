package com.example.operaton.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the layers honest (see README "Architecture"): engine → core → schema / http, only the
 * engine layer knows Operaton, config may wire everything; and inside a layer, which sub-package may
 * use which.
 */
class LayerDependencyTest {

  static final Path ROOT = Path.of("src/main/java/com/example/operaton/connector");
  static final Pattern IMPORT = Pattern.compile(
      "^import (?:static )?com\\.example\\.operaton\\.connector\\.(\\w+)(?:\\.(\\w+))?\\.", Pattern.MULTILINE);

  static final Map<String, Set<String>> LAYERS = Map.of(
      "config", Set.of("engine", "core", "schema", "http"),
      "engine", Set.of("core", "schema"),
      "core", Set.of("schema", "http"),
      "schema", Set.of(),
      "http", Set.of());

  /** Per layer with sub-packages: sub-package → the sub-packages of the same layer it may use. */
  static final Map<String, Map<String, Set<String>>> SUB_PACKAGES = Map.of(
      "core", Map.of(
          "error", Set.of(),
          "api", Set.of("error"),
          "request", Set.of("api", "error"),
          "response", Set.of("api", "error", "request"),
          "service", Set.of("api", "error", "request", "response")),
      "engine", Map.of(
          "auth", Set.of(),
          "input", Set.of("auth"),
          "output", Set.of(),
          "trace", Set.of(),
          "delegate", Set.of("input", "output", "trace")),
      "http", Map.of(
          "transport", Set.of(),
          "params", Set.of(),
          "restclient", Set.of("transport")));

  @Test
  void dependenciesFollowTheRules() throws Exception {
    List<String> violations = new ArrayList<>();
    for (String layer : LAYERS.keySet()) {
      try (Stream<Path> files = Files.walk(ROOT.resolve(layer))) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          String where = ROOT.relativize(file).toString();
          String sub = ROOT.resolve(layer).relativize(file).getNameCount() > 1
              ? ROOT.resolve(layer).relativize(file).getName(0).toString() : null;
          Map<String, Set<String>> subRules = SUB_PACKAGES.get(layer);
          if (subRules != null && (sub == null || !subRules.containsKey(sub))) {
            violations.add(where + ": " + layer + " classes belong in one of " + subRules.keySet());
          }
          String source = Files.readString(file);
          Matcher m = IMPORT.matcher(source);
          while (m.find()) {
            String targetLayer = m.group(1);
            String targetSub = m.group(2) != null && Character.isLowerCase(m.group(2).charAt(0)) ? m.group(2) : null;
            if (targetLayer.equals(layer)) {
              if (subRules != null && sub != null && subRules.containsKey(sub)
                  && !targetSub.equals(sub) && !subRules.get(sub).contains(targetSub)) {
                violations.add(where + " imports " + layer + "." + targetSub);
              }
            } else if (!LAYERS.get(layer).contains(targetLayer)) {
              violations.add(where + " imports " + targetLayer);
            }
          }
          if (!layer.equals("engine") && source.contains("org.operaton.")) {
            violations.add(where + " uses Operaton (only the engine layer may)");
          }
        }
      }
    }
    assertThat(violations).isEmpty();
  }
}
