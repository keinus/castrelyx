package org.castrelyx.castrelsign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
