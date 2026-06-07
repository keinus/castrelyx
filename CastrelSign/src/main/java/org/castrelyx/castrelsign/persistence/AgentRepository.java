package org.castrelyx.castrelsign.persistence;

import java.security.cert.X509Certificate;
import java.time.Instant;

import org.castrelyx.castrelsign.crypto.PemUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRepository {
  private final JdbcTemplate jdbcTemplate;

  public AgentRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
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
}
