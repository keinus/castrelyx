package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rules use persisted query jobs and guarded publication; observing never sends alerts. */
@Service
public class HuntRules {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final HuntWorkspace workspace;
  private final HuntJobs jobs;
  private final HuntScenarios catalogue;
  private final HuntEpisodes episodes;
  private final TransactionTemplate tx;

  public HuntRules(
      JdbcTemplate db,
      ObjectMapper json,
      HuntWorkspace workspace,
      HuntJobs jobs,
      HuntScenarios catalogue,
      HuntEpisodes episodes,
      PlatformTransactionManager transactions) {
    this.db = db;
    this.json = json;
    this.workspace = workspace;
    this.jobs = jobs;
    this.catalogue = catalogue;
    this.episodes = episodes;
    this.tx = new TransactionTemplate(transactions);
  }

  public HuntWorkspace.Resource transition(String id, String state, long version, AuthUser user) {
    if (user.role() != Role.ADMIN) throw new ForbiddenException("규칙 운영에는 관리자 권한이 필요합니다.");
    if (state == null || !Set.of("OBSERVE", "ACTIVE", "PAUSED").contains(state))
      throw new IllegalArgumentException("지원하지 않는 규칙 상태입니다.");
    return tx.execute(
        status -> {
          var rule = workspace.get(id, user);
          if (!rule.kind().equals("rule")) throw new IllegalArgumentException("규칙을 선택하세요.");
          if (rule.version() != version) throw new IllegalStateException("규칙이 변경되었습니다. 새로고침하세요.");
          var definition = catalogue.require(String.valueOf(rule.body().get("scenarioId")));
          HuntQuery query = json.convertValue(rule.body().get("query"), HuntQuery.class);
          if (query == null) throw new IllegalArgumentException("규칙 조회 범위가 필요합니다.");
          if (!query.text().isEmpty())
            throw new IllegalArgumentException("규칙에는 자유 검색어 대신 자산·주소 범위를 사용하세요.");
          HuntScenarioRunner.validateScope(query.filter());
          new HuntSql("validation").events(query, Instant.now());
          ensureRuntime(rule);
          var runtime =
              db.queryForMap("select * from hunt_rule_runtime where rule_id=? for update", id);
          String previous = String.valueOf(rule.body().getOrDefault("state", "DRAFT"));
          if (state.equals("PAUSED") && !Set.of("OBSERVE", "ACTIVE").contains(previous)
              || state.equals("OBSERVE") && !Set.of("DRAFT", "PAUSED").contains(previous))
            throw new IllegalStateException("현재 상태에서 해당 상태로 전환할 수 없습니다.");
          if (state.equals("ACTIVE")) {
            if (!definition.version().equals(rule.body().get("scenarioVersion")))
              throw new IllegalStateException("시나리오 버전이 변경되었습니다. 새 버전을 관찰 운영한 뒤 활성화하세요.");
            if (!previous.equals("OBSERVE")
                || !(runtime.get("observed_since") instanceof Timestamp since)
                || since.toInstant().isAfter(Instant.now().minusSeconds(7 * 86400L))
                || ((Number) runtime.get("ready_runs")).intValue() < 10)
              throw new IllegalStateException("활성화 전 7일 관찰 운영과 실행 가능 상태의 완료 기록 10회 이상이 필요합니다.");
          }
          var body = new LinkedHashMap<>(rule.body());
          body.put("state", state);
          if (state.equals("OBSERVE")) body.put("scenarioVersion", definition.version());
          var updated =
              workspace.saveRuleState(
                  id, new HuntWorkspace.Save(rule.title(), true, body, rule.version()), user);
          if (state.equals("OBSERVE"))
            db.update(
                "update hunt_rule_runtime set"
                    + " observed_since=?,ready_runs=0,rule_version=?,last_state='IDLE',last_job_id=null,last_error=null"
                    + " where rule_id=?",
                ts(Instant.now()),
                updated.version(),
                id);
          else
            db.update(
                "update hunt_rule_runtime set rule_version=? where rule_id=?",
                updated.version(),
                id);
          workspace.audit(
              user,
              "RULE_" + state,
              id,
              Map.of("previous", previous, "version", updated.version()));
          return updated;
        });
  }

  public List<Map<String, Object>> runtime(AuthUser user) {
    return db.queryForList(
        "select t.* from hunt_rule_runtime t join hunt_resources r on r.id=t.rule_id where"
            + " r.owner_id=? or r.shared=true order by t.last_started_at desc",
        user.id());
  }

  public List<Map<String, Object>> findings(AuthUser user) {
    return db.query(
        "select f.* from hunt_findings f join hunt_resources r on r.id=f.rule_id where (r.owner_id=?"
            + " or r.shared=true) and f.merged_into is null order by f.last_seen_at desc limit 200",
        (rs, n) ->
            Map.<String, Object>of(
                "id",
                rs.getString("id"),
                "scenarioId",
                rs.getString("scenario_id"),
                "ruleId",
                rs.getString("rule_id"),
                "ruleVersion",
                rs.getLong("rule_version"),
                "version",
                rs.getLong("version"),
                "firstSeen",
                rs.getTimestamp("first_seen_at").toInstant(),
                "lastSeen",
                rs.getTimestamp("last_seen_at").toInstant(),
                "jobId",
                rs.getString("last_job_id"),
                "status",
                rs.getString("status"),
                "details",
                decode(rs.getString("payload_json"))),
        user.id());
  }

  private void ensureRuntime(HuntWorkspace.Resource rule) {
    try {
      db.update(
          "insert into hunt_rule_runtime(rule_id,rule_version,last_state) values(?,?,'IDLE')",
          rule.id(),
          rule.version());
    } catch (DuplicateKeyException ignored) {
    }
  }

  @Scheduled(fixedDelay = 3000, initialDelay = 10000)
  public synchronized void tick() {
    var records =
        db.queryForList(
            "select"
                + " r.*,u.username,u.display_name,u.role,u.enabled,t.last_started_at,t.last_job_id,t.last_state"
                + " from hunt_resources r join users u on u.id=r.owner_id left join"
                + " hunt_rule_runtime t on t.rule_id=r.id where r.kind='rule' and r.shared=true"
                + " order by case when t.last_started_at is null then 0 else 1"
                + " end,t.last_started_at,r.created_at limit 200");
    int background = 0;
    for (var row : records) {
      String id = (String) row.get("id");
      var body = decode((String) row.get("body_json"));
      String state = String.valueOf(body.get("state"));
      Object enabled = row.get("enabled");
      if (!Set.of("OBSERVE", "ACTIVE").contains(state)
          || !(Boolean.TRUE.equals(enabled) || enabled instanceof Number n && n.intValue() == 1))
        continue;
      var user =
          new AuthUser(
              ((Number) row.get("owner_id")).longValue(),
              (String) row.get("username"),
              (String) row.get("display_name"),
              Role.valueOf((String) row.get("role")));
      if (user.role() == Role.VIEWER) continue;
      var rule = workspace.get(id, user);
      ensureRuntime(rule);
      var definition = catalogue.require(String.valueOf(body.get("scenarioId")));
      if (!definition.version().equals(body.get("scenarioVersion"))) {
        pauseChangedDefinition(rule, definition, user);
        continue;
      }
      if ("RUNNING".equals(row.get("last_state"))
          && row.get("last_job_id") instanceof String jobId) {
        try {
          var job = jobs.get(jobId, user);
          if (Set.of("QUEUED", "RUNNING").contains(job.state())) {
            background++;
            continue;
          }
          finish(rule, user, job, state);
        } catch (RuntimeException failure) {
          db.update(
              "update hunt_rule_runtime set last_state='FAILED',last_error=?,last_finished_at=?"
                  + " where rule_id=?",
              limit(failure.getMessage()),
              ts(Instant.now()),
              id);
        }
        continue;
      }
      if ("RUNNING".equals(row.get("last_state"))) {
        if (row.get("last_started_at") instanceof Timestamp started
            && started.toInstant().isBefore(Instant.now().minusSeconds(120)))
          db.update(
              "update hunt_rule_runtime set last_state='FAILED',last_error='실행 등록 중 중단됨' where"
                  + " rule_id=? and last_job_id is null",
              id);
        continue;
      }
      if (background >= 1) continue;
      if (row.get("last_started_at") instanceof Timestamp started
          && started.toInstant().isAfter(Instant.now().minusSeconds(60))) continue;
      try {
        var scenario = catalogue.require(String.valueOf(body.get("scenarioId")));
        Instant end = Instant.now();
        HuntQuery original = json.convertValue(body.get("query"), HuntQuery.class);
        var spec =
            new HuntQuery(
                end.minusSeconds(scenario.windowMinutes() * 60L + 600),
                end,
                original.filter(),
                "",
                original.groupBy(),
                "count",
                200,
                null);
        // Single Manager execution, backed by a conditional claim to avoid duplicate ticks.
        int claimed =
            db.update(
                "update hunt_rule_runtime set"
                    + " last_state='RUNNING',last_started_at=?,last_job_id=null,last_error=null"
                    + " where rule_id=? and last_state<>'RUNNING'",
                ts(end),
                id);
        if (claimed == 0) continue;
        try {
          var job = jobs.create(spec, user, scenario.id());
          db.update("update hunt_rule_runtime set last_job_id=? where rule_id=?", job.id(), id);
          background++;
        } catch (RuntimeException failure) {
          db.update(
              "update hunt_rule_runtime set last_state='FAILED',last_error=? where rule_id=?",
              limit(failure.getMessage()),
              id);
        }
      } catch (RuntimeException failure) {
        db.update(
            "update hunt_rule_runtime set last_state='FAILED',last_error=? where rule_id=?",
            limit(failure.getMessage()),
            id);
      }
    }
  }

  private void pauseChangedDefinition(
      HuntWorkspace.Resource rule, HuntScenarios.Scenario definition, AuthUser user) {
    tx.executeWithoutResult(
        status -> {
          db.queryForMap("select * from hunt_rule_runtime where rule_id=? for update", rule.id());
          var body = new LinkedHashMap<>(rule.body());
          body.put("state", "PAUSED");
          var updated =
              workspace.saveRuleState(
                  rule.id(),
                  new HuntWorkspace.Save(rule.title(), true, body, rule.version()),
                  user);
          db.update(
              "update hunt_rule_runtime set rule_version=?,last_state='VERSION_MISMATCH',last_error=?,last_finished_at=? where rule_id=?",
              updated.version(),
              "시나리오 버전 변경으로 중지했습니다. 새 버전 관찰 운영이 필요합니다.",
              ts(Instant.now()),
              rule.id());
          workspace.audit(
              user,
              "RULE_DEFINITION_CHANGED",
              rule.id(),
              Map.of(
                  "previousVersion",
                  String.valueOf(rule.body().get("scenarioVersion")),
                  "currentVersion",
                  definition.version()));
        });
  }

  private void finish(HuntWorkspace.Resource rule, AuthUser user, HuntJobs.Job job, String state) {
    tx.executeWithoutResult(
        status -> {
          var runtime =
              db.queryForMap(
                  "select * from hunt_rule_runtime where rule_id=? for update", rule.id());
          if (!"RUNNING".equals(runtime.get("last_state"))
              || !job.id().equals(runtime.get("last_job_id"))) return;
          var current =
              db.queryForMap(
                  "select version,body_json from hunt_resources where id=? for update", rule.id());
          if (((Number) current.get("version")).longValue() != rule.version()
              || !Set.of("OBSERVE", "ACTIVE")
                  .contains(
                      String.valueOf(decode((String) current.get("body_json")).get("state")))) {
            db.update(
                "update hunt_rule_runtime set last_state='SUPERSEDED',last_finished_at=? where"
                    + " rule_id=?",
                ts(Instant.now()),
                rule.id());
            return;
          }
          if (!job.state().equals("COMPLETED")) {
            db.update(
                "update hunt_rule_runtime set last_state=?,last_error=?,last_finished_at=? where"
                    + " rule_id=?",
                job.state(),
                job.error(),
                ts(Instant.now()),
                rule.id());
            return;
          }
          Map<String, Object> result = json.convertValue(job.result(), new TypeReference<>() {});
          @SuppressWarnings("unchecked")
          Map<String, Object> scenario = (Map<String, Object>) result.get("scenario");
          boolean ready =
              scenario != null
                  && "READY".equals(scenario.get("readiness"))
                  && Boolean.TRUE.equals(scenario.get("complete"));
          String readiness =
              scenario == null ? "FAILED" : String.valueOf(scenario.get("readiness"));
          db.update(
              "update hunt_rule_runtime set"
                  + " last_state=?,last_finished_at=?,ready_runs=ready_runs+?,last_error=? where"
                  + " rule_id=?",
              ready ? "COMPLETED" : readiness,
              ts(Instant.now()),
              ready ? 1 : 0,
              ready ? null : "필수 데이터·설정·결과 완전성을 확인하세요.",
              rule.id());
          if (!ready) return;
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> findings = (List<Map<String, Object>>) scenario.get("findings");
          var definition = catalogue.require(String.valueOf(scenario.get("id")));
          for (var finding : findings) {
            var payload = new LinkedHashMap<>(finding);
            payload.put("_query", result.get("query"));
            payload.put("_scenarioContext", scenario.get("context"));
            payload.put("_asOf", job.asOf().toString());
            payload.put("_coverage", result.get("coverage"));
            episodes.publish(rule, definition, payload, job.id(), state.equals("ACTIVE"));
          }
        });
  }

  private static String limit(String value) {
    if (value == null) return "규칙 실행에 실패했습니다.";
    return value.substring(0, Math.min(990, value.length()));
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("규칙을 읽을 수 없습니다.");
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("규칙 데이터를 확인하세요.");
    }
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }
}
