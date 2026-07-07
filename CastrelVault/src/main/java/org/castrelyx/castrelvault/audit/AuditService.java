package org.castrelyx.castrelvault.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public void record(String actorType, String actorId, String secretPath, Integer secretVersion,
      String action, String result, String reason, HttpServletRequest request) {
    jdbcTemplate.update("""
        insert into vault_audit_events(timestamp, actor_type, actor_id, secret_path, secret_version, action, result, reason, source_metadata)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        Instant.now().toString(),
        nonBlank(actorType, "UNKNOWN"),
        blankToNull(actorId),
        blankToNull(secretPath),
        secretVersion,
        nonBlank(action, "UNKNOWN"),
        nonBlank(result, "UNKNOWN"),
        blankToNull(reason),
        sourceMetadata(request));
  }

  public List<Map<String, Object>> list(int limit) {
    return search(new AuditSearch(null, null, null, null, null, null, null, limit, 0)).events();
  }

  public AuditPage search(AuditSearch search) {
    int limit = Math.max(1, Math.min(search.limit() == null ? 100 : search.limit(), 500));
    int offset = Math.max(0, search.offset() == null ? 0 : search.offset());
    List<Object> args = new ArrayList<>();
    String where = whereClause(search, args);
    List<Map<String, Object>> events = jdbcTemplate.query("""
        select id, timestamp, actor_type, actor_id, secret_path, secret_version, action, result, reason, source_metadata
        from vault_audit_events
        %s
        order by id desc
        limit ? offset ?
        """.formatted(where), (rs, rowNum) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", rs.getLong("id"));
          row.put("timestamp", rs.getString("timestamp"));
          row.put("actorType", rs.getString("actor_type"));
          row.put("actorId", rs.getString("actor_id"));
          row.put("secretPath", rs.getString("secret_path"));
          long version = rs.getLong("secret_version");
          row.put("secretVersion", rs.wasNull() ? null : version);
          row.put("action", rs.getString("action"));
          row.put("result", rs.getString("result"));
          row.put("reason", rs.getString("reason"));
          row.put("sourceMetadata", metadataMap(rs.getString("source_metadata")));
          return row;
        }, withPagingArgs(args, limit, offset).toArray());
    Long total = jdbcTemplate.queryForObject("""
        select count(*)
        from vault_audit_events
        %s
        """.formatted(where), Long.class, args.toArray());
    return new AuditPage(events, total == null ? 0 : total, limit, offset);
  }

  public AuditSummary summary() {
    Integer total = jdbcTemplate.queryForObject("select count(*) from vault_audit_events", Integer.class);
    Integer denied = jdbcTemplate.queryForObject("select count(*) from vault_audit_events where result = 'DENIED'", Integer.class);
    Integer reveals = jdbcTemplate.queryForObject("select count(*) from vault_audit_events where action = 'REVEAL_SECRET'", Integer.class);
    Integer resolves = jdbcTemplate.queryForObject("select count(*) from vault_audit_events where action = 'RESOLVE_SECRET'", Integer.class);
    return new AuditSummary(
        total == null ? 0 : total,
        denied == null ? 0 : denied,
        reveals == null ? 0 : reveals,
        resolves == null ? 0 : resolves);
  }

  private static String whereClause(AuditSearch search, List<Object> args) {
    List<String> clauses = new ArrayList<>();
    addClause(clauses, args, "actor_type = ?", search.actorType());
    addClause(clauses, args, "actor_id = ?", search.actorId());
    addClause(clauses, args, "action = ?", search.action());
    addClause(clauses, args, "result = ?", search.result());
    addLikeClause(clauses, args, "secret_path like ?", search.secretPath());
    if (search.from() != null && !search.from().isBlank()) {
      clauses.add("timestamp >= ?");
      args.add(search.from().trim());
    }
    if (search.to() != null && !search.to().isBlank()) {
      clauses.add("timestamp <= ?");
      args.add(search.to().trim());
    }
    return clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses);
  }

  private static void addClause(List<String> clauses, List<Object> args, String clause, String value) {
    if (value != null && !value.isBlank()) {
      clauses.add(clause);
      args.add(value.trim());
    }
  }

  private static void addLikeClause(List<String> clauses, List<Object> args, String clause, String value) {
    if (value != null && !value.isBlank()) {
      clauses.add(clause);
      args.add("%" + value.trim() + "%");
    }
  }

  private static List<Object> withPagingArgs(List<Object> args, int limit, int offset) {
    List<Object> values = new ArrayList<>(args);
    values.add(limit);
    values.add(offset);
    return values;
  }

  private String sourceMetadata(HttpServletRequest request) {
    try {
      Map<String, Object> metadata = new LinkedHashMap<>();
      if (request != null) {
        metadata.put("remoteAddr", request.getRemoteAddr());
        metadata.put("userAgent", request.getHeader("User-Agent"));
        metadata.put("method", request.getMethod());
        metadata.put("path", request.getRequestURI());
      }
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception e) {
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> metadataMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  public record AuditSearch(
      String actorType,
      String actorId,
      String action,
      String result,
      String secretPath,
      String from,
      String to,
      Integer limit,
      Integer offset) {
    public AuditSearch {
      if (limit == null) {
        limit = 100;
      }
      if (offset == null) {
        offset = 0;
      }
    }
  }

  public record AuditPage(List<Map<String, Object>> events, long total, int limit, int offset) {
  }

  public record AuditSummary(int total, int denied, int reveals, int resolves) {
  }
}
