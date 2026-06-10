package org.castrelyx.castrelsign;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAgentControllerTest {
  @TempDir
  static Path tempDir;

  @Autowired
  MockMvc mockMvc;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("castrelsign.data-dir", () -> tempDir.toString());
    registry.add("castrelsign.public-base-url", () -> "https://manager.local");
    registry.add("castrelsign.admin-token", () -> "admin-token");
    registry.add("castrelsign.enrollment-token", () -> "test-token");
  }

  @BeforeAll
  static void ensureTempDir() throws Exception {
    Files.createDirectories(tempDir);
  }

  @BeforeEach
  void fixture() {
    jdbcTemplate.update("delete from audit_events");
    jdbcTemplate.update("delete from enrollment_tokens");
    jdbcTemplate.update("delete from issued_certificates");
    jdbcTemplate.update("delete from agents");
    jdbcTemplate.update("""
        insert into agents(agent_id, hostname, version, status, first_seen_at, last_seen_at)
        values ('agent-01', 'host01', '0.1.0', 'ACTIVE', '2026-06-09T10:00:00Z', '2026-06-09T10:05:00Z')
        """);
    jdbcTemplate.update("""
        insert into issued_certificates(agent_id, serial_number, subject_dn, not_before, not_after, pem, status, issued_at)
        values ('agent-01', 'abc123', 'CN=agent-01', '2026-06-09T10:00:00Z', '2026-07-09T10:00:00Z',
                '-----BEGIN CERTIFICATE-----\\nfixture\\n-----END CERTIFICATE-----', 'ACTIVE', '2026-06-09T10:00:00Z')
        """);
    jdbcTemplate.update("""
        insert into enrollment_tokens(name, token_hash, agent_id, max_uses, used_count, expires_at, created_at)
        values ('agent-01 pending package', 'hash-agent-01', 'agent-01', 1, 0,
                '2026-07-09T10:00:00Z', '2026-06-09T10:00:00Z')
        """);
  }

  @Test
  void adminListsAgentsAndCertificates() throws Exception {
    mockMvc.perform(get("/api/admin/agents")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agent_id").value("agent-01"))
        .andExpect(jsonPath("$[0].hostname").value("host01"))
        .andExpect(jsonPath("$[0].status").value("ACTIVE"));

    mockMvc.perform(get("/api/admin/certificates")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agent_id").value("agent-01"))
        .andExpect(jsonPath("$[0].serial_number").value("abc123"))
        .andExpect(jsonPath("$[0].pem").doesNotExist());
  }

  @Test
  void adminListsRejectInvalidAdminToken() throws Exception {
    mockMvc.perform(get("/api/admin/agents")
            .header("Authorization", "Bearer wrong"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/admin/certificates"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminBlocksAndReactivatesAgentLifecycle() throws Exception {
    mockMvc.perform(post("/api/admin/agents/agent-01/block")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/admin/agents")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agent_id").value("agent-01"))
        .andExpect(jsonPath("$[0].status").value("BLOCKED"));

    Integer revokedCerts = jdbcTemplate.queryForObject(
        "select count(*) from issued_certificates where agent_id = 'agent-01' and status = 'REVOKED'",
        Integer.class);
    Integer revokedTokens = jdbcTemplate.queryForObject(
        "select count(*) from enrollment_tokens where agent_id = 'agent-01' and revoked_at is not null",
        Integer.class);
    Integer blockAudits = jdbcTemplate.queryForObject(
        "select count(*) from audit_events where agent_id = 'agent-01' and event_type = 'AGENT_BLOCKED'",
        Integer.class);
    org.assertj.core.api.Assertions.assertThat(revokedCerts).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(revokedTokens).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(blockAudits).isEqualTo(1);

    mockMvc.perform(post("/api/admin/agents/agent-01/reactivate")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/admin/agents")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("PENDING"));

    Integer activeCerts = jdbcTemplate.queryForObject(
        "select count(*) from issued_certificates where agent_id = 'agent-01' and status = 'ACTIVE'",
        Integer.class);
    org.assertj.core.api.Assertions.assertThat(activeCerts).isZero();
  }

  @Test
  void adminReadsAuditEventsAndRootCa() throws Exception {
    jdbcTemplate.update("""
        insert into audit_events(event_type, agent_id, message, created_at)
        values ('AGENT_BLOCKED', 'agent-01', 'blocked by operator', '2026-06-09T10:10:00Z')
        """);

    mockMvc.perform(get("/api/admin/audit-events")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].event_type").value("AGENT_BLOCKED"))
        .andExpect(jsonPath("$[0].agent_id").value("agent-01"));

    mockMvc.perform(get("/api/admin/ca.pem")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("-----BEGIN CERTIFICATE-----")));
  }
}
