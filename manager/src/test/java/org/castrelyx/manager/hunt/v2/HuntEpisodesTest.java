package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.web.AlertController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

class HuntEpisodesTest {
  private EmbeddedDatabase source;
  private JdbcTemplate db;
  private TransactionTemplate tx;
  private HuntEpisodes episodes;
  private HuntFindingEvidence evidence;
  private HuntWorkspace.Resource rule;
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final HuntScenarios.Scenario definition = new HuntScenarios().require("A01");
  private final Instant at = Instant.parse("2020-01-02T00:00:00Z");
  private final AuthUser owner = new AuthUser(1, "owner", "Owner", Role.OPERATOR),
      viewer = new AuthUser(2, "viewer", "Viewer", Role.VIEWER),
      admin = new AuthUser(3, "admin", "Admin", Role.ADMIN);

  @BeforeEach
  void prepare() {
    source =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("episodes-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
            .build();
    Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    episodes = new HuntEpisodes(db, json, Clock.fixed(at.plusSeconds(10000), ZoneOffset.UTC));
    evidence = new HuntFindingEvidence(db, json);
    rule =
        new HuntWorkspace.Resource(
            UUID.randomUUID().toString(),
            "rule",
            1,
            true,
            "episode QA",
            Map.of("state", "ACTIVE", "scenarioId", "A01"),
            3,
            at,
            at);
    db.update(
        "insert into hunt_resources(id,kind,owner_id,shared,title,body_json,version,created_at,updated_at) values(?,'rule',1,true,?,'{}',3,?,?)",
        rule.id(),
        rule.title(),
        ts(at),
        ts(at));
    db.update(
        "insert into hunt_rule_runtime(rule_id,rule_version,last_state) values(?,3,'RUNNING')",
        rule.id());
  }

  @AfterEach
  void close() {
    source.shutdown();
  }

  private Map<String, Object> candidate(long first, long last) {
    var v = new LinkedHashMap<String, Object>();
    v.putAll(
        Map.of(
            "src_ip",
            "192.0.2.2",
            "dst_ip",
            "198.51.100.2",
            "asset_uid",
            "host",
            "realm",
            "domain",
            "actor",
            "alice",
            "first_seen",
            at.plusMillis(first).toString(),
            "last_seen",
            at.plusMillis(last).toString(),
            "hits",
            6));
    v.put("command_line", "tool password=QA_EPISODE_SECRET --public safe");
    return v;
  }

  private void publish(Map<String, Object> value, String job, boolean active) {
    tx.executeWithoutResult(
        s -> {
          db.queryForMap("select * from hunt_rule_runtime where rule_id=? for update", rule.id());
          db.queryForMap("select * from hunt_resources where id=? for update", rule.id());
          episodes.publish(rule, definition, value, job, active);
        });
  }

  private void run(long first, long last) {
    publish(candidate(first, last), UUID.randomUUID().toString(), true);
  }

  private long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }

  private String root() {
    return db.queryForObject(
        "select id from hunt_findings where merged_into is null", String.class);
  }

  private String encoded(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String legacy(String id, long first, long last) {
    var c = candidate(first, last);
    var group = new TreeMap<String, Object>();
    definition.keys().forEach(key -> group.put(key, c.get(key)));
    String payload = encoded(c);
    db.update(
        "insert into hunt_findings(id,rule_id,rule_version,group_key,scenario_id,first_seen_at,last_seen_at,last_job_id,payload_json) values(?,?,3,?,'A01',?,?,?,?)",
        id,
        rule.id(),
        HuntWorkspace.hash(encoded(group)),
        ts(at.plusMillis(first)),
        ts(at.plusMillis(last)),
        UUID.randomUUID().toString(),
        payload);
    return payload;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> items(Map<String, Object> value) {
    return (List<Map<String, Object>>) value.get("items");
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }

  @Test
  void bridgeKeepsOneAttentionItemAndThreeIndependentSourceProofs() {
    run(0, 10000);
    run(1000000, 1010000);
    run(300000, 700000);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where merged_into is null", Integer.class))
        .isEqualTo(1);
    assertThat(count("hunt_findings")).isEqualTo(2);
    assertThat(count("hunt_finding_merges")).isEqualTo(1);
    assertThat(count("hunt_finding_occurrences")).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where status='ACTIVE'", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where status='MERGED'", Integer.class))
        .isEqualTo(1);
    assertThat(items(evidence.occurrences(root(), null, owner))).hasSize(3);
    for (var proof : items(evidence.occurrences(root(), null, owner)))
      assertThat(HuntWorkspace.hash((String) proof.get("evidenceJson")))
          .isEqualTo(proof.get("evidenceSha256"));
  }

  @Test
  void connectedLegacyIntervalsExpandToAFixedPointAndKeepTheirOriginalPayloads() {
    String a = legacy("a", 0, 400000),
        b = legacy("b", 600000, 800000),
        c = legacy("c", 1000000, 1200000);
    publish(candidate(-100000, 0), "transitive-job", false);
    assertThat(root()).isEqualTo("a");
    assertThat(
            db.queryForObject(
                    "select last_seen_at from hunt_findings where id='a'", Timestamp.class)
                .toInstant())
        .isEqualTo(at.plusMillis(1200000));
    var pages = items(evidence.occurrences("a", null, owner));
    assertThat(pages).hasSize(4);
    assertThat(pages.stream().map(p -> p.get("evidenceJson"))).contains(a, b, c);
    assertThat(pages.stream().filter(p -> p.get("origin").equals("LEGACY_LAST_RESULT"))).hasSize(3);
    assertThat(count("alert_instances")).isZero();
  }

  @ParameterizedTest
  @ValueSource(longs = {300000, 300001})
  void episodeGapUsesAnInclusiveMillisecondBoundary(long gap) {
    run(0, 10000);
    run(10000 + gap, 11000 + gap);
    long expected = gap == 300000 ? 1 : 2;
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where merged_into is null", Long.class))
        .isEqualTo(expected);
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where status='ACTIVE'", Long.class))
        .isEqualTo(expected);
  }

  @Test
  void groupAndRuleVersionBoundariesNeverMerge() {
    run(0, 10000);
    var other = candidate(0, 10000);
    other.put("actor", "bob");
    publish(other, "other-group", true);
    rule =
        new HuntWorkspace.Resource(
            rule.id(), "rule", 1, true, rule.title(), rule.body(), 4, at, at);
    run(0, 10000);
    assertThat(count("hunt_findings")).isEqualTo(3);
  }

  @Test
  void replayedCompletionCannotReplaceItsOriginalProofOrEmitAnotherAlert() {
    var first = candidate(0, 10000);
    publish(first, "same-job", true);
    var changed = new LinkedHashMap<>(first);
    changed.put("_asOf", "changed metadata");
    publish(changed, "same-job", true);
    assertThat(count("hunt_finding_occurrences")).isEqualTo(1);
    assertThat(count("alert_instances")).isEqualTo(1);
    assertThat(items(evidence.occurrences(root(), null, owner)).getFirst().get("evidenceJson"))
        .isEqualTo(encoded(first));
  }

  @Test
  void observationDoesNotAlertAndPromotionCanAttachOneAlertToAnExistingEpisode() {
    publish(candidate(0, 10000), "observe-job", false);
    assertThat(count("alert_instances")).isZero();
    publish(candidate(0, 10000), "active-job", true);
    assertThat(count("hunt_findings")).isEqualTo(1);
    assertThat(count("alert_instances")).isEqualTo(1);
  }

  @Test
  void missingLinkedAlertRollsBackCandidateAndOccurrenceChanges() {
    run(0, 10000);
    String id = root(),
        before =
            db.queryForObject(
                "select payload_json from hunt_findings where id=?", String.class, id);
    db.update("update hunt_findings set alert_id=987654 where id=?", id);
    assertThatThrownBy(() -> run(10000, 20000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("알림");
    assertThat(count("hunt_finding_occurrences")).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select payload_json from hunt_findings where id=?", String.class, id))
        .isEqualTo(before);
  }

  @Test
  void mergeAuditFailureRollsBackAliasesOriginalProofsAndBothAlerts() {
    run(0, 10000);
    run(1000000, 1010000);
    db.execute(
        "alter table hunt_audit add constraint reject_episode_merge check(action<>'HUNT_EPISODES_MERGED')");
    assertThatThrownBy(() -> run(300000, 700000)).isInstanceOf(RuntimeException.class);
    assertThat(count("hunt_finding_merges")).isZero();
    assertThat(count("hunt_finding_occurrences")).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where merged_into is null", Integer.class))
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where status='ACTIVE'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void opaqueOriginalMergePayloadIsParsedBeforeRoleMasking() {
    run(0, 10000);
    run(1000000, 1010000);
    run(300000, 700000);
    var raw = evidence.merges(root(), null, admin);
    assertThat(encoded(raw)).contains("QA_EPISODE_SECRET");
    for (var actor : List.of(owner, viewer)) {
      var copy = (Map<?, ?>) HuntRedaction.apply(raw, actor);
      assertThat(encoded(copy)).doesNotContain("QA_EPISODE_SECRET");
      for (var item : (List<?>) copy.get("items")) {
        var proof = (Map<?, ?>) item;
        assertThat(HuntWorkspace.hash((String) proof.get("evidenceJson")))
            .isEqualTo(proof.get("exportEvidenceSha256"));
      }
    }
  }

  @Test
  void privateRuleVisibilityIsRecheckedOnEveryDetailAndPageIncludingAdmin() {
    run(0, 10000);
    String id = root();
    db.update("update hunt_resources set shared=false where id=?", rule.id());
    assertThat(evidence.detail(id, owner)).isNotEmpty();
    for (var actor : List.of(viewer, admin)) {
      assertThatThrownBy(() -> evidence.detail(id, actor)).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> evidence.occurrences(id, null, actor))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> evidence.merges(id, null, actor))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Test
  void immutableOccurrenceAndMergeCorruptionAreRefused() {
    run(0, 10000);
    run(1000000, 1010000);
    run(300000, 700000);
    String id = root();
    db.update("update hunt_finding_occurrences set snapshot_sha256=?", "0".repeat(64));
    assertThatThrownBy(() -> evidence.occurrences(id, null, owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("해시");
    db.update("update hunt_finding_merges set source_sha256=?", "0".repeat(64));
    assertThatThrownBy(() -> evidence.merges(id, null, owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("해시");
  }

  @Test
  void occurrenceAndMergeHistoryBothPageBeyondTwentyWithoutLosingRows() {
    for (int i = 0; i < 22; i++) legacy(String.format("legacy-%02d", i), i * 1000, i * 1000 + 100);
    publish(candidate(0, 22000), "many-legacy", false);
    String id = root();
    var page = evidence.merges(id, null, owner);
    assertThat(items(page)).hasSize(20);
    assertThat(page.get("hasMore")).isEqualTo(true);
    var rest = evidence.merges(id, ((Number) page.get("nextCursor")).longValue(), owner);
    assertThat(items(rest)).hasSize(1);
    assertThat(rest.get("hasMore")).isEqualTo(false);
    var occurrences = evidence.occurrences(id, null, owner);
    assertThat(items(occurrences)).hasSize(20);
    assertThat(
            items(
                evidence.occurrences(
                    id, ((Number) occurrences.get("nextCursor")).longValue(), owner)))
        .hasSize(3);
  }

  @Test
  void boundedConnectedSetFailureLeavesEveryLegacyEpisodeUntouched() {
    for (int i = 0; i < 501; i++) legacy("limit-" + i, 0, 10000);
    assertThatThrownBy(() -> publish(candidate(0, 10000), "over-limit", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500");
    assertThat(count("hunt_finding_occurrences")).isZero();
    assertThat(count("hunt_finding_merges")).isZero();
    assertThat(count("alert_instances")).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where merged_into is null", Integer.class))
        .isEqualTo(501);
  }

  @Test
  void mergedAlertsCannotBeAcknowledgedOrReopenedThroughTheExistingAlertApi() {
    run(0, 10000);
    run(1000000, 1010000);
    run(300000, 700000);
    Long merged =
        db.queryForObject("select id from alert_instances where status='MERGED'", Long.class);
    var controller = new AlertController(db);
    assertThatThrownBy(() -> controller.acknowledge(merged))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> controller.resolve(merged)).isInstanceOf(IllegalStateException.class);
    assertThat(controller.alerts().stream().filter(a -> a.status().name().equals("MERGED")))
        .hasSize(1);
  }

  @Test
  void publicationOutsideTheCompletionTransactionIsRefused() {
    assertThatThrownBy(
            () -> episodes.publish(rule, definition, candidate(0, 10000), "unsafe", true))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("hunt_findings")).isZero();
  }

  @Test
  void concurrentCompletionOfTheSameJobIsIdempotentUnderTheRuntimeLock() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks =
          List.<Callable<Void>>of(
              () -> {
                publish(candidate(0, 10000), "concurrent", true);
                return null;
              },
              () -> {
                publish(candidate(0, 10000), "concurrent", true);
                return null;
              });
      for (var done : pool.invokeAll(tasks)) done.get();
    }
    assertThat(count("hunt_finding_occurrences")).isEqualTo(1);
    assertThat(count("hunt_findings")).isEqualTo(1);
    assertThat(count("alert_instances")).isEqualTo(1);
  }

  @Test
  void malformedOrSubMillisecondIntervalsAreRejectedWithoutPublishing() {
    var reversed = candidate(10000, 0);
    assertThatThrownBy(() -> publish(reversed, "reverse", true))
        .isInstanceOf(IllegalArgumentException.class);
    var nano = candidate(0, 10000);
    nano.put("first_seen", at.plusNanos(1).toString());
    assertThatThrownBy(() -> publish(nano, "nano", true))
        .isInstanceOf(IllegalArgumentException.class);
    var missing = candidate(0, 10000);
    missing.remove("actor");
    assertThatThrownBy(() -> publish(missing, "missing", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("hunt_findings")).isZero();
  }
}
