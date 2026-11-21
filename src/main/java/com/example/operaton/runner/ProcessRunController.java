package com.example.operaton.runner;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs a process to its end in one request:
 * <pre>
 * POST /api/process/customer-snapshot/run?timeoutSeconds=30
 * {"variables": {"apiBaseUrl": "http://localhost:8081/api", "customerId": 501, "accessToken": "eyJ..."}}
 * </pre>
 * Variables are plain JSON values (objects / arrays become Json variables). Always 200 with
 * {@code "status": "SUCCESS" | "FAILURE"}; 404 for an unknown process key.
 */
@RestController
@RequestMapping("/api/process")
public class ProcessRunController {

  private final ProcessRunner runner;

  public ProcessRunController(ProcessRunner runner) {
    this.runner = runner;
  }

  @PostMapping("/{processKey}/run")
  public RunResult run(@PathVariable String processKey,
                       @RequestBody(required = false) Map<String, Object> body,
                       @RequestParam(required = false) Integer timeoutSeconds) {
    if (!runner.exists(processKey)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No process definition with key '" + processKey + "'");
    }
    Object variables = body == null ? Map.of() : body.getOrDefault("variables", Map.of());
    if (!(variables instanceof Map<?, ?> map)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"variables\" must be a JSON object");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> vars = (Map<String, Object>) map;
    return runner.run(processKey, vars, timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds));
  }
}
