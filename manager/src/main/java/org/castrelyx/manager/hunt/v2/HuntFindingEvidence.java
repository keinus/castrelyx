package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only lineage; every page rechecks the current rule visibility and stored proof hash. */
@Service
public class HuntFindingEvidence {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public HuntFindingEvidence(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> detail(String id, AuthUser user) {
    var row = read(id, user);
    var result = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "id",
            "rule_id",
            "rule_version",
            "scenario_id",
            "version",
            "status",
            "merged_into",
            "last_job_id")) result.put(key, row.get(key));
    result.put("firstSeen", ((Timestamp) row.get("first_seen_at")).toInstant());
    result.put("lastSeen", ((Timestamp) row.get("last_seen_at")).toInstant());
    result.put("latestCandidate", decode(String.valueOf(row.get("payload_json"))));
    result.put("occurrences", occurrences(id, null, user));
    result.put("merges", merges(id, null, user));
    return result;
  }

  public Map<String, Object> occurrences(String id, Long before, AuthUser user) {
    read(id, user);
    cursor(before);
    var args = new ArrayList<Object>(List.of(id, id));
    if (before != null) args.add(before);
    var rows =
        db.queryForList(
            "select o.* from hunt_finding_occurrences o join hunt_findings f on f.id=o.finding_id where (f.id=? or f.merged_into=?)"
                + (before == null ? "" : " and o.id<?")
                + " order by o.id desc limit 21",
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows.stream().limit(20).toList()) {
      String encoded = String.valueOf(row.get("snapshot_json")),
          sha = String.valueOf(row.get("snapshot_sha256"));
      verify(encoded, sha);
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("findingId", row.get("finding_id"));
      item.put("jobId", row.get("job_id"));
      item.put("createdAt", ((Timestamp) row.get("created_at")).toInstant());
      item.put("origin", row.get("origin"));
      item.put("evidence", decode(encoded));
      item.put("evidenceJson", encoded);
      item.put("evidenceSha256", sha);
      items.add(item);
    }
    return page(items, rows.size() > 20);
  }

  public Map<String, Object> merges(String id, Long before, AuthUser user) {
    read(id, user);
    cursor(before);
    var args = new ArrayList<Object>(List.of(id, id, id));
    if (before != null) args.add(before);
    var rows =
        db.queryForList(
            "select m.* from hunt_finding_merges m join hunt_findings f on f.id=m.source_id where (f.merged_into=? or m.source_id=? or m.target_id=?)"
                + (before == null ? "" : " and m.id<?")
                + " order by m.id desc limit 21",
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows.stream().limit(20).toList()) {
      String encoded = String.valueOf(row.get("source_json")),
          sha = String.valueOf(row.get("source_sha256"));
      verify(encoded, sha);
      var proof = new LinkedHashMap<>(decode(encoded));
      // The original SQL-row snapshot contains exact serialized payload bytes. A role copy must
      // expose a parsed payload to the common redactor, never that opaque original string.
      Object payload = proof.remove("payload_json");
      if (payload instanceof String source) proof.put("payload", decode(source));
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("sourceId", row.get("source_id"));
      item.put("targetId", row.get("target_id"));
      item.put("jobId", row.get("job_id"));
      item.put("actorId", row.get("actor_id"));
      item.put("createdAt", ((Timestamp) row.get("created_at")).toInstant());
      item.put("evidence", proof);
      item.put("evidenceJson", encoded);
      item.put("evidenceSha256", sha);
      items.add(item);
    }
    return page(items, rows.size() > 20);
  }

  private Map<String, Object> read(String id, AuthUser user) {
    var rows =
        db.queryForList(
            "select f.*,r.owner_id,r.shared from hunt_findings f join hunt_resources r on r.id=f.rule_id where f.id=?",
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("조사 후보를 찾을 수 없습니다.");
    var row = rows.getFirst();
    boolean shared =
        row.get("shared") instanceof Boolean b ? b : ((Number) row.get("shared")).intValue() != 0;
    if (((Number) row.get("owner_id")).longValue() != user.id() && !shared)
      throw new ForbiddenException("이 규칙의 조사 후보를 볼 수 없습니다.");
    return row;
  }

  private static void cursor(Long before) {
    if (before != null && before <= 0) throw new IllegalArgumentException("근거 페이지 커서를 확인하세요.");
  }

  private static Map<String, Object> page(List<Map<String, Object>> items, boolean more) {
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("hasMore", more);
    result.put("nextCursor", more ? items.getLast().get("id") : null);
    return result;
  }

  private static void verify(String source, String expected) {
    if (!HuntWorkspace.hash(source).equals(expected))
      throw new IllegalStateException("실행·병합 근거의 해시가 일치하지 않습니다.");
  }

  private Map<String, Object> decode(String source) {
    try {
      return json.readValue(source, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("실행·병합 근거를 읽을 수 없습니다.");
    }
  }
}
