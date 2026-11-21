package com.example.operaton.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.operaton.bpm.engine.runtime.ProcessInstanceWithVariables;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every error a REST task can raise ends customer-snapshot in the error event subprocess
 * ("Handle REST errors") at the end event for that kind of error, with errorCode / errorMessage set.
 */
@SpringBootTest
class CustomerSnapshotErrorsTest extends NginxMockSupport {

  static final String GET = "GET";
  static final String POST = "POST";
  static final String CUSTOMER = "/api/customers/501/";
  static final String SNAPSHOTS = "/api/customer-snapshots";

  /** name, fault to inject (method, uri, status, body or null), start overrides, expected code, end event, POST sent? */
  static Stream<Arguments> errors() {
    return Stream.of(
        Arguments.of("401 bad token", null, Map.of("accessToken", "not-a-jwt"), "HTTP_401", "errEndAuth", false),
        Arguments.of("403 on a GET", fault(GET, CUSTOMER + "account", 403, null), Map.of(), "HTTP_403", "errEndAuth", false),
        Arguments.of("404 unknown customer", null, Map.of("customerId", 999), "HTTP_404", "errEndNotFound", false),
        Arguments.of("params break schema", null, Map.of("customerId", 0), "REQUEST_INVALID", "errEndInvalid", false),
        Arguments.of("body breaks schema", fault(GET, CUSTOMER + "profile", 200, "{\"customer\":{\"id\":501}}"), Map.of(),
            "REQUEST_INVALID", "errEndInvalid", false),
        Arguments.of("response breaks schema", fault(POST, SNAPSHOTS, 201, "{\"id\":42}"), Map.of(),
            "RESPONSE_INVALID", "errEndInvalid", true),
        Arguments.of("503 on a GET", fault(GET, CUSTOMER + "loyalty", 503, null), Map.of(), "HTTP_503", "errEndUnavailable", false),
        Arguments.of("500 on the POST", fault(POST, SNAPSHOTS, 500, null), Map.of(), "HTTP_500", "errEndUnavailable", true),
        Arguments.of("connection refused", null, Map.of("apiBaseUrl", "http://127.0.0.1:1/api"), "CONNECTION_FAILED",
            "errEndUnavailable", false),
        Arguments.of("409 on the POST", fault(POST, SNAPSHOTS, 409, "{\"message\":\"snapshot exists\"}"), Map.of(),
            "HTTP_409", "errEndRejected", true),
        Arguments.of("429 on a GET", fault(GET, CUSTOMER + "address", 429, null), Map.of(), "HTTP_429", "errEndRejected", false));
  }

  static String fault(String method, String uri, int status, String body) {
    return "{\"method\":\"" + method + "\",\"uri\":\"" + uri + "\",\"status\":" + status
        + (body == null ? "" : ",\"body\":" + body) + "}";
  }

  @ParameterizedTest(name = "{0} -> {3} -> {4}")
  @MethodSource("errors")
  void endsAtTheMatchingErrorEnd(String name, String fault, Map<String, Object> overrides, String expectedCode,
                                 String expectedEnd, boolean postSent) throws Exception {
    if (fault != null) {
      mock.put().uri("/__mock/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(fault).retrieve().toBodilessEntity();
    }
    Map<String, Object> vars = new HashMap<>(Map.of(
        "apiBaseUrl", baseUrl() + "/api", "customerId", 501, "accessToken", CustomerSnapshotNginxTest.TOKEN));
    vars.putAll(overrides);

    ProcessInstanceWithVariables pi = start("customer-snapshot", vars);

    assertThat(endActivity(pi)).as(name).isEqualTo(expectedEnd);
    Map<String, Object> result = variables(pi);
    assertThat(result).as(name).containsEntry("errorCode", expectedCode).containsKey("errorMessage")
        .doesNotContainKey("snapshot");
    JsonNode calls = calls();
    boolean posted = false;
    for (JsonNode c : calls) posted |= POST.equals(c.get("method").asText());
    assertThat(posted).as(name + ": POST sent").isEqualTo(postSent);
    assertNoVariablesStored(pi);
  }

  @Test
  void errorMessageCarriesTheResponseBody() throws Exception {
    mock.put().uri("/__mock/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(fault(POST, SNAPSHOTS, 409, "{\"message\":\"snapshot exists\"}")).retrieve().toBodilessEntity();

    ProcessInstanceWithVariables pi = start("customer-snapshot", Map.of(
        "apiBaseUrl", baseUrl() + "/api", "customerId", 501, "accessToken", CustomerSnapshotNginxTest.TOKEN));

    assertThat(variables(pi).get("errorMessage")).asString().contains("snapshot exists");
  }
}
