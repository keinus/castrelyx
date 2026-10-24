package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Rule fixtures must not be executed by workers from another cached test context.
@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:hunt-rules-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class HuntRulesTest {
  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired LocalAuthProvider auth;
  @Autowired HuntWorkspace workspace;
  @Autowired HuntRules rules;
  @MockitoBean HuntJobs jobs;
  @MockitoBean HuntStorage storage;
  private AuthUser user;

  @BeforeEach
  void setup() {
    db.update("delete from hunt_response_requests");
    db.update("delete from hunt_findings");
    db.update("delete from hunt_rule_runtime");
    db.update("delete from hunt_lookup_names");
    db.update("delete from hunt_evidence");
    db.update("delete from hunt_resources");
    db.update(
        "delete from alert_instances where rule_id in (select id from alert_rules where rule_type='hunt')");
    db.update("delete from alert_rules where rule_type='hunt'");
    user =
        auth.createLocalUser(
            "rules-" + UUID.randomUUID(), "test-password", "Rules admin", Role.ADMIN);
    when(storage.database()).thenReturn("test");
  }

  @Test
  void sourcePagesUseRealSessionsCurrentPrivacyAndSeparateRoleCopyHashes() throws Exception {
    Instant now = Instant.now();
    var query = new HuntQuery(now.minusSeconds(3600), now, null, "", "actor", "count", 200, null);
    var rule =
        workspace.save(
            "rule",
            null,
            new HuntWorkspace.Save(
                "API lineage",
                true,
                Map.of("scenarioId", "A01", "state", "DRAFT", "query", query),
                0),
            user);
    rule = rules.transition(rule.id(), "OBSERVE", rule.version(), user);
    finishRun(rule, now.minusSeconds(1800), now.minusSeconds(1790));
    finishRun(rule, now.minusSeconds(800), now.minusSeconds(790));
    finishRun(rule, now.minusSeconds(1500), now.minusSeconds(1100));
    String root =
        db.queryForObject(
            "select id from hunt_findings where rule_id=? and merged_into is null",
            String.class,
            rule.id());
    String alias =
        db.queryForObject(
            "select id from hunt_findings where rule_id=? and merged_into is not null",
            String.class,
            rule.id());
    String base = "/api/security/hunt/v2/findings/";
    mvc.perform(get(base + root)).andExpect(status().isUnauthorized());
    for (Role role : Role.values()) {
      AuthUser actor =
          role == Role.ADMIN
              ? user
              : auth.createLocalUser(
                  "proof-" + UUID.randomUUID(), "test-only-password", "Reader", role);
      var cookie =
          new jakarta.servlet.http.Cookie(
              org.castrelyx.manager.web.AuthController.SESSION_COOKIE, auth.createSession(actor));
      var detail =
          json.readTree(
              mvc.perform(get(base + root).cookie(cookie))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(detail.path("occurrences").path("items").size()).isEqualTo(3);
      var proof = detail.path("merges").path("items").get(0);
      String bytes = proof.path("evidenceJson").asText();
      assertThat(HuntWorkspace.hash(bytes))
          .isEqualTo(
              proof.path(role == Role.ADMIN ? "evidenceSha256" : "exportEvidenceSha256").asText());
      if (role == Role.ADMIN) assertThat(bytes).contains("QA_EPISODE_API_SECRET");
      else assertThat(bytes).doesNotContain("QA_EPISODE_API_SECRET");
      var aliasDetail =
          json.readTree(
              mvc.perform(get(base + alias).cookie(cookie))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(aliasDetail.path("merged_into").asText()).isEqualTo(root);
      mvc.perform(get(base + root + "/occurrences?before=0").cookie(cookie))
          .andExpect(status().isBadRequest());
      for (String page : List.of("occurrences", "merges"))
        mvc.perform(get(base + root + "/" + page + "?before=9223372036854775807").cookie(cookie))
            .andExpect(status().isOk());
    }
    db.update("update hunt_resources set shared=false where id=?", rule.id());
    for (Role role : List.of(Role.ADMIN, Role.VIEWER)) {
      var other =
          auth.createLocalUser(
              "private-proof-" + UUID.randomUUID(), "test-only-password", "Other", role);
      var cookie =
          new jakarta.servlet.http.Cookie(
              org.castrelyx.manager.web.AuthController.SESSION_COOKIE, auth.createSession(other));
      for (String path : List.of("", "/occurrences", "/merges"))
        mvc.perform(get(base + root + path).cookie(cookie)).andExpect(status().isForbidden());
    }
  }

  @Test
  void aBridgeCandidateMergesEveryConnectedEpisodeAndLeavesOneAlert() {
    Instant now = Instant.now();
    var query = new HuntQuery(now.minusSeconds(3600), now, null, "", "actor", "count", 200, null);
    var rule =
        workspace.save(
            "rule",
            null,
            new HuntWorkspace.Save(
                "bridge regression",
                true,
                Map.of("scenarioId", "A01", "state", "DRAFT", "query", query),
                0),
            user);
    rule = rules.transition(rule.id(), "OBSERVE", rule.version(), user);
    db.update(
        "update hunt_rule_runtime set observed_since=?,ready_runs=10 where rule_id=?",
        Timestamp.from(now.minusSeconds(8 * 86400L)),
        rule.id());
    rule = rules.transition(rule.id(), "ACTIVE", rule.version(), user);
    finishRun(rule, now.minusSeconds(1800), now.minusSeconds(1790));
    finishRun(rule, now.minusSeconds(800), now.minusSeconds(790));
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where rule_id=? and status='OPEN'",
                Integer.class,
                rule.id()))
        .isEqualTo(2);
    finishRun(rule, now.minusSeconds(1500), now.minusSeconds(1100));
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where rule_id=? and status='OPEN'",
                Integer.class,
                rule.id()))
        .isEqualTo(1);
    Long alertRule =
        db.queryForObject(
            "select alert_rule_id from hunt_rule_runtime where rule_id=?", Long.class, rule.id());
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where rule_id=? and status='ACTIVE'",
                Integer.class,
                alertRule))
        .isEqualTo(1);
    String ruleId = rule.id();
    assertThat(rules.findings(user).stream().filter(item -> ruleId.equals(item.get("ruleId"))))
        .hasSize(1);
  }

  @Test
  void observingNeverAlertsAndActiveRunsMergeOverlappingFindings() {
    Instant now = Instant.now();
    var query = new HuntQuery(now.minusSeconds(600), now, null, "", "actor", "count", 200, null);
    var rule =
        workspace.save(
            "rule",
            null,
            new HuntWorkspace.Save(
                "guarded", true, Map.of("scenarioId", "A01", "state", "DRAFT", "query", query), 0),
            user);
    rule = rules.transition(rule.id(), "OBSERVE", rule.version(), user);
    finishRun(rule, now.minusSeconds(60));
    assertThat(
            db.queryForObject(
                "select count(*) from hunt_findings where rule_id=?", Integer.class, rule.id()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where title='단일 계정 무차별 인증'", Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select ready_runs from hunt_rule_runtime where rule_id=?",
                Integer.class,
                rule.id()))
        .isEqualTo(1);
    db.update(
        "update hunt_rule_runtime set observed_since=?,ready_runs=10 where rule_id=?",
        Timestamp.from(now.minusSeconds(8 * 86400L)),
        rule.id());
    rule = rules.transition(rule.id(), "ACTIVE", rule.version(), user);
    finishRun(rule, now.minusSeconds(30));
    finishRun(rule, now.minusSeconds(20));
    Long alertRule =
        db.queryForObject(
            "select alert_rule_id from hunt_rule_runtime where rule_id=?", Long.class, rule.id());
    assertThat(alertRule).isNotNull();
    assertThat(
            db.queryForObject(
                "select count(*) from alert_instances where rule_id=?", Integer.class, alertRule))
        .isEqualTo(1);
    rules.transition(rule.id(), "PAUSED", rule.version(), user);
    int calls = mockingDetails(jobs).getInvocations().size();
    rules.tick();
    assertThat(mockingDetails(jobs).getInvocations()).hasSize(calls);
  }

  @Test
  void changedScenarioCannotInheritTheOldObservationAndActivation() throws Exception {
    Instant now = Instant.now();
    var query = new HuntQuery(now.minusSeconds(600), now, null, "", "actor", "count", 200, null);
    var rule =
        workspace.save(
            "rule",
            null,
            new HuntWorkspace.Save(
                "version guard",
                true,
                Map.of(
                    "scenarioId",
                    "A01",
                    "state",
                    "DRAFT",
                    "scenarioVersion",
                    "2.0.0",
                    "query",
                    query),
                0),
            user);
    rule = rules.transition(rule.id(), "OBSERVE", rule.version(), user);
    assertThat(rule.body())
        .containsEntry("scenarioVersion", new HuntScenarios().require("A01").version());
    var old = new LinkedHashMap<>(rule.body());
    old.put("scenarioVersion", "2.0.0");
    db.update(
        "update hunt_resources set body_json=? where id=?",
        new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .writeValueAsString(old),
        rule.id());
    db.update(
        "update hunt_rule_runtime set observed_since=?,ready_runs=10 where rule_id=?",
        Timestamp.from(now.minusSeconds(8 * 86400L)),
        rule.id());
    String id = rule.id();
    long version = rule.version();
    assertThatThrownBy(() -> rules.transition(id, "ACTIVE", version, user))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("시나리오 버전");
    rules.tick();
    var paused = workspace.get(id, user);
    assertThat(paused.body()).containsEntry("state", "PAUSED");
    assertThat(
            db.queryForObject(
                "select last_state from hunt_rule_runtime where rule_id=?", String.class, id))
        .isEqualTo("VERSION_MISMATCH");
    verify(jobs, never()).create(any(), any(), anyString());
    rules.transition(id, "OBSERVE", paused.version(), user);
    assertThat(
            db.queryForObject(
                "select ready_runs from hunt_rule_runtime where rule_id=?", Integer.class, id))
        .isZero();
  }

  private void finishRun(HuntWorkspace.Resource rule, Instant first) {
    finishRun(rule, first, first.plusSeconds(10));
  }

  private void finishRun(HuntWorkspace.Resource rule, Instant first, Instant last) {
    // ClickHouse DateTime64(3) produces millisecond precision; do not round a nano fixture in H2.
    first = first.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    last = last.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    db.update(
        "update hunt_rule_runtime set last_started_at=? where rule_id=?",
        Timestamp.from(Instant.now().minusSeconds(120)),
        rule.id());
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    var finding =
        Map.of(
            "src_ip",
            "10.0.0.1",
            "dst_ip",
            "10.0.0.2",
            "asset_uid",
            "host",
            "realm",
            "ACME",
            "actor",
            "alice",
            "first_seen",
            first.toString(),
            "last_seen",
            last.toString(),
            "hits",
            8,
            "command_line",
            "curl password=QA_EPISODE_API_SECRET --host visible");
    when(jobs.create(any(), eq(user), eq("A01")))
        .thenReturn(new HuntJobs.Job(id, "QUEUED", now, now, null, null));
    when(jobs.get(id, user))
        .thenReturn(
            new HuntJobs.Job(
                id,
                "COMPLETED",
                now,
                now,
                null,
                Map.of(
                    "scenario",
                    Map.of(
                        "id",
                        "A01",
                        "readiness",
                        "READY",
                        "complete",
                        true,
                        "findings",
                        List.of(finding)))));
    rules.tick();
    rules.tick();
    assertThat(db.queryForMap("select * from hunt_rule_runtime where rule_id=?", rule.id()))
        .containsEntry("last_state", "COMPLETED");
  }
}
