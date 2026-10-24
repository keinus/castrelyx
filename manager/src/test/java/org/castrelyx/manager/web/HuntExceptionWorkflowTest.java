package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:hunt-exception-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HuntExceptionWorkflowTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired LocalAuthProvider auth;
  @Autowired HuntScenarios catalogue;
  private final String base = "/api/security/hunt/v2";
  private Cookie operator;
  private String csrf, job;
  private Instant now;
  private Cookie admin, outsider, viewer;

  @BeforeEach
  void prepare() throws Exception {
    db.update("delete from hunt_exception_history");
    db.update("delete from hunt_exceptions");
    now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var user =
        auth.createLocalUser(
            "exception-" + UUID.randomUUID(), "test-only-password", "Analyst", Role.OPERATOR);
    operator = new Cookie(AuthController.SESSION_COOKIE, auth.createSession(user));
    admin = actor(Role.ADMIN);
    outsider = actor(Role.OPERATOR);
    viewer = actor(Role.VIEWER);
    csrf =
        json.readTree(
                mvc.perform(get(base + "/session").cookie(operator))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("csrfToken")
            .asText();
    job = UUID.randomUUID().toString();
    var query =
        new HuntQuery(now.minusSeconds(600), now, null, "", "event_type", "count", 200, null);
    var candidate =
        Map.of(
            "src_ip",
            "192.0.2.10",
            "dst_ip",
            "198.51.100.10",
            "asset_uid",
            "qa-host",
            "realm",
            "qa-realm",
            "actor",
            "qa-actor",
            "count",
            5,
            "first_seen",
            now.minusSeconds(120).toString(),
            "last_seen",
            now.minusSeconds(60).toString());
    var result =
        Map.of(
            "query",
            query,
            "scenario",
            Map.of(
                "id",
                "A01",
                "version",
                catalogue.require("A01").version(),
                "readiness",
                "READY",
                "complete",
                true,
                "findings",
                List.of(candidate)));
    db.update(
        "insert into hunt_query_jobs(id,owner_id,scenario_id,query_json,result_json,state,created_at,updated_at,as_of,expires_at,asset_aliases_json,scenario_context_json) values(?,?,?,?,?,'COMPLETED',?,?,?,?,?,?)",
        job,
        user.id(),
        "A01",
        json.writeValueAsString(query),
        json.writeValueAsString(result),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(86400)),
        "{}",
        json.writeValueAsString(
            new HuntScenarioRunner.Context(catalogue.require("A01").version(), Map.of())));
  }

  private Cookie actor(Role role) {
    var user =
        auth.createLocalUser(
            "exception-" + UUID.randomUUID(), "test-only-password", role.name(), role);
    return new Cookie(AuthController.SESSION_COOKIE, auth.createSession(user));
  }

  private String token(Cookie actor) throws Exception {
    return json.readTree(
            mvc.perform(get(base + "/session").cookie(actor))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("csrfToken")
        .asText();
  }

  private Map<String, Object> input() {
    return Map.of(
        "jobId",
        job,
        "candidateIndex",
        0,
        "title",
        "승인된 정기 점검",
        "reason",
        "변경 요청 QA-1에 등록된 점검",
        "validFrom",
        now.minusSeconds(300).toString(),
        "expiresAt",
        now.plusSeconds(3600).toString());
  }

  private JsonNode create() throws Exception {
    return json.readTree(
        mvc.perform(
                post(base + "/exceptions")
                    .cookie(operator)
                    .header("X-Hunt-CSRF", csrf)
                    .contentType("application/json")
                    .content(json.writeValueAsString(input())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode decide(JsonNode item, Cookie actor, String action, int status) throws Exception {
    return json.readTree(
        mvc.perform(
                post(base + "/exceptions/" + item.path("id").asText() + "/decision")
                    .cookie(actor)
                    .header("X-Hunt-CSRF", token(actor))
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "version",
                                item.path("version").asLong(),
                                "action",
                                action,
                                "reason",
                                "검토한 변경 요청 근거"))))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void cancellingAnUnapprovedRequestDoesNotPublishItsPrivateEvidence() throws Exception {
    var request = create();
    mvc.perform(get(base + "/exceptions/" + request.path("id").asText()).cookie(outsider))
        .andExpect(status().isForbidden());
    var cancelled = decide(request, operator, "REVOKE", 200);
    assertThat(cancelled.path("state").asText()).isEqualTo("REVOKED");
    mvc.perform(get(base + "/exceptions/" + request.path("id").asText()).cookie(outsider))
        .andExpect(status().isForbidden());
    var page =
        json.readTree(
            mvc.perform(get(base + "/exceptions").cookie(viewer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.path("items").size()).isZero();
  }

  @Test
  void approvalIsVersionedPublicAndRequiresAnIndependentAdministrator() throws Exception {
    var request = create();
    decide(request, operator, "APPROVE", 403);
    decide(request, outsider, "APPROVE", 403);
    var approved = decide(request, admin, "APPROVE", 200);
    assertThat(approved.path("version").asLong()).isEqualTo(2);
    assertThat(approved.path("history").size()).isEqualTo(2);
    assertThat(approved.path("evidenceSha256").asText())
        .isEqualTo(request.path("evidenceSha256").asText());
    mvc.perform(get(base + "/exceptions/" + request.path("id").asText()).cookie(viewer))
        .andExpect(status().isOk());
    decide(request, admin, "APPROVE", 409);
    var revoked = decide(approved, operator, "REVOKE", 200);
    assertThat(revoked.path("history").size()).isEqualTo(3);
    decide(revoked, admin, "APPROVE", 409);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "invalid", "other-user", "same-user-other-session"})
  void bothNewMutationRoutesRejectEveryInvalidSessionTokenBeforeChangingState(String kind)
      throws Exception {
    var pending = create();
    for (boolean decision : List.of(false, true)) {
      Cookie actor = decision ? admin : operator;
      String supplied =
          switch (kind) {
            case "missing" -> null;
            case "invalid" -> "invalid-token";
            case "other-user" -> token(decision ? operator : admin);
            default ->
                token(
                    new Cookie(
                        AuthController.SESSION_COOKIE,
                        auth.createSession(auth.currentUser(actor.getValue()))));
          };
      var request =
          post(base
                  + (decision
                      ? "/exceptions/" + pending.path("id").asText() + "/decision"
                      : "/exceptions"))
              .cookie(actor)
              .contentType("application/json")
              .content(
                  json.writeValueAsString(
                      decision
                          ? Map.of("version", 1, "action", "APPROVE", "reason", "valid reason")
                          : input()));
      if (supplied != null) request.header("X-Hunt-CSRF", supplied);
      mvc.perform(request).andExpect(status().isForbidden());
    }
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class))
        .isEqualTo(1);
    assertThat(db.queryForObject("select state from hunt_exceptions", String.class))
        .isEqualTo("REQUESTED");
  }

  @Test
  void jobOwnershipAndAuthenticationCannotBeBypassedByAnAdministratorOrViewer() throws Exception {
    for (Cookie actor : List.of(admin, outsider, viewer))
      mvc.perform(
              post(base + "/exceptions")
                  .cookie(actor)
                  .header("X-Hunt-CSRF", token(actor))
                  .contentType("application/json")
                  .content(json.writeValueAsString(input())))
          .andExpect(status().isForbidden());
    for (String path :
        List.of("/exceptions", "/exceptions/" + job, "/exceptions/" + job + "/usage"))
      mvc.perform(get(base + path)).andExpect(status().isUnauthorized());
    mvc.perform(post(base + "/exceptions").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class)).isZero();
  }

  @Test
  void clientSuppliedScopeAndStateCannotExpandAnExceptionOrEditItsFrozenEvidence()
      throws Exception {
    var body = new LinkedHashMap<>(input());
    body.put("scope", Map.of("actor", "*"));
    body.put("state", "APPROVED");
    body.put("scenarioId", "B01");
    var entry =
        json.readTree(
            mvc.perform(
                    post(base + "/exceptions")
                        .cookie(operator)
                        .header("X-Hunt-CSRF", csrf)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(entry.path("scope").path("actor").asText()).isEqualTo("qa-actor");
    assertThat(entry.path("state").asText()).isEqualTo("REQUESTED");
    assertThat(entry.path("scenarioId").asText()).isEqualTo("A01");
    mvc.perform(
            put(base + "/exceptions/" + entry.path("id").asText())
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isMethodNotAllowed());
    var after =
        json.readTree(
            mvc.perform(get(base + "/exceptions/" + entry.path("id").asText()).cookie(operator))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.path("evidenceSha256")).isEqualTo(entry.path("evidenceSha256"));
    assertThat(after.path("version").asLong()).isEqualTo(1);
    assertThat(HuntWorkspace.hash(after.path("evidenceJson").asText()))
        .isEqualTo(after.path("exportEvidenceSha256").asText());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"incomplete", "unavailable", "bad-index", "running", "expired", "ordinary-query"})
  void requestsRequireACompleteOwnedLiveScenarioCandidate(String condition) throws Exception {
    var body = new LinkedHashMap<>(input());
    int expected = 400;
    if (condition.equals("running")) {
      db.update("update hunt_query_jobs set state='RUNNING' where id=?", job);
      expected = 409;
    } else if (condition.equals("expired")) {
      db.update(
          "update hunt_query_jobs set expires_at=? where id=?",
          Timestamp.from(now.minusSeconds(1)),
          job);
      expected = 409;
    } else if (condition.equals("bad-index")) body.put("candidateIndex", 2);
    else {
      var result =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              json.readTree(
                  db.queryForObject(
                      "select result_json from hunt_query_jobs where id=?", String.class, job));
      if (condition.equals("ordinary-query")) result.remove("scenario");
      else if (condition.equals("incomplete"))
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("scenario"))
            .put("complete", false);
      else
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("scenario"))
            .put("readiness", "UNAVAILABLE");
      db.update(
          "update hunt_query_jobs set result_json=? where id=?",
          json.writeValueAsString(result),
          job);
    }
    mvc.perform(
            post(base + "/exceptions")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().is(expected));
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "null", "fraction", "text", "huge"})
  void malformedCandidateIndicesAreNotSilentlyConvertedToAnotherCandidate(String variant)
      throws Exception {
    var body = new LinkedHashMap<>(input());
    switch (variant) {
      case "missing" -> body.remove("candidateIndex");
      case "null" -> body.put("candidateIndex", null);
      case "fraction" -> body.put("candidateIndex", 0.5);
      case "text" -> body.put("candidateIndex", "0");
      case "huge" -> body.put("candidateIndex", Long.MAX_VALUE);
    }
    mvc.perform(
            post(base + "/exceptions")
                .cookie(operator)
                .header("X-Hunt-CSRF", csrf)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class)).isZero();
  }

  @Test
  void storedExceptionEvidenceDoesNotDependOnTheSourceJobsLifetime() throws Exception {
    var pending = create();
    var approved = decide(pending, admin, "APPROVE", 200);
    var before =
        json.readTree(
            mvc.perform(get(base + "/exceptions/" + approved.path("id").asText()).cookie(operator))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    db.update("delete from hunt_query_jobs where id=?", job);
    var retained =
        json.readTree(
            mvc.perform(get(base + "/exceptions/" + approved.path("id").asText()).cookie(operator))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(retained.path("evidenceSha256")).isEqualTo(approved.path("evidenceSha256"));
    assertThat(retained.path("evidenceJson")).isEqualTo(before.path("evidenceJson"));
  }

  @Test
  void analystCanRequestAnImmutableTimeBoundExceptionFromAnActualOwnedCandidate() throws Exception {
    var input =
        Map.of(
            "jobId",
            job,
            "candidateIndex",
            0,
            "title",
            "승인된 정기 점검",
            "reason",
            "변경 요청 QA-1에 등록된 점검",
            "validFrom",
            now.minusSeconds(300).toString(),
            "expiresAt",
            now.plusSeconds(3600).toString());
    var response =
        mvc.perform(
                post(base + "/exceptions")
                    .cookie(operator)
                    .header("X-Hunt-CSRF", csrf)
                    .contentType("application/json")
                    .content(json.writeValueAsString(input)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    var value = json.readTree(response.getContentAsString());
    assertThat(value.path("state").asText()).isEqualTo("REQUESTED");
    assertThat(value.path("scenarioId").asText()).isEqualTo("A01");
    assertThat(value.path("version").asLong()).isEqualTo(1);
    assertThat(value.path("scope").path("actor").asText()).isEqualTo("qa-actor");
    assertThat(value.path("evidenceSha256").asText()).matches("[a-f0-9]{64}");
  }
}
