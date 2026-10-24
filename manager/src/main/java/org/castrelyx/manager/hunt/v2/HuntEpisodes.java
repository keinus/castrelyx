package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publication joins the rule completion transaction and its per-rule runtime lock. */
@Service
public class HuntEpisodes {
  public static final String POLICY_VERSION = "2.0.0";
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public HuntEpisodes(JdbcTemplate db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  void publish(
      HuntWorkspace.Resource rule,
      HuntScenarios.Scenario definition,
      Map<String, Object> candidate,
      String jobId,
      boolean active) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Episode publication requires the rule completion transaction.");
    var group = new TreeMap<String, Object>();
    for (String key : definition.keys()) {
      if (!candidate.containsKey(key))
        throw new IllegalArgumentException("후보의 연결 식별자가 없습니다: " + key);
      Object value = candidate.get(key);
      if (value != null
          && !(value instanceof String)
          && !(value instanceof Number)
          && !(value instanceof Boolean))
        throw new IllegalArgumentException("후보 식별자는 단일 값이어야 합니다.");
      group.put(key, value);
    }
    var semantic = new TreeMap<String, Object>();
    candidate.forEach(
        (key, value) -> {
          if (!key.startsWith("_")) semantic.put(key, value);
        });
    String candidateSha = HuntWorkspace.hash(encode(semantic));
    if (!db.queryForList(
            "select id from hunt_finding_occurrences where rule_id=? and rule_version=? and job_id=? and candidate_sha256=?",
            rule.id(),
            rule.version(),
            jobId,
            candidateSha)
        .isEmpty()) return;
    Instant first = HuntExceptionPolicy.time(candidate.get("first_seen")),
        last = HuntExceptionPolicy.time(candidate.get("last_seen"));
    if (first == null || last == null || last.isBefore(first))
      throw new IllegalArgumentException("후보의 관측 구간을 확인하세요.");
    if (first.getNano() % 1_000_000 != 0 || last.getNano() % 1_000_000 != 0)
      throw new IllegalArgumentException("후보 관측 시각은 밀리초 정밀도여야 합니다.");
    String groupKey = HuntWorkspace.hash(encode(group));
    long window = definition.windowMinutes() * 60L;
    var connected = new LinkedHashMap<String, Map<String, Object>>();
    boolean expanded;
    do {
      expanded = false;
      var rows =
          db.queryForList(
              "select * from hunt_findings where rule_id=? and rule_version=? and group_key=? and merged_into is null and last_seen_at>=? and first_seen_at<=? order by first_seen_at,id limit 501 for update",
              rule.id(),
              rule.version(),
              groupKey,
              ts(first.minusSeconds(window)),
              ts(last.plusSeconds(window)));
      for (var row : rows)
        if (connected.putIfAbsent(String.valueOf(row.get("id")), row) == null) {
          if (connected.size() > 500)
            throw new IllegalStateException("연결된 episode가 500개를 초과합니다. 조사 범위를 나누세요.");
          first = earliest(first, instant(row, "first_seen_at"));
          last = latest(last, instant(row, "last_seen_at"));
          expanded = true;
        }
    } while (expanded);
    var roots = new ArrayList<>(connected.values());
    roots.sort(
        Comparator.<Map<String, Object>, Instant>comparing(row -> instant(row, "first_seen_at"))
            .thenComparing(row -> String.valueOf(row.get("id"))));
    String id =
        roots.isEmpty() ? UUID.randomUUID().toString() : String.valueOf(roots.getFirst().get("id"));
    Instant now = Instant.now(clock);
    String snapshot = encode(candidate), snapshotSha = HuntWorkspace.hash(snapshot);
    for (var root : roots) preserveLegacyResult(root, rule, now);
    if (roots.isEmpty())
      db.update(
          "insert into hunt_findings(id,rule_id,rule_version,group_key,scenario_id,first_seen_at,last_seen_at,last_job_id,payload_json) values(?,?,?,?,?,?,?,?,?)",
          id,
          rule.id(),
          rule.version(),
          groupKey,
          definition.id(),
          ts(first),
          ts(last),
          jobId,
          snapshot);
    else
      db.update(
          "update hunt_findings set first_seen_at=?,last_seen_at=?,last_job_id=?,payload_json=?,version=version+1 where id=?",
          ts(first),
          ts(last),
          jobId,
          snapshot,
          id);
    for (var source : roots) {
      String sourceId = String.valueOf(source.get("id"));
      if (sourceId.equals(id)) continue;
      String before = encode(source);
      db.update(
          "insert into hunt_finding_merges(source_id,target_id,job_id,actor_id,source_json,source_sha256,created_at) values(?,?,?,?,?,?,?)",
          sourceId,
          id,
          jobId,
          rule.ownerId(),
          before,
          HuntWorkspace.hash(before),
          ts(now));
      db.update(
          "update hunt_findings set merged_into=?,status='MERGED',version=version+1 where id=?",
          id,
          sourceId);
      // Flatten lookup aliases, while the merge log retains the original path and snapshots.
      db.update(
          "update hunt_findings set merged_into=?,version=version+1 where merged_into=?",
          id,
          sourceId);
    }
    db.update(
        "insert into hunt_finding_occurrences(finding_id,rule_id,rule_version,job_id,candidate_sha256,snapshot_json,snapshot_sha256,created_at) values(?,?,?,?,?,?,?,?)",
        id,
        rule.id(),
        rule.version(),
        jobId,
        candidateSha,
        snapshot,
        snapshotSha,
        ts(now));
    reconcileAlerts(rule, definition, id, roots, active, first, last, now);
    if (roots.size() > 1)
      db.update(
          "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,'HUNT_EPISODES_MERGED',?,?,?)",
          rule.ownerId(),
          id,
          encode(
              Map.of(
                  "policyVersion",
                  POLICY_VERSION,
                  "ruleId",
                  rule.id(),
                  "ruleVersion",
                  rule.version(),
                  "jobId",
                  jobId,
                  "canonicalId",
                  id,
                  "sourceIds",
                  roots.stream().map(row -> row.get("id")).toList())),
          ts(now));
  }

  private void reconcileAlerts(
      HuntWorkspace.Resource rule,
      HuntScenarios.Scenario definition,
      String id,
      List<Map<String, Object>> roots,
      boolean active,
      Instant first,
      Instant last,
      Instant now) {
    var alerts = new ArrayList<Map<String, Object>>();
    for (var root : roots)
      if (root.get("alert_id") instanceof Number number) {
        var found =
            db.queryForList(
                "select * from alert_instances where id=? for update", number.longValue());
        if (found.isEmpty()) throw new IllegalStateException("Episode에 연결된 알림이 없습니다.");
        alerts.addAll(found);
      }
    Long target =
        roots.isEmpty() || !(roots.getFirst().get("alert_id") instanceof Number number)
            ? null
            : number.longValue();
    if (target == null && !alerts.isEmpty())
      target =
          alerts.stream()
              .map(row -> ((Number) row.get("id")).longValue())
              .min(Long::compareTo)
              .orElseThrow();
    if (target == null && active) {
      Long ruleId =
          db.queryForObject(
              "select alert_rule_id from hunt_rule_runtime where rule_id=?", Long.class, rule.id());
      if (ruleId == null) {
        ruleId =
            insert(
                "insert into alert_rules(name,rule_type,severity,expression_json,enabled,created_at) values(?,'hunt','WARNING',?,true,?)",
                rule.title(),
                encode(Map.of("huntRuleId", rule.id())),
                ts(now));
        db.update(
            "update hunt_rule_runtime set alert_rule_id=? where rule_id=?", ruleId, rule.id());
      }
      target =
          insert(
              "insert into alert_instances(rule_id,severity,status,title,detail,state_key,first_seen_at,last_seen_at) values(?,'WARNING','ACTIVE',?,?,?,?,?)",
              ruleId,
              definition.title(),
              "헌팅 규칙 일치 · 침해 확정 아님 · 조사 결과 " + id,
              id,
              ts(first),
              ts(last));
    }
    if (target == null) return;
    db.update("update hunt_findings set alert_id=? where id=?", target, id);
    if (roots.size() > 1) {
      String status =
          alerts.stream()
              .map(row -> String.valueOf(row.get("status")))
              .min(Comparator.comparingInt(HuntEpisodes::attention))
              .orElse("ACTIVE");
      db.update(
          "update alert_instances set status=?,resolved_at=case when ?='RESOLVED' then resolved_at else null end where id=?",
          status,
          status,
          target);
    }
    db.update(
        "update alert_instances set state_key=?,first_seen_at=?,last_seen_at=? where id=?",
        id,
        ts(first),
        ts(last),
        target);
    for (var alert : alerts) {
      long source = ((Number) alert.get("id")).longValue();
      if (source == target) continue;
      db.update(
          "update alert_instances set status='MERGED',resolved_at=?,detail=concat(detail,?) where id=?",
          ts(now),
          " · 병합 대상 " + id,
          source);
    }
  }

  private static int attention(String state) {
    return switch (state) {
      case "ACTIVE" -> 0;
      case "ACKNOWLEDGED" -> 1;
      case "RESOLVED" -> 2;
      default -> throw new IllegalStateException("알림 상태를 확인하세요.");
    };
  }

  private void preserveLegacyResult(
      Map<String, Object> root, HuntWorkspace.Resource rule, Instant now) {
    String id = String.valueOf(root.get("id"));
    if (!db.queryForList("select id from hunt_finding_occurrences where finding_id=? limit 1", id)
        .isEmpty()) return;
    String source = String.valueOf(root.get("payload_json"));
    try {
      var value =
          json.readValue(
              source, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      var semantic = new TreeMap<String, Object>();
      value.forEach(
          (key, item) -> {
            if (!key.startsWith("_")) semantic.put(key, item);
          });
      String hash = HuntWorkspace.hash(encode(semantic)),
          job = String.valueOf(root.get("last_job_id"));
      if (db.queryForList(
              "select id from hunt_finding_occurrences where rule_id=? and rule_version=? and job_id=? and candidate_sha256=?",
              rule.id(),
              rule.version(),
              job,
              hash)
          .isEmpty())
        db.update(
            "insert into hunt_finding_occurrences(finding_id,rule_id,rule_version,job_id,candidate_sha256,origin,snapshot_json,snapshot_sha256,created_at) values(?,?,?,?,?,'LEGACY_LAST_RESULT',?,?,?)",
            id,
            rule.id(),
            rule.version(),
            job,
            hash,
            source,
            HuntWorkspace.hash(source),
            ts(now));
    } catch (Exception invalid) {
      throw new IllegalStateException("이전 episode의 근거를 보존할 수 없습니다.", invalid);
    }
  }

  private long insert(String sql, Object... values) {
    var key = new GeneratedKeyHolder();
    db.update(
        connection -> {
          PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Episode 근거를 저장할 수 없습니다.", e);
    }
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(Map<String, Object> row, String key) {
    return ((Timestamp) row.get(key)).toInstant();
  }

  private static Instant earliest(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Instant latest(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
