package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.manager.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;

class HuntExceptionStateTest {
  private EmbeddedDatabase database;
  private JdbcTemplate db;
  private HuntExceptions service;
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final HuntScenarios catalogue = new HuntScenarios();
  private final Instant start = Instant.parse("2020-01-02T00:00:00Z");
  private final TestClock clock = new TestClock(start);
  private final AuthUser analyst = new AuthUser(1, "analyst", "Analyst", Role.OPERATOR),
      admin = new AuthUser(2, "admin", "Admin", Role.ADMIN),
      secondAdmin = new AuthUser(3, "other-admin", "Admin", Role.ADMIN),
      outsider = new AuthUser(4, "other", "Other", Role.OPERATOR),
      viewer = new AuthUser(5, "viewer", "Viewer", Role.VIEWER);
  private HuntQuery query;
  private HuntJobs.Job source;
  private Map<String, Object> candidate;

  static class TestClock extends Clock {
    private volatile Instant value;

    TestClock(Instant at) {
      value = at;
    }

    void set(Instant at) {
      value = at;
    }

    public Instant instant() {
      return value;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(value, zone);
    }
  }

  @BeforeEach
  void setup() {
    clock.set(start);
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("exception-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
            .addScript("classpath:db/migration/V10__Hunt_approved_exceptions.sql")
            .build();
    db = new JdbcTemplate(database);
    db.execute(
        "create table hunt_audit(id bigint primary key auto_increment,actor_id bigint,action varchar(60),resource_id varchar(100),detail_json longtext,created_at timestamp(3))");
    db.execute("create table users(id bigint primary key,enabled boolean not null)");
    for (long id = 1; id <= 5; id++) db.update("insert into users(id,enabled) values(?,true)", id);
    service =
        new HuntExceptions(db, json, catalogue, clock, new DataSourceTransactionManager(database));
    query =
        new HuntQuery(
            start.minusSeconds(600),
            start,
            new HuntQuery.Filter("eq", "source_id", List.of("sensor-a"), null),
            "",
            "actor",
            "count",
            200,
            null);
    candidate =
        new LinkedHashMap<>(
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
                "hits",
                5,
                "first_seen",
                start.minusSeconds(120).toString(),
                "last_seen",
                start.minusSeconds(60).toString()));
    source =
        new HuntJobs.Job(
            UUID.randomUUID().toString(),
            "COMPLETED",
            start,
            start,
            null,
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
                    List.of(candidate))));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  private HuntExceptions.Request request() {
    return new HuntExceptions.Request(
        source.id(),
        0,
        "Approved work",
        "Reviewed change QA-1",
        start.minusSeconds(300),
        start.plusSeconds(3600));
  }

  private Map<String, Object> create(AuthUser user) {
    return service.request(request(), source, query, Map.of("sensor-a", "qa-host"), user);
  }

  private Map<String, Object> decide(Map<String, Object> item, AuthUser user, String action) {
    return service.decide(
        (String) item.get("id"),
        new HuntExceptions.Decision(
            ((Number) item.get("version")).longValue(), action, "Reviewed decision QA-1"),
        user);
  }

  private List<HuntExceptionPolicy.Grant> capture(Map<String, Object> item) {
    return service.capture(
        "A01", catalogue.require("A01").version(), (String) item.get("scopeKey"), clock.instant());
  }

  @Test
  void requestsDoNotSuppressUntilApprovedAndRevocationOnlyChangesNewSnapshots() throws Exception {
    var pending = create(analyst);
    assertThat(capture(pending)).isEmpty();
    var approved = decide(pending, admin, "APPROVE");
    var captured = capture(approved);
    assertThat(captured).hasSize(1);
    assertThat(
            HuntExceptionPolicy.apply(
                    catalogue.require("A01"),
                    (String) approved.get("scopeKey"),
                    start,
                    captured,
                    List.of(candidate))
                .findings())
        .isEmpty();
    assertThat(HuntWorkspace.hash((String) approved.get("evidenceJson")))
        .isEqualTo(approved.get("evidenceSha256"));
    var revoked = decide(approved, analyst, "REVOKE");
    assertThat(capture(revoked)).isEmpty();
    assertThat(
            HuntExceptionPolicy.apply(
                    catalogue.require("A01"),
                    (String) approved.get("scopeKey"),
                    start,
                    captured,
                    List.of(candidate))
                .suppressed())
        .hasSize(1);
    assertThat(revoked.get("evidenceJson")).isEqualTo(pending.get("evidenceJson"));
    assertThat(db.queryForObject("select count(*) from hunt_exception_history", Integer.class))
        .isEqualTo(3);
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isEqualTo(3);
  }

  @Test
  void expiryIsExclusiveAndAnExpiredPendingRequestCannotBeApproved() {
    var pending = create(analyst);
    var approved = decide(pending, admin, "APPROVE");
    clock.set(start.plusSeconds(3600).minusMillis(1));
    assertThat(capture(approved)).hasSize(1);
    clock.set(start.plusSeconds(3600));
    assertThat(capture(approved)).isEmpty();
    assertThat(service.detail((String) approved.get("id"), viewer))
        .containsEntry("effectiveState", "EXPIRED");
    clock.set(start);
    var another = create(analyst);
    clock.set(start.plusSeconds(3600));
    assertThatThrownBy(() -> decide(another, admin, "APPROVE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("만료");
  }

  @Test
  void futureApprovalStartsAtTheExactBoundaryAndDoesNotHideTheOldExample() {
    var planned =
        new HuntExceptions.Request(
            source.id(),
            0,
            "Upcoming maintenance",
            "Example documents the exact entity",
            start.plusSeconds(60),
            start.plusSeconds(3600));
    var pending = service.request(planned, source, query, Map.of("sensor-a", "qa-host"), analyst);
    var approved = decide(pending, admin, "APPROVE");
    assertThat(approved).containsEntry("effectiveState", "SCHEDULED");
    assertThat(capture(approved)).isEmpty();
    clock.set(start.plusSeconds(60));
    var captured = capture(approved);
    assertThat(captured).hasSize(1);
    assertThat(
            HuntExceptionPolicy.apply(
                    catalogue.require("A01"),
                    (String) approved.get("scopeKey"),
                    clock.instant(),
                    captured,
                    List.of(candidate))
                .findings())
        .containsExactly(candidate);
  }

  @Test
  void changedAliasOrSourceScopeHasNoApplicableGrant() {
    var approved = decide(create(analyst), admin, "APPROVE");
    String changed =
        HuntExceptionPolicy.scopeKey(
            json,
            "A01",
            catalogue.require("A01").version(),
            query,
            Map.of("sensor-a", "another-host"));
    assertThat(service.capture("A01", catalogue.require("A01").version(), changed, start))
        .isEmpty();
    assertThat(capture(approved)).hasSize(1);
  }

  @Test
  void requesterCannotApproveEvenWhenTheyAreAnAdministrator() {
    var pending = create(admin);
    assertThatThrownBy(() -> decide(pending, admin, "APPROVE"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(decide(pending, secondAdmin, "APPROVE")).containsEntry("state", "APPROVED");
  }

  @Test
  void aSuppressedCandidateCanRequestANewIndependentlyApprovedPeriod() {
    var approved = decide(create(analyst), admin, "APPROVE");
    var renewedSource =
        new HuntJobs.Job(
            UUID.randomUUID().toString(),
            "COMPLETED",
            start,
            start,
            null,
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
                    List.of(),
                    "suppressed",
                    List.of(Map.of("finding", candidate, "approvals", capture(approved))))));
    var renewal =
        new HuntExceptions.Request(
            renewedSource.id(),
            0,
            "Next maintenance window",
            "New change request",
            start.plusSeconds(3600),
            start.plusSeconds(7200),
            "SUPPRESSED");
    var pending =
        service.request(renewal, renewedSource, query, Map.of("sensor-a", "qa-host"), analyst);
    assertThat(pending.get("scopeKey")).isEqualTo(approved.get("scopeKey"));
    assertThat(pending.get("id")).isNotEqualTo(approved.get("id"));
    assertThat(pending.get("state")).isEqualTo("REQUESTED");
    assertThat(capture(approved)).hasSize(1);
    var next = decide(pending, admin, "APPROVE");
    clock.set(start.plusSeconds(3600));
    var current = capture(next);
    assertThat(current).hasSize(1);
    assertThat(current.getFirst().id()).isEqualTo(next.get("id"));
    var later = new LinkedHashMap<>(candidate);
    later.put("first_seen", clock.instant().toString());
    later.put("last_seen", clock.instant().plusSeconds(30).toString());
    assertThat(
            HuntExceptionPolicy.apply(
                    catalogue.require("A01"),
                    (String) next.get("scopeKey"),
                    clock.instant().plusSeconds(30),
                    current,
                    List.of(later))
                .suppressed())
        .hasSize(1);
  }

  @Test
  void appliedAuditHistoryIsReadableOnlyWithTheRequestAndHasStablePagination() {
    var pending = create(analyst);
    assertThatThrownBy(() -> service.usage((String) pending.get("id"), null, viewer))
        .isInstanceOf(ForbiddenException.class);
    var approved = decide(pending, admin, "APPROVE");
    for (int i = 0; i < 21; i++)
      db.update(
          "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(1,'HUNT_EXCEPTION_APPLIED',?,'{}',?)",
          approved.get("id"),
          java.sql.Timestamp.from(start));
    var first = service.usage((String) approved.get("id"), null, viewer);
    assertThat((List<?>) first.get("items")).hasSize(20);
    assertThat(first.get("hasMore")).isEqualTo(true);
    var second =
        service.usage(
            (String) approved.get("id"), ((Number) first.get("nextCursor")).longValue(), viewer);
    assertThat((List<?>) second.get("items")).hasSize(1);
    assertThat(second.get("hasMore")).isEqualTo(false);
  }

  @Test
  void rejectedAndCancelledUnapprovedRequestsStayPrivate() {
    var pending = create(analyst);
    assertThatThrownBy(() -> service.detail((String) pending.get("id"), outsider))
        .isInstanceOf(ForbiddenException.class);
    var rejected = decide(pending, admin, "REJECT");
    assertThatThrownBy(() -> service.detail((String) rejected.get("id"), viewer))
        .isInstanceOf(ForbiddenException.class);
    var cancelled = decide(create(analyst), analyst, "REVOKE");
    assertThatThrownBy(() -> service.detail((String) cancelled.get("id"), outsider))
        .isInstanceOf(ForbiddenException.class);
    assertThat(service.list(null, viewer).items()).isEmpty();
    assertThatThrownBy(() -> decide(create(analyst), viewer, "APPROVE"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void corruptedEvidenceFailsBeforeApprovalOrCapture() {
    var pending = create(analyst);
    String id = (String) pending.get("id");
    db.update(
        "update hunt_exceptions set payload_json=replace(payload_json,'QA-1','TAMPERED') where id=?",
        id);
    assertThatThrownBy(() -> decide(pending, admin, "APPROVE"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("해시");
    assertThat(db.queryForObject("select state from hunt_exceptions where id=?", String.class, id))
        .isEqualTo("REQUESTED");
    assertThat(db.queryForObject("select count(*) from hunt_exception_history", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void simultaneousDecisionsHaveOneWinnerAndOneRecordedTransition() {
    var pending = create(analyst);
    var results = new CopyOnWriteArrayList<String>();
    var tasks =
        List.of(admin, secondAdmin).stream()
            .map(
                user ->
                    CompletableFuture.runAsync(
                        () -> {
                          try {
                            decide(pending, user, "APPROVE");
                            results.add("APPROVED");
                          } catch (IllegalStateException conflict) {
                            results.add("CONFLICT");
                          }
                        }))
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(tasks).join();
    assertThat(results).containsExactlyInAnyOrder("APPROVED", "CONFLICT");
    assertThat(db.queryForObject("select count(*) from hunt_exception_history", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void stablePagingIncludesEverySameTimestampRequestWithoutDuplicates() {
    for (int i = 0; i < 50; i++) create(analyst);
    assertThatThrownBy(() -> create(analyst))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("50");
    create(outsider);
    var first = service.list(null, admin);
    assertThat(first.items()).hasSize(50);
    assertThat(first.hasMore()).isTrue();
    var second = service.list(first.nextCursor(), admin);
    assertThat(second.items()).hasSize(1);
    assertThat(second.hasMore()).isFalse();
    var ids = new HashSet<Object>();
    first.items().forEach(item -> ids.add(item.get("id")));
    second.items().forEach(item -> ids.add(item.get("id")));
    assertThat(ids).hasSize(51);
    assertThat(service.list(null, viewer).items()).isEmpty();
  }

  @Test
  void simultaneousRequestsCannotOverfillThePerRequesterBudget() {
    for (int i = 0; i < 49; i++) create(analyst);
    var results = new CopyOnWriteArrayList<String>();
    var tasks =
        java.util.stream.IntStream.range(0, 2)
            .mapToObj(
                index ->
                    CompletableFuture.runAsync(
                        () -> {
                          try {
                            create(analyst);
                            results.add("CREATED");
                          } catch (IllegalStateException full) {
                            results.add("FULL");
                          }
                        }))
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(tasks).join();
    assertThat(results).containsExactlyInAnyOrder("CREATED", "FULL");
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class))
        .isEqualTo(50);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "reversed",
        "too-long",
        "too-old",
        "nanoseconds",
        "expired",
        "blank-title",
        "blank-reason",
        "no-identity"
      })
  void invalidOrAmbiguousGrantsNeverCreateARequest(String variant) {
    var original = request();
    Instant from = original.validFrom(), to = original.expiresAt();
    String title = original.title(), reason = original.reason();
    switch (variant) {
      case "missing" -> to = null;
      case "reversed" -> to = from;
      case "too-long" -> to = start.plus(Duration.ofDays(31));
      case "too-old" -> from = start.minus(Duration.ofDays(31));
      case "nanoseconds" -> to = to.plusNanos(1);
      case "expired" -> to = start;
      case "blank-title" -> title = " ";
      case "blank-reason" -> reason = " ";
      case "no-identity" -> candidate.put("asset_uid", "");
    }
    var invalid = new HuntExceptions.Request(source.id(), 0, title, reason, from, to);
    assertThatThrownBy(() -> service.request(invalid, source, query, Map.of(), analyst))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.queryForObject("select count(*) from hunt_exceptions", Integer.class)).isZero();
  }
}
