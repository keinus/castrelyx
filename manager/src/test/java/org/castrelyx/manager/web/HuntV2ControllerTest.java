package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Cached application contexts keep independent scheduled workers alive. Keep this
// mocked-storage queue separate from their real-storage test databases.
@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:hunt-controller-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HuntV2ControllerTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired LocalAuthProvider auth;
  @Autowired HuntJobs jobs;
  @MockitoBean HuntStorage storage;
  private final String base = "/api/security/hunt/v2";
  private Cookie viewer, operator, other;

  private record Session(Cookie cookie, String csrf) {}

  @BeforeEach
  void setup() {
    db.update("delete from hunt_response_requests");
    db.update("delete from hunt_findings");
    db.update("delete from hunt_rule_runtime");
    db.update("delete from hunt_lookup_names");
    db.update("delete from hunt_evidence");
    db.update("delete from hunt_resources");
    db.update("delete from hunt_query_jobs");
    viewer = cookie(Role.VIEWER);
    operator = cookie(Role.OPERATOR);
    other = cookie(Role.ADMIN);
    when(storage.database()).thenReturn("test");
    when(storage.read(any(), anyString()))
        .thenAnswer(
            invocation -> {
              String id = invocation.getArgument(1);
              if (id.endsWith("summary"))
                return List.of(
                    Map.of(
                        "total",
                        10050,
                        "assets",
                        1,
                        "addresses",
                        10050,
                        "accounts",
                        1,
                        "oldest",
                        "2026-01-01 00:00:00",
                        "newest",
                        "2026-01-01 01:00:00"));
              if (id.endsWith("histogram"))
                return List.of(Map.of("time", 1767225600, "count", 10050));
              if (id.endsWith("groups"))
                return List.of(
                    Map.of("value", "auth.login.success", "metric", 10050, "count", 10050));
              return List.of(
                  Map.of(
                      "id",
                      "e".repeat(64),
                      "at",
                      "2026-01-01T00:30:00Z",
                      "event_type",
                      "auth.login.success",
                      "asset_uid",
                      "host",
                      "event_json",
                      "sensitive raw",
                      "actor",
                      "alice"));
            });
  }

  private Cookie cookie(Role role) {
    var user =
        auth.createLocalUser("hunt-" + UUID.randomUUID(), "test-only-password", role.name(), role);
    return new Cookie(AuthController.SESSION_COOKIE, auth.createSession(user));
  }

  private String csrf(Cookie cookie) throws Exception {
    return json.readTree(
            mvc.perform(get(base + "/session").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("csrfToken")
        .asText();
  }

  private String query() {
    return "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T01:00:00Z\",\"groupBy\":\"event_type\",\"pageSize\":200}";
  }

  @Test
  void compoundAggregationChecksEveryDimensionAndReturnsExplicitTopGroupAndGraphCoverage()
      throws Exception {
    var spec = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(query());
    spec.putArray("groupFields").add("event_type").add("command_line");
    mvc.perform(
            post(base + "/queries")
                .cookie(viewer)
                .header("X-Hunt-CSRF", csrf(viewer))
                .contentType("application/json")
                .content(spec.toString()))
        .andExpect(status().isForbidden());
    spec.putArray("groupFields").add("event_type").add("unsupported_field");
    mvc.perform(
            post(base + "/queries")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf(operator))
                .contentType("application/json")
                .content(spec.toString()))
        .andExpect(status().isBadRequest());
    spec.putArray("groupFields").add("event_type").add("source_id");
    spec.put("includeRelations", true);
    var groupRows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 101; i++)
      groupRows.add(Map.of("value", "type-" + i, "dimension_1", "sensor", "count", 1, "metric", 1));
    when(storage.read(any(), endsWith("-groups"))).thenReturn(groupRows);
    var accepted =
        json.readTree(
            mvc.perform(
                    post(base + "/queries")
                        .cookie(operator)
                        .header("X-Hunt-CSRF", csrf(operator))
                        .contentType("application/json")
                        .content(spec.toString()))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var result = completed(accepted.path("id").asText(), operator).path("result");
    assertThat(result.path("groups")).hasSize(100);
    assertThat(result.path("groupHasMore").asBoolean()).isTrue();
    assertThat(result.path("groups").get(0).path("dimensions").path("source_id").asText())
        .isEqualTo("sensor");
    assertThat(result.path("groups").get(0).has("dimension_1")).isFalse();
    assertThat(result.path("relations").path("coverage").path("complete").asBoolean()).isFalse();
    assertThat(result.path("query").path("includeRelations").asBoolean()).isTrue();
  }

  @Test
  void graphEvidenceDetailKeepsJobOwnershipValidationAndRoleRedaction() throws Exception {
    String id = start(viewer);
    completed(id, viewer);
    clearInvocations(storage);
    mvc.perform(get(base + "/queries/" + id + "/events/" + "e".repeat(64)).cookie(other))
        .andExpect(status().isForbidden());
    verify(storage, never()).read(any(), anyString());
    mvc.perform(get(base + "/queries/" + id + "/events/not-an-id").cookie(viewer))
        .andExpect(status().isBadRequest());
    mvc.perform(get(base + "/queries/" + id + "/events/" + "e".repeat(64)).cookie(viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("e".repeat(64)))
        .andExpect(jsonPath("$.event_json").doesNotExist());
    verify(storage)
        .read(
            argThat(statement -> statement.parameters().containsValue("e".repeat(64))),
            endsWith("-detail"));
  }

  @Test
  void savedSearchKeepsNestedAstAndMillisecondBoundsAndRejectsInvalidAggregation()
      throws Exception {
    var filter =
        Map.of(
            "op",
            "or",
            "children",
            List.of(
                Map.of("op", "eq", "field", "src_ip", "values", List.of("10.0.0.1")),
                Map.of(
                    "op", "not", "children", List.of(Map.of("op", "exists", "field", "actor")))));
    var spec = new LinkedHashMap<String, Object>();
    spec.put("from", "2026-10-21T00:01:02.345Z");
    spec.put("to", "2026-10-22T00:05:06.789Z");
    spec.put("filter", filter);
    spec.put("groupBy", "event_type");
    spec.put("pageSize", 500);
    String token = csrf(operator);
    var response =
        json.readTree(
            mvc.perform(
                    post(base + "/resources/saved-search")
                        .cookie(operator)
                        .header("X-Hunt-CSRF", token)
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "title",
                                    "nested query",
                                    "shared",
                                    false,
                                    "version",
                                    0,
                                    "body",
                                    Map.of("query", spec)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(response.path("body").path("query").path("filter"))
        .isEqualTo(json.valueToTree(filter));
    assertThat(response.path("body").path("query").path("from").asText())
        .isEqualTo(spec.get("from"));
    assertThat(response.path("body").path("query").path("pageSize").asInt()).isEqualTo(500);
    spec.put("groupBy", "unsupported_field");
    mvc.perform(
            post(base + "/resources/saved-search")
                .cookie(operator)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "title",
                            "bad group",
                            "shared",
                            false,
                            "version",
                            0,
                            "body",
                            Map.of("query", spec)))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void storageRolloutRequiresAdministratorAndSessionCsrfBeforeAnyStorageIo() throws Exception {
    mvc.perform(get(base + "/storage").cookie(viewer)).andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/storage/install")
                .cookie(other)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/storage/backfill")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf(operator))
                .contentType("application/json")
                .content("{\"partition\":\"20261022\"}"))
        .andExpect(status().isForbidden());
    verify(storage, never()).readModel(anyBoolean());
  }

  @Test
  void viewerCanExecuteReadPostButMustSupplySessionBoundCsrf() throws Exception {
    mvc.perform(
            post(base + "/queries").cookie(viewer).contentType("application/json").content(query()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/queries")
                .cookie(viewer)
                .header("X-Hunt-CSRF", csrf(operator))
                .contentType("application/json")
                .content(query()))
        .andExpect(status().isForbidden());
    String id = start(viewer);
    JsonNode result = completed(id, viewer);
    assertThat(result.path("result").path("summary").path("total").asInt()).isEqualTo(10050);
    assertThat(result.toString()).doesNotContain("sensitive raw");
    mvc.perform(get(base + "/queries/" + id).cookie(other)).andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/queries/" + id + "/cancel")
                .cookie(viewer)
                .header("X-Hunt-CSRF", csrf(viewer))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive raw"))));
  }

  @Test
  void investigationsUseOptimisticVersionsAndPinServerVerifiedEvidence() throws Exception {
    String token = csrf(operator);
    String body =
        "{\"title\":\"investigation\",\"shared\":false,\"body\":{\"hypothesis\":\"test\",\"status\":\"INVESTIGATING\",\"verdict\":\"INSUFFICIENT\"}}";
    JsonNode saved =
        json.readTree(
            mvc.perform(
                    post(base + "/resources/investigation")
                        .cookie(operator)
                        .header("X-Hunt-CSRF", token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String investigation = saved.path("id").asText();
    mvc.perform(
            put(base + "/resources/investigation/" + investigation)
                .cookie(operator)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    String job = start(operator);
    completed(job, operator);
    mvc.perform(
            post(base + "/investigations/" + investigation + "/evidence")
                .cookie(operator)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("jobId", job, "eventId", "e".repeat(64)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sha256").isString());
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_evidence where investigation_id=?",
                Integer.class,
                investigation))
        .isEqualTo(1);
    mvc.perform(get(base + "/investigations/" + investigation + "/evidence").cookie(operator))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive raw"))));
    mvc.perform(get(base + "/investigations/" + investigation + "/evidence").cookie(other))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsBadFieldsAndPreventsViewerWrites() throws Exception {
    mvc.perform(
            post(base + "/queries")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf(operator))
                .contentType("application/json")
                .content(
                    query().replace("\"groupBy\":\"event_type\"", "\"groupBy\":\"drop_table\"")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(base + "/resources/investigation")
                .cookie(viewer)
                .header("X-Hunt-CSRF", csrf(viewer))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(base + "/scenarios").cookie(viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(48));
  }

  @Test
  void ruleActivationCannotBypassObservationEvenThroughGenericAdminSave() throws Exception {
    String token = csrf(other);
    var body = new LinkedHashMap<String, Object>();
    body.put("scenarioId", "A01");
    body.put("state", "ACTIVE");
    body.put("query", json.readTree(query()));
    var save = new LinkedHashMap<String, Object>();
    save.put("title", "test rule");
    save.put("shared", true);
    save.put("body", body);
    mvc.perform(
            post(base + "/resources/rule")
                .cookie(other)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content(json.writeValueAsString(save)))
        .andExpect(status().isBadRequest());
    body.put("state", "DRAFT");
    var rule =
        json.readTree(
            mvc.perform(
                    post(base + "/resources/rule")
                        .cookie(other)
                        .header("X-Hunt-CSRF", token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(save)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String id = rule.path("id").asText();
    mvc.perform(
            post(base + "/rules/" + id + "/transition")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf(operator))
                .contentType("application/json")
                .content("{\"state\":\"OBSERVE\",\"version\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/rules/" + id + "/transition")
                .cookie(other)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content("{\"state\":\"OBSERVE\",\"version\":1}"))
        .andExpect(status().isOk());
    mvc.perform(
            post(base + "/rules/" + id + "/transition")
                .cookie(other)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content("{\"state\":\"ACTIVE\",\"version\":2}"))
        .andExpect(status().isConflict());
    db.update(
        "update hunt_rule_runtime set observed_since=?,ready_runs=10 where rule_id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(8 * 86400L)),
        id);
    mvc.perform(
            post(base + "/rules/" + id + "/transition")
                .cookie(other)
                .header("X-Hunt-CSRF", token)
                .contentType("application/json")
                .content("{\"state\":\"ACTIVE\",\"version\":2}"))
        .andExpect(status().isOk());
  }

  private String start(Cookie cookie) throws Exception {
    return json.readTree(
            mvc.perform(
                    post(base + "/queries")
                        .cookie(cookie)
                        .header("X-Hunt-CSRF", csrf(cookie))
                        .contentType("application/json")
                        .content(query()))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("id")
        .asText();
  }

  @Test
  void viewerCannotUseRawJsonFreeTextAsAnAlternativeToForbiddenFieldConditions() throws Exception {
    var spec = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(query());
    spec.put("text", "sensitive raw");
    for (String path : List.of("/queries", "/scenarios/A01/runs"))
      mvc.perform(
              post(base + path)
                  .cookie(viewer)
                  .header("X-Hunt-CSRF", csrf(viewer))
                  .contentType("application/json")
                  .content(spec.toString()))
          .andExpect(status().isForbidden());
    assertThat(db.queryForObject("select count(*) from hunt_query_jobs", Integer.class)).isZero();
  }

  @Test
  void sharedSavedSearchMetadataUsesTheReadersRoleWithoutChangingStoredQuery() throws Exception {
    var spec = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(query());
    spec.put("text", "password=saved-search-secret");
    spec.putObject("filter")
        .put("op", "contains")
        .put("field", "command_line")
        .putArray("values")
        .add("tool token=saved-search-secret");
    var save = json.createObjectNode().put("title", "shared sensitive query").put("shared", true);
    save.putObject("body").set("query", spec);
    var created =
        json.readTree(
            mvc.perform(
                    post(base + "/resources/saved-search")
                        .cookie(other)
                        .header("X-Hunt-CSRF", csrf(other))
                        .contentType("application/json")
                        .content(save.toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (Cookie reader : List.of(viewer, operator)) {
      for (String path :
          List.of(
              "/resources/saved-search",
              "/resources/saved-search/" + created.path("id").asText())) {
        String response =
            mvc.perform(get(base + path).cookie(reader))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("saved-search-secret");
      }
    }
    assertThat(
            mvc.perform(
                    get(base + "/resources/saved-search/" + created.path("id").asText())
                        .cookie(other))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains("saved-search-secret");
    assertThat(
            db.queryForObject(
                "select body_json from hunt_resources where id=?",
                String.class,
                created.path("id").asText()))
        .contains("saved-search-secret");
  }

  @Test
  void everyMutationAndReadPostRejectsMissingInvalidForeignAndSameUserOtherSessionCsrf()
      throws Exception {
    clearInvocations(storage);
    String id = "00000000-0000-4000-8000-000000000001";
    var routes =
        new ArrayList<String[]>(
            List.of(
                new String[] {"POST", "/queries"},
                new String[] {"POST", "/scenarios/A01/runs"},
                new String[] {"POST", "/queries/" + id + "/cancel"},
                new String[] {"POST", "/storage/install"},
                new String[] {"POST", "/storage/backfill"},
                new String[] {"POST", "/rules/" + id + "/transition"}));
    for (String kind : List.of("saved-search", "investigation", "rule", "lookup", "indicator")) {
      routes.add(new String[] {"POST", "/resources/" + kind});
      routes.add(new String[] {"PUT", "/resources/" + kind + "/" + id});
    }
    for (String suffix :
        List.of("assignment", "review", "decision", "reopen", "evidence", "exports"))
      routes.add(new String[] {"POST", "/investigations/" + id + "/" + suffix});
    for (Cookie reader : List.of(viewer, operator, other)) {
      var sameUser =
          new Cookie(
              AuthController.SESSION_COOKIE,
              auth.createSession(auth.currentUser(reader.getValue())));
      var unrelated = reader == other ? viewer : other;
      for (String[] route : routes)
        for (String token : List.of("", "invalid-token", csrf(unrelated), csrf(sameUser))) {
          var request =
              request(org.springframework.http.HttpMethod.valueOf(route[0]), base + route[1])
                  .cookie(reader)
                  .contentType("application/json")
                  .content("{}");
          if (!token.isEmpty()) request.header("X-Hunt-CSRF", token);
          mvc.perform(request).andExpect(status().isForbidden());
        }
    }
    assertThat(db.queryForObject("select count(*) from hunt_resources", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from hunt_query_jobs", Integer.class)).isZero();
    verifyNoInteractions(storage);
  }

  private JsonNode completed(String id, Cookie cookie) throws Exception {
    jobs.dispatch();
    for (int i = 0; i < 100; i++) {
      var node =
          json.readTree(
              mvc.perform(get(base + "/queries/" + id).cookie(cookie))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      if (node.path("state").asText().equals("COMPLETED")) return node;
      if (node.path("state").asText().equals("FAILED")) throw new AssertionError(node);
      Thread.sleep(20);
    }
    throw new AssertionError("job did not finish");
  }
}
