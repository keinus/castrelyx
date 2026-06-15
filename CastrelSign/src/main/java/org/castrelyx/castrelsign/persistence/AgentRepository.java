package org.castrelyx.castrelsign.persistence;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import org.castrelyx.castrelsign.crypto.PemUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRepository {
  private final JdbcTemplate jdbcTemplate;
  private final EnrollmentTokenRepository enrollmentTokenRepository;

  public AgentRepository(JdbcTemplate jdbcTemplate, EnrollmentTokenRepository enrollmentTokenRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.enrollmentTokenRepository = enrollmentTokenRepository;
  }

  public void upsertAgent(String agentId, String hostname, String version) {
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agents
        set hostname = ?, version = ?, status = 'ACTIVE', last_seen_at = ?
        where agent_id = ?
        """, hostname, version, now, agentId);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into agents(agent_id, hostname, version, status, first_seen_at, last_seen_at)
          values (?, ?, ?, 'ACTIVE', ?, ?)
          """, agentId, hostname, version, now, now);
    }
  }

  public void saveCertificate(String agentId, X509Certificate certificate) {
    jdbcTemplate.update("update issued_certificates set status = 'SUPERSEDED' where agent_id = ? and status = 'ACTIVE'", agentId);
    jdbcTemplate.update("""
        insert into issued_certificates(agent_id, serial_number, subject_dn, not_before, not_after, pem, status, issued_at)
        values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
        """,
        agentId,
        certificate.getSerialNumber().toString(16),
        certificate.getSubjectX500Principal().getName(),
        certificate.getNotBefore().toInstant().toString(),
        certificate.getNotAfter().toInstant().toString(),
        PemUtil.certificateToPem(certificate),
        Instant.now().toString());
  }

  public void audit(String eventType, String agentId, String message) {
    jdbcTemplate.update("""
        insert into audit_events(event_type, agent_id, message, created_at)
        values (?, ?, ?, ?)
        """, eventType, agentId, message, Instant.now().toString());
  }

  public void blockAgent(String agentId) {
    String normalized = requireAgentId(agentId);
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agents
        set status = 'BLOCKED', last_seen_at = ?
        where agent_id = ?
        """, now, normalized);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into agents(agent_id, status, first_seen_at, last_seen_at)
          values (?, 'BLOCKED', ?, ?)
          """, normalized, now, now);
    }
    jdbcTemplate.update("""
        update issued_certificates
        set status = 'REVOKED'
        where agent_id = ? and status = 'ACTIVE'
        """, normalized);
    int revokedTokens = enrollmentTokenRepository.revokeUnusedForAgent(normalized);
    audit("AGENT_BLOCKED", normalized, "blocked agent and revoked " + revokedTokens + " unused enrollment token(s)");
  }

  public void reactivateAgent(String agentId) {
    String normalized = requireAgentId(agentId);
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agents
        set status = 'PENDING', last_seen_at = ?
        where agent_id = ?
        """, now, normalized);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into agents(agent_id, status, first_seen_at, last_seen_at)
          values (?, 'PENDING', ?, ?)
          """, normalized, now, now);
    }
    audit("AGENT_REACTIVATED", normalized, "reactivated agent; new enrollment is required");
  }

  public boolean isBlocked(String agentId) {
    var rows = jdbcTemplate.query("""
        select status from agents
        where agent_id = ?
        """, (rs, rowNum) -> rs.getString("status"), agentId);
    return !rows.isEmpty() && "BLOCKED".equals(rows.getFirst());
  }

  public boolean isActiveCertificate(String agentId, X509Certificate certificate) {
    String serial = certificate.getSerialNumber().toString(16);
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from issued_certificates
        where agent_id = ? and serial_number = ? and status = 'ACTIVE'
        """, Integer.class, agentId, serial);
    return count != null && count > 0;
  }

  public List<AgentRecord> listAgents() {
    return jdbcTemplate.query("""
        select agent_id, hostname, version, status, first_seen_at, last_seen_at
        from agents
        order by agent_id
        """, (rs, rowNum) -> new AgentRecord(
            rs.getString("agent_id"),
            rs.getString("hostname"),
            rs.getString("version"),
            rs.getString("status"),
            rs.getString("first_seen_at"),
            rs.getString("last_seen_at")));
  }

  public List<CertificateRecord> listCertificates() {
    return jdbcTemplate.query("""
        select id, agent_id, serial_number, subject_dn, not_before, not_after, status, issued_at
        from issued_certificates
        order by issued_at desc, id desc
        """, (rs, rowNum) -> new CertificateRecord(
            rs.getLong("id"),
            rs.getString("agent_id"),
            rs.getString("serial_number"),
            rs.getString("subject_dn"),
            rs.getString("not_before"),
            rs.getString("not_after"),
            rs.getString("status"),
            rs.getString("issued_at")));
  }

  public List<AuditEventRecord> listAuditEvents() {
    return jdbcTemplate.query("""
        select id, event_type, agent_id, message, created_at
        from audit_events
        order by created_at desc, id desc
        """, (rs, rowNum) -> new AuditEventRecord(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getString("agent_id"),
            rs.getString("message"),
            rs.getString("created_at")));
  }

  private static String requireAgentId(String agentId) {
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agent_id is required");
    }
    return agentId.trim();
  }

  public record AgentRecord(
      String agentId,
      String hostname,
      String version,
      String status,
      String firstSeenAt,
      String lastSeenAt) {
  }

  public record CertificateRecord(
      long id,
      String agentId,
      String serialNumber,
      String subjectDn,
      String notBefore,
      String notAfter,
      String status,
      String issuedAt) {
  }

  public record AuditEventRecord(
      long id,
      String eventType,
      String agentId,
      String message,
      String createdAt) {
  }
}
