package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:hunt-case-workflow-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HuntInvestigationWorkflowTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired LocalAuthProvider auth;
  @MockitoBean HuntJobs jobs;
  @MockitoBean HuntStorage storage;
  private static final String BASE = "/api/security/hunt/v2",
      EVENT = "e".repeat(64),
      JOB = "11111111-1111-4111-8111-111111111111";

  private record Actor(AuthUser user, Cookie cookie) {}

  private Actor owner, assignee, reviewer, other, admin, viewer;

  @BeforeEach
  void setup() {
    db.update("delete from hunt_response_requests");
    db.update("delete from hunt_evidence");
    db.update("delete from hunt_resources");
    db.update("delete from hunt_audit");
    owner = actor(Role.OPERATOR);
    assignee = actor(Role.OPERATOR);
    reviewer = actor(Role.OPERATOR);
    other = actor(Role.OPERATOR);
    admin = actor(Role.ADMIN);
    viewer = actor(Role.VIEWER);
    when(storage.database()).thenReturn("test");
    when(storage.read(any(), anyString()))
        .thenReturn(List.of(Map.of("id", EVENT, "event_json", "fixture raw", "actor", "qa")));
    Instant now = Instant.now();
    when(jobs.get(eq(JOB), any()))
        .thenReturn(new HuntJobs.Job(JOB, "COMPLETED", now, now, null, null));
    when(jobs.spec(eq(JOB), any()))
        .thenReturn(
            new HuntQuery(now.minusSeconds(60), now, null, "", "event_type", "count", 1, null));
    when(jobs.aliases(eq(JOB), any())).thenReturn(Map.of());
  }

  private Actor actor(Role role) {
    var user =
        auth.createLocalUser("case-" + UUID.randomUUID(), "fixture-password", role.name(), role);
    return new Actor(user, new Cookie(AuthController.SESSION_COOKIE, auth.createSession(user)));
  }

  private JsonNode read(String path, Actor actor, int status) throws Exception {
    return result(mvc.perform(get(BASE + path).cookie(actor.cookie())).andReturn(), status);
  }

  private JsonNode write(String path, Object body, Actor actor, int status) throws Exception {
    return send(post(BASE + path), body, actor, status);
  }

  private JsonNode send(MockHttpServletRequestBuilder request, Object body, Actor actor, int status)
      throws Exception {
    String token = read("/session", actor, 200).path("csrfToken").asText();
    return result(
        mvc.perform(
                request
                    .cookie(actor.cookie())
                    .header("X-Hunt-CSRF", token)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(body)))
            .andReturn(),
        status);
  }

  private JsonNode result(MvcResult result, int expected) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
    return json.readTree(result.getResponse().getContentAsString());
  }

  private Map<String, Object> body() {
    return new LinkedHashMap<>(
        Map.of(
            "status",
            "INVESTIGATING",
            "verdict",
            "INSUFFICIENT",
            "hypothesis",
            "검증 fixture",
            "notes",
            "initial notes"));
  }

  private JsonNode create(boolean shared) throws Exception {
    return write(
        "/resources/investigation",
        Map.of("title", "case", "shared", shared, "body", body(), "version", 0),
        owner,
        201);
  }

  private String id(JsonNode item) {
    return item.path("id").asText();
  }

  private JsonNode assign(JsonNode item, Actor who, Actor assigned, Actor peer, int status)
      throws Exception {
    var value = new LinkedHashMap<String, Object>();
    value.put("version", item.path("version").asLong());
    value.put("assigneeId", assigned == null ? null : assigned.user().id());
    value.put("reviewerId", peer == null ? null : peer.user().id());
    return write("/investigations/" + id(item) + "/assignment", value, who, status);
  }

  private JsonNode save(JsonNode item, Actor actor, String notes, int status) throws Exception {
    var next = body();
    next.put("notes", notes);
    return send(
        put(BASE + "/resources/investigation/" + id(item)),
        Map.of(
            "title",
            "case",
            "shared",
            item.path("shared").asBoolean(),
            "body",
            next,
            "version",
            item.path("version").asLong()),
        actor,
        status);
  }

  private JsonNode submit(JsonNode item, Actor actor, int status) throws Exception {
    return write(
        "/investigations/" + id(item) + "/review",
        Map.of("version", item.path("version").asLong(), "note", "검토 요청 근거"),
        actor,
        status);
  }

  private JsonNode workflow(JsonNode item, Actor actor) throws Exception {
    return read("/investigations/" + id(item) + "/workflow", actor, 200);
  }

  private JsonNode decide(JsonNode item, Actor actor, String decision, int status)
      throws Exception {
    long reviewId = workflow(item, actor).path("pendingReview").path("id").asLong();
    return write(
        "/investigations/" + id(item) + "/decision",
        Map.of(
            "version",
            item.path("version").asLong(),
            "reviewId",
            reviewId,
            "decision",
            decision,
            "note",
            "검토 결과 근거"),
        actor,
        status);
  }

  @Test
  void corruptPinnedBytesCannotBeApprovedAndRemainAvailableForAChangesRequest() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    write(
        "/investigations/" + id(item) + "/evidence",
        Map.of("jobId", JOB, "eventId", EVENT),
        assignee,
        200);
    item = read("/resources/investigation/" + id(item), assignee, 200);
    item = submit(item, assignee, 200);
    db.update("update hunt_evidence set snapshot_json='{}' where investigation_id=?", id(item));
    var state = workflow(item, reviewer);
    assertThat(state.path("evidenceChanged").asBoolean()).isTrue();
    assertThat(state.path("permissions").path("approve").asBoolean()).isFalse();
    decide(item, reviewer, "APPROVED", 409);
    decide(item, reviewer, "CHANGES_REQUESTED", 200);
  }

  @Test
  void administratorCanCommitPrivacyChangesWithoutKeepingPrivateReadAccess() throws Exception {
    var item = create(true);
    assertThat(
            read("/resources/investigation/" + id(item), admin, 200)
                .path("casePermissions")
                .path("manage")
                .asBoolean())
        .isTrue();
    var saved =
        send(
            put(BASE + "/resources/investigation/" + id(item)),
            Map.of(
                "title",
                "private case",
                "shared",
                false,
                "body",
                body(),
                "version",
                item.path("version").asLong()),
            admin,
            200);
    assertThat(saved.path("casePermissions").path("read").asBoolean(true)).isFalse();
    assertThat(saved.path("casePermissions").path("edit").asBoolean()).isFalse();
    assertThat(saved.path("casePermissions").path("manage").asBoolean()).isFalse();
    read("/resources/investigation/" + id(item), admin, 403);
    assertThat(read("/resources/investigation", admin, 200).toString()).doesNotContain(id(item));
    var retained = read("/resources/investigation/" + id(item), owner, 200);
    assertThat(retained.path("shared").asBoolean()).isFalse();
    assertThat(retained.path("ownerId").asLong()).isEqualTo(owner.user().id());
    assertThat(retained.path("version").asLong()).isEqualTo(2);
    assertThat(retained.path("casePermissions").path("read").asBoolean()).isTrue();
    assertThat(
            db.queryForObject(
                "select last_editor_id from hunt_case_workflow where investigation_id=?",
                Long.class,
                id(item)))
        .isEqualTo(admin.user().id());
    assertThat(read("/investigations/" + id(item) + "/audit", owner, 200).toString())
        .contains("SAVE_investigation", "caseVersion");
    read("/investigations/" + id(item) + "/audit", admin, 403);
  }

  @Test
  void administratorCanRemoveOwnPrivateMembershipAndGetsExplicitRevocation() throws Exception {
    var item = assign(create(false), owner, admin, reviewer, 200);
    var removed = assign(item, admin, null, reviewer, 200);
    assertThat(removed.path("casePermissions").path("read").asBoolean(true)).isFalse();
    assertThat(removed.path("casePermissions").path("manage").asBoolean()).isFalse();
    read("/resources/investigation/" + id(item), admin, 403);
    assertThat(workflow(removed, owner).path("assignee").isNull()).isTrue();
    read("/resources/investigation/" + id(item), reviewer, 200);
  }

  @Test
  void privateMembershipIsGrantedAndRevokedWithoutGrantingOrganizationWriteAccess()
      throws Exception {
    var item = create(false);
    read("/resources/investigation/" + id(item), other, 403);
    read("/resources/investigation/" + id(item), admin, 403);
    item = assign(item, owner, assignee, reviewer, 200);
    read("/resources/investigation/" + id(item), assignee, 200);
    read("/resources/investigation/" + id(item), reviewer, 200);
    assertThat(read("/resources/investigation", assignee, 200).toString()).contains(id(item));
    save(item, reviewer, "not allowed", 403);
    item = save(item, assignee, "assigned notes", 200);
    item = assign(item, owner, null, null, 200);
    read("/resources/investigation/" + id(item), assignee, 403);
    read("/investigations/" + id(item) + "/reviews", reviewer, 403);
    var shared = create(true);
    read("/resources/investigation/" + id(shared), viewer, 200);
    save(shared, other, "shared is read only", 403);
    assertThat(workflow(shared, viewer).path("permissions").path("edit").asBoolean()).isFalse();
  }

  @Test
  void assignmentRejectsNonAnalystsDisabledAccountsSamePersonAndStaleVersions() throws Exception {
    var item = create(true);
    assign(item, other, assignee, reviewer, 403);
    assign(item, owner, assignee, assignee, 400);
    assign(item, owner, viewer, reviewer, 400);
    db.update("update users set enabled=false where id=?", assignee.user().id());
    assign(item, owner, assignee, reviewer, 400);
    item = assign(item, owner, owner, reviewer, 200);
    var stale = item.deepCopy();
    item = assign(item, owner, null, reviewer, 200);
    assign(stale, owner, owner, reviewer, 409);
    assertThat(workflow(item, owner).path("assignee").isNull()).isTrue();
  }

  @Test
  void disabledOrDowngradedAssignedUsersLosePrivateCaseAccess() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    db.update("update users set enabled=false where id=?", assignee.user().id());
    read("/resources/investigation/" + id(item), assignee, 401);
    assertThat(workflow(item, owner).path("assignee").path("enabled").asBoolean()).isFalse();
    db.update("update users set role='VIEWER' where id=?", reviewer.user().id());
    read("/resources/investigation/" + id(item), reviewer, 403);
    assign(item, owner, null, null, 200);
  }

  @Test
  void directoryIsBoundedSearchableAndExcludesIneligibleAccounts() throws Exception {
    read("/investigations/analysts", viewer, 403);
    db.update("update users set enabled=false where id=?", other.user().id());
    var users = read("/investigations/analysts", owner, 200);
    assertThat(users.toString()).doesNotContain(other.user().username(), viewer.user().username());
    assertThat(
            read("/investigations/analysts?q=" + assignee.user().username(), owner, 200)
                .path("items")
                .size())
        .isEqualTo(1);
    for (int i = 0; i < 105; i++)
      db.update(
          "insert into users(username,password_hash,display_name,role,enabled) values(?,?,'directory fixture','OPERATOR',true)",
          "bounded-" + UUID.randomUUID(),
          "not-a-login-hash");
    users = read("/investigations/analysts?q=bounded-", owner, 200);
    assertThat(users.path("items").size()).isEqualTo(100);
    assertThat(users.path("hasMore").asBoolean()).isTrue();
  }

  @Test
  void genericResourceWritesCannotForgeWorkflowOrBypassCsrf() throws Exception {
    for (String state : List.of("REVIEW", "CLOSED")) {
      var value = body();
      value.put("status", state);
      write(
          "/resources/investigation",
          Map.of("title", "bypass", "shared", false, "body", value),
          owner,
          400);
    }
    var forged = body();
    forged.put("reviewerId", reviewer.user().id());
    write(
        "/resources/investigation",
        Map.of("title", "bypass", "shared", false, "body", forged),
        owner,
        400);
    var item = create(false);
    var input =
        Map.of(
            "version",
            item.path("version").asLong(),
            "assigneeId",
            assignee.user().id(),
            "reviewerId",
            reviewer.user().id());
    result(
        mvc.perform(
                post(BASE + "/investigations/" + id(item) + "/assignment")
                    .cookie(owner.cookie())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(input)))
            .andReturn(),
        403);
    result(
        mvc.perform(
                post(BASE + "/investigations/" + id(item) + "/assignment")
                    .cookie(owner.cookie())
                    .header(
                        "X-Hunt-CSRF", read("/session", assignee, 200).path("csrfToken").asText())
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(input)))
            .andReturn(),
        403);
  }

  @Test
  void simultaneousAnalystSavesHaveExactlyOneWinner() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> responses = new ArrayList<>();
      for (Actor actor : List.of(owner, assignee))
        responses.add(
            pool.submit(
                () -> {
                  String token = read("/session", actor, 200).path("csrfToken").asText();
                  start.await();
                  var input =
                      Map.of(
                          "title",
                          "case",
                          "shared",
                          false,
                          "body",
                          body(),
                          "version",
                          item.path("version").asLong());
                  return mvc.perform(
                          put(BASE + "/resources/investigation/" + id(item))
                              .cookie(actor.cookie())
                              .header("X-Hunt-CSRF", token)
                              .contentType("application/json")
                              .content(json.writeValueAsBytes(input)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      start.countDown();
      assertThat(
              List.of(
                  responses.get(0).get(10, TimeUnit.SECONDS),
                  responses.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void reviewFreezesContentAndEvidenceAndRequiresAnIndependentAssignedReviewer() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    item = save(item, assignee, "analysis", 200);
    item = submit(item, assignee, 200);
    assertThat(item.path("body").path("status").asText()).isEqualTo("REVIEW");
    save(item, owner, "cannot rewrite", 409);
    assign(item, owner, owner, reviewer, 409);
    write(
        "/investigations/" + id(item) + "/evidence",
        Map.of("jobId", JOB, "eventId", EVENT),
        assignee,
        409);
    verify(jobs, never()).get(anyString(), any());
    decide(item, owner, "APPROVED", 403);
    item = decide(item, reviewer, "APPROVED", 200);
    assertThat(item.path("body").path("status").asText()).isEqualTo("CLOSED");
    save(item, assignee, "closed", 409);
  }

  @Test
  void aPreviousAuthorCannotBecomeTheirOwnReviewerAndUnassignedCasesCannotSubmit()
      throws Exception {
    var item = create(false);
    submit(item, owner, 409);
    item = assign(item, owner, assignee, owner, 200);
    submit(item, assignee, 409);
    item = save(item, assignee, "independent author", 200);
    item = submit(item, assignee, 200);
    decide(item, owner, "APPROVED", 200);
  }

  @Test
  void changesRequestsReopenWithImmutableHistoryAndOldDecisionsCannotBeReused() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    item = submit(item, assignee, 200);
    long oldReview = workflow(item, reviewer).path("pendingReview").path("id").asLong();
    var original = read("/investigations/" + id(item) + "/reviews/" + oldReview, reviewer, 200);
    String encoded = json.writeValueAsString(original.path("snapshot"));
    assertThat(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(encoded.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(original.path("review").path("sha256").asText());
    item = decide(item, reviewer, "CHANGES_REQUESTED", 200);
    item = save(item, assignee, "revised analysis", 200);
    item = submit(item, assignee, 200);
    write(
        "/investigations/" + id(item) + "/decision",
        Map.of(
            "version",
            item.path("version").asLong(),
            "reviewId",
            oldReview,
            "decision",
            "APPROVED",
            "note",
            "old request"),
        reviewer,
        409);
    assertThat(
            read("/investigations/" + id(item) + "/reviews/" + oldReview, owner, 200)
                .path("snapshot"))
        .isEqualTo(original.path("snapshot"));
    item = decide(item, reviewer, "APPROVED", 200);
    write(
        "/investigations/" + id(item) + "/reopen",
        Map.of("version", item.path("version").asLong(), "note", ""),
        owner,
        400);
    item =
        write(
            "/investigations/" + id(item) + "/reopen",
            Map.of("version", item.path("version").asLong(), "note", "new evidence"),
            owner,
            200);
    assertThat(item.path("body").path("status").asText()).isEqualTo("INVESTIGATING");
    assertThat(workflow(item, owner).path("history").path("items").size()).isEqualTo(2);
  }

  @Test
  void confirmedReviewNeedsActiveEvidenceAndKeepsThePinnedSnapshotAfterApproval() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    var content = body();
    content.put("verdict", "CONFIRMED");
    item =
        send(
            put(BASE + "/resources/investigation/" + id(item)),
            Map.of(
                "title",
                "case",
                "shared",
                false,
                "version",
                item.path("version").asLong(),
                "body",
                content),
            assignee,
            200);
    submit(item, assignee, 409);
    write(
        "/investigations/" + id(item) + "/evidence",
        Map.of("jobId", JOB, "eventId", EVENT),
        assignee,
        200);
    item = read("/resources/investigation/" + id(item), assignee, 200);
    assertThat(item.path("casePermissions").path("edit").asBoolean()).isTrue();
    item = submit(item, assignee, 200);
    assertThat(item.path("casePermissions").path("edit").asBoolean()).isFalse();
    assertThat(workflow(item, reviewer).path("permissions").path("approve").asBoolean()).isTrue();
    item = decide(item, reviewer, "APPROVED", 200);
    assertThat(read("/investigations/" + id(item) + "/evidence", reviewer, 200).size())
        .isEqualTo(1);
    assertThat(
            read("/resources/investigation", assignee, 200)
                .get(0)
                .path("casePermissions")
                .path("edit")
                .asBoolean())
        .isFalse();
  }

  @Test
  void pinningAdvancesTheCaseVersionAndExpiredSubmittedEvidenceCannotBeApproved() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    long before = item.path("version").asLong();
    var pin =
        write(
            "/investigations/" + id(item) + "/evidence",
            Map.of("jobId", JOB, "eventId", EVENT),
            assignee,
            200);
    assertThat(pin.path("previousVersion").asLong()).isEqualTo(before);
    assertThat(pin.path("caseVersion").asLong()).isEqualTo(before + 1);
    item = read("/resources/investigation/" + id(item), assignee, 200);
    var duplicate =
        write(
            "/investigations/" + id(item) + "/evidence",
            Map.of("jobId", JOB, "eventId", EVENT),
            assignee,
            200);
    assertThat(duplicate.path("caseVersion").asLong()).isEqualTo(before + 1);
    item = submit(item, assignee, 200);
    db.update(
        "update hunt_evidence set expires_at=? where investigation_id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        id(item));
    assertThat(workflow(item, reviewer).path("evidenceChanged").asBoolean()).isTrue();
    decide(item, reviewer, "APPROVED", 409);
    decide(item, reviewer, "CHANGES_REQUESTED", 200);
  }

  @Test
  void withdrawalAndReviewPaginationPreserveCaseBoundaries() throws Exception {
    var item = assign(create(false), owner, assignee, reviewer, 200);
    item = submit(item, assignee, 200);
    item =
        write(
            "/investigations/" + id(item) + "/reopen",
            Map.of("version", item.path("version").asLong(), "note", "withdraw for investigation"),
            owner,
            200);
    assertThat(workflow(item, reviewer).path("history").path("items").get(0).path("state").asText())
        .isEqualTo("WITHDRAWN");
    for (int i = 0; i < 22; i++)
      db.update(
          "insert into hunt_case_reviews(investigation_id,case_version,submitted_by,reviewer_id,state,request_note,snapshot_json,sha256,created_at) values(?,?,?,?,'CHANGES_REQUESTED','pagination fixture','{}',?,?)",
          id(item),
          100 + i,
          owner.user().id(),
          reviewer.user().id(),
          "a".repeat(64),
          Timestamp.from(Instant.now()));
    var first = read("/investigations/" + id(item) + "/reviews", owner, 200);
    assertThat(first.path("items").size()).isEqualTo(20);
    long cursor = first.path("nextBefore").asLong();
    var second = read("/investigations/" + id(item) + "/reviews?before=" + cursor, reviewer, 200);
    assertThat(second.path("items").size()).isEqualTo(3);
    assertThat(second.path("items").get(0).path("id").asLong()).isLessThan(cursor);
    read("/investigations/" + id(item) + "/reviews/" + cursor, other, 403);
  }
}
