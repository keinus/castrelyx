package org.castrelyx.manager.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefaultAlertRuleSeeder {
  private final JdbcTemplate jdbcTemplate;

  public DefaultAlertRuleSeeder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    List<SeedRule> rules = List.of(
        new SeedRule("Agent heartbeat stale", "agent_heartbeat_stale", Severity.CRITICAL),
        new SeedRule("CPU threshold exceeded", "cpu_threshold", Severity.CRITICAL),
        new SeedRule("Memory threshold exceeded", "memory_threshold", Severity.CRITICAL),
        new SeedRule("Disk threshold exceeded", "disk_threshold", Severity.CRITICAL),
        new SeedRule("Interface down", "interface_down", Severity.CRITICAL),
        new SeedRule("Interface error/discard spike", "interface_error_spike", Severity.WARNING),
        new SeedRule("SNMP poll failure", "snmp_poll_failure", Severity.WARNING),
        new SeedRule("Logparser output failure", "logparser_output_failure", Severity.CRITICAL));
    for (SeedRule rule : rules) {
      Integer count = jdbcTemplate.queryForObject("select count(*) from alert_rules where rule_type = ?", Integer.class,
          rule.ruleType());
      if (count == null || count == 0) {
        jdbcTemplate.update("""
            insert into alert_rules(name, rule_type, severity, expression_json, enabled, created_at)
            values (?, ?, ?, ?, true, ?)
            """,
            rule.name(),
            rule.ruleType(),
            rule.severity().name(),
            "{}",
            Timestamp.from(Instant.now()));
      }
    }
  }

  private record SeedRule(String name, String ruleType, Severity severity) {
  }
}
