package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:hunt-evidence-export-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "manager.hunt.export-max-bytes=1048576"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HuntEvidenceExportTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired LocalAuthProvider auth;
  @Autowired HuntRetention retention;
  @MockitoSpyBean HuntWorkspace workspace;
  @MockitoBean HuntJobs jobs;
  @MockitoBean HuntStorage storage;
  @MockitoBean Clock clock;
  private static final String BASE = "/api/security/hunt/v2",
      EVENT = "e".repeat(64),
      JOB = UUID.randomUUID().toString();
  private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

  private record Actor(AuthUser user, Cookie cookie) {}

  private Actor owner, admin, viewer, other;

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    db.update("delete from hunt_response_requests");
    db.update("delete from hunt_evidence");
    db.update("delete from hunt_resources");
    db.update("delete from hunt_audit");
    db.update("delete from hunt_query_jobs");
    owner = actor(Role.OPERATOR);
    admin = actor(Role.ADMIN);
    viewer = actor(Role.VIEWER);
    other = actor(Role.OPERATOR);
    when(storage.database()).thenReturn("test");
    when(jobs.get(eq(JOB), any()))
        .thenReturn(new HuntJobs.Job(JOB, "COMPLETED", NOW, NOW, null, null));
    when(jobs.spec(eq(JOB), any()))
        .thenReturn(
            new HuntQuery(NOW.minusSeconds(60), NOW, null, "", "event_type", "count", 1, null));
    when(jobs.aliases(eq(JOB), any())).thenReturn(Map.of());
    when(storage.read(any(), anyString())).thenReturn(List.of(event()));
  }

  private Map<String, Object> event() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", EVENT);
    row.put("event_json", "RAW_FIXTURE_ONLY");
    row.put("command_line", "run --token=fixture-secret");
    row.put("message", "password=fixture-secret");
    row.put("wide_number", 9007199254740993L);
    row.put("decimal_number", 1.0);
    row.put("unicode", "한글·雪·🔎");
    return row;
  }

  private Actor actor(Role role) {
    var user =
        auth.createLocalUser(
            "evidence-" + UUID.randomUUID(), "fixture-password", role.name(), role);
    return new Actor(user, new Cookie(AuthController.SESSION_COOKIE, auth.createSession(user)));
  }

  private JsonNode result(MvcResult value, int expected) throws Exception {
    assertThat(value.getResponse().getStatus())
        .as(value.getResponse().getContentAsString())
        .isEqualTo(expected);
    return json.readTree(value.getResponse().getContentAsByteArray());
  }

  private JsonNode read(String path, Actor actor, int expected) throws Exception {
    return result(mvc.perform(get(BASE + path).cookie(actor.cookie())).andReturn(), expected);
  }

  private JsonNode post(String path, Object body, Actor actor, int expected) throws Exception {
    var token = read("/session", actor, 200).path("csrfToken").asText();
    return result(
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        BASE + path)
                    .cookie(actor.cookie())
                    .header("X-Hunt-CSRF", token)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn(),
        expected);
  }

  private JsonNode create(boolean shared) throws Exception {
    return post(
        "/resources/investigation",
        Map.of(
            "title",
            "정확한 증거 보존",
            "shared",
            shared,
            "body",
            Map.of("status", "INVESTIGATING", "verdict", "INSUFFICIENT", "notes", "분석가 메모")),
        owner,
        201);
  }

  private String id(JsonNode item) {
    return item.path("id").asText();
  }

  private JsonNode pin(JsonNode item, int status) throws Exception {
    return post(
        "/investigations/" + id(item) + "/evidence",
        Map.of("jobId", JOB, "eventId", EVENT),
        owner,
        status);
  }

  private JsonNode evidence(JsonNode item, Actor actor) throws Exception {
    return read("/investigations/" + id(item) + "/evidence", actor, 200);
  }

  private void reviewFixture(String id, long version, String snapshot) {
    db.update(
        "insert into hunt_case_reviews(investigation_id,case_version,submitted_by,reviewer_id,state,request_note,snapshot_json,sha256,created_at) values(?,?,?,?,'CHANGES_REQUESTED','fixture review',?,?,?)",
        id,
        version,
        owner.user().id(),
        admin.user().id(),
        snapshot,
        HuntWorkspace.hash(snapshot),
        Timestamp.from(NOW));
  }

  @Test
  void viewerPreparesAUserBoundSingleUseNativeDownloadWithSessionCsrf() throws Exception {
    var item = create(true);
    pin(item, 200);
    item = read("/resources/investigation/" + id(item), owner, 200);
    String path = "/investigations/" + id(item) + "/exports";
    var body = Map.of("version", item.path("version").asLong());
    for (String csrf : List.of("missing", "foreign")) {
      var builder =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BASE + path)
              .cookie(viewer.cookie())
              .contentType("application/json")
              .content(json.writeValueAsBytes(body));
      if (csrf.equals("foreign"))
        builder.header("X-Hunt-CSRF", read("/session", owner, 200).path("csrfToken").asText());
      result(mvc.perform(builder).andReturn(), 403);
    }
    var ticket = post(path, body, viewer, 201);
    String url = ticket.path("url").asText();
    assertThat(url).startsWith(BASE + "/exports/");
    assertThat(ticket.path("caseVersion").asLong()).isEqualTo(2);
    read(url.substring(BASE.length()), other, 403);
    var response = mvc.perform(get(url).cookie(viewer.cookie())).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    byte[] bytes = response.getContentAsByteArray();
    assertThat(ticket.path("length").asLong()).isEqualTo(bytes.length);
    assertThat(ticket.path("sha256").asText())
        .isEqualTo(HuntWorkspace.hash(new String(bytes, StandardCharsets.UTF_8)));
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .doesNotContain("RAW_FIXTURE_ONLY", "fixture-secret", "run --token");
    read(url.substring(BASE.length()), viewer, 409);
  }

  @Test
  void nativeDownloadRechecksPrivateAccessAndRoleAndRejectsAnExpiredTicket() throws Exception {
    var item = create(true);
    String path = "/investigations/" + id(item) + "/exports";
    var ticket = post(path, Map.of("version", 1), admin, 201);
    String token = read("/session", owner, 200).path("csrfToken").asText();
    result(
        mvc.perform(
                put(BASE + "/resources/investigation/" + id(item))
                    .cookie(owner.cookie())
                    .header("X-Hunt-CSRF", token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "title",
                                item.path("title").asText(),
                                "shared",
                                false,
                                "version",
                                1,
                                "body",
                                item.path("body")))))
            .andReturn(),
        200);
    read(ticket.path("url").asText().substring(BASE.length()), admin, 403);
    var shared = create(true);
    var roleTicket =
        post("/investigations/" + id(shared) + "/exports", Map.of("version", 1), admin, 201);
    db.update("update users set role='VIEWER' where id=?", admin.user().id());
    read(roleTicket.path("url").asText().substring(BASE.length()), admin, 403);
    var expiring =
        post("/investigations/" + id(shared) + "/exports", Map.of("version", 1), owner, 201);
    when(clock.instant()).thenReturn(NOW.plusSeconds(900));
    read(expiring.path("url").asText().substring(BASE.length()), owner, 409);
  }

  @Test
  void staleNativePreparationCannotCreateAnExportOfAnotherVersion() throws Exception {
    var item = create(true);
    pin(item, 200);
    post("/investigations/" + id(item) + "/exports", Map.of("version", 1), owner, 409);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='EXPORT_INVESTIGATION'", Long.class))
        .isZero();
  }

  @Test
  void anExportAndConcurrentCaseSaveProduceOneCoherentVersion() throws Exception {
    var item = create(true);
    String id = id(item);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              locked.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("export fixture timeout");
              return invocation.callRealMethod();
            })
        .when(workspace)
        .audit(any(), eq("EXPORT_INVESTIGATION"), eq(id), any());
    String token = read("/session", owner, 200).path("csrfToken").asText();
    try (var pool = Executors.newFixedThreadPool(2)) {
      try {
        var exported =
            pool.submit(
                () ->
                    mvc.perform(
                            get(BASE + "/investigations/" + id + "/export").cookie(owner.cookie()))
                        .andReturn()
                        .getResponse());
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var update =
            pool.submit(
                () ->
                    mvc.perform(
                            put(BASE + "/resources/investigation/" + id)
                                .cookie(owner.cookie())
                                .header("X-Hunt-CSRF", token)
                                .contentType("application/json")
                                .content(
                                    json.writeValueAsBytes(
                                        Map.of(
                                            "title",
                                            item.path("title").asText(),
                                            "shared",
                                            true,
                                            "version",
                                            1,
                                            "body",
                                            Map.of(
                                                "status",
                                                "INVESTIGATING",
                                                "verdict",
                                                "INSUFFICIENT",
                                                "notes",
                                                "수정 후 메모")))))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        var response = exported.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(200);
        var frozen = json.readTree(response.getContentAsByteArray());
        assertThat(frozen.path("investigation").path("version").asLong()).isEqualTo(1);
        assertThat(frozen.path("investigation").path("body").path("notes").asText())
            .isEqualTo("분석가 메모");
        assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(
                read("/resources/investigation/" + id, owner, 200)
                    .path("body")
                    .path("notes")
                    .asText())
            .isEqualTo("수정 후 메모");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void queryExpiryUsesTheSameBoundaryAndDoesNotRemoveUnexpiredPinnedEvidence() throws Exception {
    var item = create(true);
    pin(item, 200);
    var actual =
        new HuntJobs(
            db,
            json,
            storage,
            mock(HuntScenarioRunner.class),
            mock(AssetService.class),
            clock,
            mock(HuntJobResults.class));
    var ids = new LinkedHashMap<String, String>();
    try {
      for (String state : List.of("COMPLETED", "QUEUED", "RUNNING")) {
        String key = UUID.randomUUID().toString();
        ids.put(state, key);
        db.update(
            "insert into hunt_query_jobs(id,owner_id,query_json,state,created_at,updated_at,as_of,expires_at) values(?,?,'{}',?,?,?,?,?)",
            key,
            owner.user().id(),
            state,
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW));
        assertThatThrownBy(() -> actual.get(key, owner.user()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("만료");
      }
      String future = UUID.randomUUID().toString();
      db.update(
          "insert into hunt_query_jobs(id,owner_id,query_json,state,created_at,updated_at,as_of,expires_at) values(?,?,'{}','COMPLETED',?,?,?,?)",
          future,
          owner.user().id(),
          Timestamp.from(NOW.minusSeconds(60)),
          Timestamp.from(NOW.minusSeconds(60)),
          Timestamp.from(NOW.minusSeconds(60)),
          Timestamp.from(NOW.plusMillis(1)));
      assertThat(actual.get(future, owner.user()).state()).isEqualTo("COMPLETED");
      retention.expire();
      assertThat(
              db.queryForObject(
                  "select count(*) from hunt_query_jobs where id in (?,?)",
                  Long.class,
                  ids.get("COMPLETED"),
                  ids.get("QUEUED")))
          .isZero();
      assertThat(
              db.queryForObject(
                  "select count(*) from hunt_query_jobs where id=?",
                  Long.class,
                  ids.get("RUNNING")))
          .isEqualTo(1);
      assertThat(evidence(item, admin).size()).isEqualTo(1);
      when(clock.instant()).thenReturn(NOW.plusMillis(1));
      assertThatThrownBy(() -> actual.get(future, owner.user()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      actual.stop();
    }
  }

  @Test
  void corruptReviewIsRejectedWithoutCommittingAnExportAudit() throws Exception {
    var item = create(true);
    reviewFixture(id(item), 10, "{\"notes\":\"original review\"}");
    db.update("update hunt_case_reviews set snapshot_json='{}' where investigation_id=?", id(item));
    read("/investigations/" + id(item) + "/export", owner, 409);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='EXPORT_INVESTIGATION'", Long.class))
        .isZero();
  }

  @Test
  void exactSnapshotStringsPreserveWideNumbersDecimalsUnicodeAndRoleMasks() throws Exception {
    var item = create(true);
    pin(item, 200);
    String stored =
        db.queryForObject(
            "select snapshot_json from hunt_evidence where investigation_id=?",
            String.class,
            id(item));
    var original = evidence(item, admin).get(0);
    assertThat(original.path("snapshotJson").asText()).isEqualTo(stored);
    assertThat(HuntWorkspace.hash(original.path("snapshotJson").asText()))
        .isEqualTo(original.path("sha256").asText());
    assertThat(stored).contains("9007199254740993", "1.0", "한글·雪·🔎");
    for (var actor : List.of(owner, viewer)) {
      var copy = evidence(item, actor).get(0);
      var bytes = copy.path("snapshotJson").asText();
      assertThat(bytes)
          .isNotEmpty()
          .doesNotContain("RAW_FIXTURE_ONLY", "fixture-secret", "event_json");
      assertThat(HuntWorkspace.hash(bytes)).isEqualTo(copy.path("exportSha256").asText());
      assertThat(copy.path("sha256").asText()).isEqualTo(original.path("sha256").asText());
      assertThat(json.readTree(bytes)).isEqualTo(copy.path("snapshot"));
      assertThat(bytes).contains("9007199254740993", "1.0", "한글·雪·🔎");
      if (actor == viewer) assertThat(bytes).doesNotContain("run --token").contains("원문 조회 권한 필요");
    }
  }

  @Test
  void exportContainsAllReviewsAndAuditsBeyondScreenPagesAndHasExactFileDigest() throws Exception {
    var item = create(true);
    pin(item, 200);
    for (int i = 1; i <= 23; i++)
      reviewFixture(
          id(item),
          i,
          "{\"version\":" + i + ",\"body\":{\"notes\":\"검토 " + i + "\"},\"evidence\":[]}");
    for (int i = 0; i < 207; i++)
      db.update(
          "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,'FIXTURE_AUDIT',?,?,?)",
          owner.user().id(),
          id(item),
          "{\"sequence\":" + i + "}",
          Timestamp.from(NOW));
    var foreign = create(false);
    reviewFixture(id(foreign), 1, "{\"notes\":\"FOREIGN_CASE_SENTINEL\"}");
    var response =
        mvc.perform(get(BASE + "/investigations/" + id(item) + "/export").cookie(owner.cookie()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    byte[] bytes = response.getContentAsByteArray();
    var value = json.readTree(bytes);
    assertThat(value.path("reviews").size()).isEqualTo(23);
    assertThat(value.path("audit").size()).isEqualTo(210);
    assertThat(value.path("manifest").path("complete").asBoolean()).isTrue();
    assertThat(value.path("manifest").path("caseVersion").asLong()).isEqualTo(2);
    assertThat(value.path("manifest").path("reviewCount").asLong()).isEqualTo(23);
    assertThat(value.toString())
        .doesNotContain("FOREIGN_CASE_SENTINEL", "RAW_FIXTURE_ONLY", "fixture-secret");
    assertThat(response.getHeader("X-Hunt-Export-SHA256"))
        .isEqualTo(HuntWorkspace.hash(new String(bytes, StandardCharsets.UTF_8)));
    assertThat(response.getHeader("Content-Length")).isEqualTo(Integer.toString(bytes.length));
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getHeader("Content-Disposition")).contains("attachment;", id(item));
    for (var entry : value.path("reviews")) {
      assertThat(HuntWorkspace.hash(entry.path("snapshotJson").asText()))
          .isEqualTo(entry.path("exportSha256").asText());
    }
    assertThat(value.path("audit").toString()).contains("EXPORT_INVESTIGATION");
  }

  @Test
  void inaccessibleExportDoesNotWriteAuditAndSharedViewerGetsOnlyTheirCopy() throws Exception {
    var item = create(false);
    pin(item, 200);
    long initial = db.queryForObject("select count(*) from hunt_audit", Long.class);
    for (var actor : List.of(admin, viewer, other))
      read("/investigations/" + id(item) + "/export", actor, 403);
    assertThat(db.queryForObject("select count(*) from hunt_audit", Long.class)).isEqualTo(initial);
    var shared = create(true);
    pin(shared, 200);
    var exported = read("/investigations/" + id(shared) + "/export", viewer, 200);
    assertThat(exported.toString())
        .doesNotContain("RAW_FIXTURE_ONLY", "fixture-secret", "run --token");
    assertThat(exported.path("manifest").path("complete").asBoolean()).isTrue();
    assertThat(exported.path("evidence").get(0).path("redacted").asBoolean()).isTrue();
  }

  @Test
  void oversizedExportFailsBeforeSuccessfulResponseAndRollsBackExportAudit() throws Exception {
    var row = event();
    row.put("message", "x".repeat(600_000));
    when(storage.read(any(), anyString())).thenReturn(List.of(row));
    var item = create(true);
    pin(item, 200);
    long before =
        db.queryForObject(
            "select count(*) from hunt_audit where action='EXPORT_INVESTIGATION'", Long.class);
    read("/investigations/" + id(item) + "/export", admin, 409);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='EXPORT_INVESTIGATION'", Long.class))
        .isEqualTo(before);
  }

  @Test
  void corruptEvidenceIsRejectedOnReadAndBeforeExport() throws Exception {
    var item = create(true);
    pin(item, 200);
    db.update("update hunt_evidence set snapshot_json='{}' where investigation_id=?", id(item));
    read("/investigations/" + id(item) + "/evidence", owner, 409);
    read("/investigations/" + id(item) + "/export", admin, 409);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='EXPORT_INVESTIGATION'", Long.class))
        .isZero();
  }

  @Test
  void cleanupUsesExactExpiryBoundaryAndPreservesReviewReferencesAndUnexpiredCopies()
      throws Exception {
    var item = create(true);
    pin(item, 200);
    String evidenceId =
        db.queryForObject(
            "select id from hunt_evidence where investigation_id=?", String.class, id(item));
    var snapshot =
        json.writeValueAsString(
            Map.of("evidence", List.of(Map.of("id", evidenceId, "sha256", "original-reference"))));
    reviewFixture(id(item), 10, snapshot);
    db.update("update hunt_evidence set expires_at=? where id=?", Timestamp.from(NOW), evidenceId);
    for (int offset : List.of(-1, 1))
      db.update(
          "insert into hunt_evidence(id,investigation_id,event_id,query_job_id,snapshot_json,sha256,created_by,created_at,expires_at) values(?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id(item),
          (offset < 0 ? "a" : "b").repeat(64),
          JOB,
          "{}",
          HuntWorkspace.hash("{}"),
          owner.user().id(),
          Timestamp.from(NOW.minusSeconds(60)),
          Timestamp.from(NOW.plusMillis(offset)));
    db.update(
        "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,'EXACT_BOUNDARY',?,'{}',?)",
        owner.user().id(),
        id(item),
        Timestamp.from(NOW.minusSeconds(365 * 86400L)));
    db.update(
        "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,'FUTURE_BOUNDARY',?,'{}',?)",
        owner.user().id(),
        id(item),
        Timestamp.from(NOW.minusSeconds(365 * 86400L).plusMillis(1)));
    assertThat(evidence(item, admin).size()).isEqualTo(1);
    retention.expire();
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_evidence where investigation_id=?",
                Long.class,
                id(item)))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='EXACT_BOUNDARY'", Long.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_audit where action='FUTURE_BOUNDARY'", Long.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select snapshot_json from hunt_case_reviews where investigation_id=?",
                String.class,
                id(item)))
        .isEqualTo(snapshot);
    assertThat(read("/investigations/" + id(item) + "/export", admin, 200).path("reviews").size())
        .isEqualTo(1);
  }

  @Test
  void expiredEvidenceIsRepinnedAsNewVerifiedEvidenceWithoutReusingExpiredIdentity()
      throws Exception {
    var item = create(true);
    var first = pin(item, 200);
    db.update(
        "update hunt_evidence set expires_at=? where investigation_id=?",
        Timestamp.from(NOW.minusSeconds(1)),
        id(item));
    var next = pin(item, 200);
    assertThat(next.path("id").asText()).isNotEqualTo(first.path("id").asText());
    assertThat(next.path("alreadyPinned").asBoolean()).isFalse();
    assertThat(next.path("caseVersion").asLong()).isEqualTo(3);
    assertThat(evidence(item, owner).size()).isEqualTo(1);
    when(storage.read(any(), anyString())).thenReturn(List.of());
    pin(item, 400);
  }
}
