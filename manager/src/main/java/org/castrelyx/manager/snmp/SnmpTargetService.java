package org.castrelyx.manager.snmp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.integration.LogparserClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class SnmpTargetService {
  private final JdbcTemplate jdbcTemplate;
  private final LogparserClient logparserClient;

  public SnmpTargetService(JdbcTemplate jdbcTemplate, LogparserClient logparserClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.logparserClient = logparserClient;
  }

  public List<SnmpTarget> list() {
    return jdbcTemplate.query("""
        select id, name, host, port, credential_id, enabled, poll_interval_ms, logparser_adapter_id
        from snmp_targets
        order by id desc
        """, SnmpTargetService::target);
  }

  public SnmpTarget create(SnmpTargetRequest request) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var ps = connection.prepareStatement("""
          insert into snmp_targets(name, host, port, credential_id, enabled, poll_interval_ms, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?)
          """, new String[] {"id"});
      ps.setString(1, request.name());
      ps.setString(2, request.host());
      ps.setInt(3, request.port() == null ? 161 : request.port());
      if (request.credentialId() == null) {
        ps.setObject(4, null);
      } else {
        ps.setLong(4, request.credentialId());
      }
      ps.setBoolean(5, request.enabled() == null || request.enabled());
      ps.setLong(6, request.pollIntervalMs() == null ? 60000 : request.pollIntervalMs());
      ps.setTimestamp(7, Timestamp.from(Instant.now()));
      ps.setTimestamp(8, Timestamp.from(Instant.now()));
      return ps;
    }, keyHolder);
    SnmpTarget target = get(keyHolder.getKey().longValue());
    syncLogparserAdapter(target, request.oids());
    return target;
  }

  public SnmpTarget update(long id, SnmpTargetRequest request) {
    jdbcTemplate.update("""
        update snmp_targets
        set name = ?, host = ?, port = ?, credential_id = ?, enabled = ?, poll_interval_ms = ?, updated_at = ?
        where id = ?
        """,
        request.name(),
        request.host(),
        request.port() == null ? 161 : request.port(),
        request.credentialId(),
        request.enabled() == null || request.enabled(),
        request.pollIntervalMs() == null ? 60000 : request.pollIntervalMs(),
        Timestamp.from(Instant.now()),
        id);
    SnmpTarget target = get(id);
    syncLogparserAdapter(target, request.oids());
    return target;
  }

  public SnmpTarget enable(long id, boolean enabled) {
    jdbcTemplate.update("update snmp_targets set enabled = ?, updated_at = ? where id = ?", enabled, Timestamp.from(Instant.now()), id);
    return get(id);
  }

  public Map<String, Object> toLogparserPayload(SnmpTarget target, List<String> oids) {
    return Map.of(
        "type", "SnmpInputAdapter",
        "name", "manager-snmp-" + target.id(),
        "messagetype", "manager-snmp-" + target.id(),
        "enabled", target.enabled(),
        "configParams", Map.of(
            "targets", List.of(Map.of("host", target.host(), "port", target.port(), "name", target.name())),
            "oids", oids == null || oids.isEmpty() ? List.of("1.3.6.1.2.1.2.2.1") : oids,
            "intervalMs", target.pollIntervalMs(),
            "timeoutMs", 5000,
            "retries", 1,
            "queueSize", 1000,
            "workerThreads", 1));
  }

  private void syncLogparserAdapter(SnmpTarget target, List<String> oids) {
    logparserClient.upsertSnmpInputAdapter(toLogparserPayload(target, oids));
  }

  private SnmpTarget get(long id) {
    return jdbcTemplate.queryForObject("""
        select id, name, host, port, credential_id, enabled, poll_interval_ms, logparser_adapter_id
        from snmp_targets
        where id = ?
        """, SnmpTargetService::target, id);
  }

  private static SnmpTarget target(ResultSet rs, int rowNum) throws SQLException {
    long credentialId = rs.getLong("credential_id");
    Long credential = rs.wasNull() ? null : credentialId;
    long adapterId = rs.getLong("logparser_adapter_id");
    Long adapter = rs.wasNull() ? null : adapterId;
    return new SnmpTarget(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("host"),
        rs.getInt("port"),
        credential,
        rs.getBoolean("enabled"),
        rs.getLong("poll_interval_ms"),
        adapter);
  }
}
