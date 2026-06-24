package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.castrelyx.castrelsign.update.AgentUpdateService;
import org.castrelyx.castrelsign.update.AgentUpdateService.UpdateCheckRequest;
import org.castrelyx.castrelsign.update.AgentUpdateService.UpdateStatusRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AgentUpdateControllerTest {
  @TempDir
  static Path tempDir;

  @Autowired
  MockMvc mockMvc;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  AgentUpdateService updateService;

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
  void cleanTables() {
    jdbcTemplate.update("delete from agent_update_attempts");
    jdbcTemplate.update("delete from agent_update_policies");
    jdbcTemplate.update("delete from agent_releases");
    jdbcTemplate.update("delete from audit_events");
    jdbcTemplate.update("delete from issued_certificates");
    jdbcTemplate.update("delete from enrollment_tokens");
    jdbcTemplate.update("delete from agents");
  }

  @Test
  void adminUploadsActivatesAndOffersSignedRelease() throws Exception {
    MockMultipartFile artifact = new MockMultipartFile(
        "artifact",
        "castrelyx-agent-linux-amd64",
        "application/octet-stream",
        "agent-binary-v020".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    mockMvc.perform(multipart("/api/admin/agent-releases")
            .file(artifact)
            .param("version", "0.2.0")
            .param("os", "linux")
            .param("arch", "amd64")
            .param("channel", "stable")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version", is("0.2.0")))
        .andExpect(jsonPath("$.status", is("DRAFT")))
        .andExpect(jsonPath("$.sha256").isNotEmpty())
        .andExpect(jsonPath("$.signature").isNotEmpty());

    mockMvc.perform(get("/api/admin/agent-update-public-key.pem")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BEGIN PUBLIC KEY")));

    long releaseId = jdbcTemplate.queryForObject("select id from agent_releases where version = '0.2.0'", Long.class);
    mockMvc.perform(post("/api/admin/agent-releases/" + releaseId + "/activate")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("ACTIVE")));

    mockMvc.perform(post("/api/admin/agent-update-policy")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"enabled":true,"channel":"stable","targetVersion":"0.2.0"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel", is("stable")))
        .andExpect(jsonPath("$.targetVersion", is("0.2.0")));

    var check = updateService.check("agent-01", new UpdateCheckRequest("0.1.0", "linux", "amd64", "stable", "daemon"));
    assertThat(check.updateAvailable()).isTrue();
    assertThat(check.deploymentId()).isNotBlank();
    assertThat(check.manifest()).contains("\"version\":\"0.2.0\"");
    assertThat(check.signature()).isNotBlank();

    updateService.recordStatus("agent-01", new UpdateStatusRequest(
        check.deploymentId(),
        check.release().id(),
        "0.1.0",
        "APPLIED",
        "agent restarted after update"));

    Integer attempts = jdbcTemplate.queryForObject(
        "select count(*) from agent_update_attempts where agent_id = 'agent-01' and status = 'APPLIED'",
        Integer.class);
    assertThat(attempts).isEqualTo(1);
  }
}
