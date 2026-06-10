package org.castrelyx.manager.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.castrelyx.manager.alert.AlertInstance;
import org.castrelyx.manager.alert.AlertRule;
import org.castrelyx.manager.alert.AlertStatus;
import org.castrelyx.manager.alert.Severity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertController {
  private final JdbcTemplate jdbcTemplate;

  public AlertController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping("/api/alerts")
  public List<AlertInstance> alerts() {
    return jdbcTemplate.query("""
        select id, rule_id, asset_id, severity, status, title, detail, state_key, first_seen_at, last_seen_at,
               acknowledged_at, resolved_at
        from alert_instances
        order by last_seen_at desc
        """, AlertController::alert);
  }

  @PostMapping("/api/alerts/{id}/acknowledge")
  public AlertInstance acknowledge(@PathVariable long id) {
    jdbcTemplate.update("update alert_instances set status = 'ACKNOWLEDGED', acknowledged_at = ? where id = ?",
        Timestamp.from(Instant.now()), id);
    return alert(id);
  }

  @PostMapping("/api/alerts/{id}/resolve")
  public AlertInstance resolve(@PathVariable long id) {
    jdbcTemplate.update("update alert_instances set status = 'RESOLVED', resolved_at = ? where id = ?",
        Timestamp.from(Instant.now()), id);
    return alert(id);
  }

  @GetMapping("/api/alert-rules")
  public List<AlertRule> rules() {
    return jdbcTemplate.query("""
        select id, name, rule_type, severity, expression_json, enabled
        from alert_rules
        order by id
        """, AlertController::rule);
  }

  @PutMapping("/api/alert-rules/{id}")
  public AlertRule updateRule(@PathVariable long id, @RequestBody AlertRule request) {
    jdbcTemplate.update("""
        update alert_rules
        set name = ?, severity = ?, expression_json = ?, enabled = ?
        where id = ?
        """, request.name(), request.severity().name(), request.expressionJson(), request.enabled(), id);
    return rule(id);
  }

  private AlertInstance alert(long id) {
    return jdbcTemplate.queryForObject("""
        select id, rule_id, asset_id, severity, status, title, detail, state_key, first_seen_at, last_seen_at,
               acknowledged_at, resolved_at
        from alert_instances
        where id = ?
        """, AlertController::alert, id);
  }

  private AlertRule rule(long id) {
    return jdbcTemplate.queryForObject("""
        select id, name, rule_type, severity, expression_json, enabled
        from alert_rules
        where id = ?
        """, AlertController::rule, id);
  }

  private static AlertInstance alert(ResultSet rs, int rowNum) throws SQLException {
    long assetId = rs.getLong("asset_id");
    return new AlertInstance(
        rs.getLong("id"),
        rs.getLong("rule_id"),
        rs.wasNull() ? null : assetId,
        Severity.valueOf(rs.getString("severity")),
        AlertStatus.valueOf(rs.getString("status")),
        rs.getString("title"),
        rs.getString("detail"),
        rs.getString("state_key"),
        instant(rs.getTimestamp("first_seen_at")),
        instant(rs.getTimestamp("last_seen_at")),
        instant(rs.getTimestamp("acknowledged_at")),
        instant(rs.getTimestamp("resolved_at")));
  }

  private static AlertRule rule(ResultSet rs, int rowNum) throws SQLException {
    return new AlertRule(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("rule_type"),
        Severity.valueOf(rs.getString("severity")),
        rs.getString("expression_json"),
        rs.getBoolean("enabled"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
