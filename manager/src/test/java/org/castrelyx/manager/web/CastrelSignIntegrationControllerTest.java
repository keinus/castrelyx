package org.castrelyx.manager.web;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CastrelSignIntegrationControllerTest {
  @Autowired
  MockMvc mockMvc;

  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @MockitoBean
  CastrelSignClient client;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from integration_configs");
    jdbcTemplate.update("delete from users");
    authProvider.createLocalUser("admin", "password", "Administrator", Role.ADMIN);
    authProvider.createLocalUser("operator", "password", "Operator", Role.OPERATOR);
  }

  @Test
  void adminUpdatesCastrelSignConfigAndResponseMasksSecret() throws Exception {
    Cookie admin = loginCookie("admin");

    mockMvc.perform(put("/api/integrations/castrelsign")
            .cookie(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"baseUrl":"https://castrelsign:8443","adminToken":"super-secret-token","enabled":true}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceName", is("castrelsign")))
        .andExpect(jsonPath("$.baseUrl", is("https://castrelsign:8443")))
        .andExpect(jsonPath("$.secret.configured", is(true)))
        .andExpect(jsonPath("$.secret.masked", is("********")))
        .andExpect(jsonPath("$.enabled", is(true)));

    String encrypted = jdbcTemplate.queryForObject(
        "select encrypted_secret from integration_configs where service_name = 'castrelsign'",
        String.class);
    org.assertj.core.api.Assertions.assertThat(encrypted).startsWith("v1:").doesNotContain("super-secret-token");

    mockMvc.perform(get("/api/integrations/castrelsign").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret.configured", is(true)))
        .andExpect(jsonPath("$.secret.masked", is("********")));
  }

  @Test
  void operatorCannotChangeCastrelSignSecret() throws Exception {
    Cookie operator = loginCookie("operator");

    mockMvc.perform(put("/api/integrations/castrelsign")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"baseUrl":"https://castrelsign:8443","adminToken":"super-secret-token","enabled":true}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCreatesEnrollmentToken() throws Exception {
    Cookie admin = loginCookie("admin");
    Map<?, ?> createdToken = Map.of("id", 3, "token", "enroll-token-123", "name", "Manager issued token", "agent_id", "edge-01",
        "max_uses", 1, "used_count", 0);
    doReturn(createdToken).when(client).createEnrollmentToken(anyMap());

    mockMvc.perform(post("/api/integrations/castrelsign/tokens")
            .cookie(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Manager issued token","agentId":"edge-01","ttlSeconds":3600,"maxUses":1}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token", is("enroll-token-123")))
        .andExpect(jsonPath("$.name", is("Manager issued token")))
        .andExpect(jsonPath("$.agentId", is("edge-01")))
        .andExpect(jsonPath("$.maxUses", is(1)))
        .andExpect(jsonPath("$.usedCount", is(0)))
        .andExpect(jsonPath("$.agent_id").doesNotExist());

    verify(client).createEnrollmentToken(anyMap());
  }

  @Test
  void operatorCannotCreateEnrollmentToken() throws Exception {
    Cookie operator = loginCookie("operator");

    mockMvc.perform(post("/api/integrations/castrelsign/tokens")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"description":"blocked"}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminReadsCastrelSignRuntimeState() throws Exception {
    Cookie admin = loginCookie("admin");
    List<?> tokens = List.of(Map.of("id", 4, "agent_id", "edge-agent", "max_uses", 1, "used_count", 0));
    List<?> agents = List.of(Map.of("agent_id", "edge-agent", "status", "ACTIVE", "first_seen_at", "2026-06-09T10:00:00Z"));
    List<?> certificates = List.of(Map.of("serial_number", "01", "subject_dn", "CN=edge-agent", "not_after", "2026-07-09T10:00:00Z", "status", "ACTIVE"));
    List<?> audits = List.of(Map.of("event_type", "AGENT_BLOCKED", "agent_id", "edge-agent", "created_at", "2026-06-09T10:10:00Z"));
    doReturn(tokens).when(client).listEnrollmentTokens();
    doReturn(agents).when(client).listAgents();
    doReturn(certificates).when(client).listCertificates();
    doReturn(audits).when(client).listAuditEvents();

    mockMvc.perform(get("/api/integrations/castrelsign/tokens").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agentId", is("edge-agent")))
        .andExpect(jsonPath("$[0].maxUses", is(1)))
        .andExpect(jsonPath("$[0].usedCount", is(0)))
        .andExpect(jsonPath("$[0].agent_id").doesNotExist());

    mockMvc.perform(get("/api/integrations/castrelsign/agents").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agentId", is("edge-agent")))
        .andExpect(jsonPath("$[0].firstSeenAt", is("2026-06-09T10:00:00Z")))
        .andExpect(jsonPath("$[0].first_seen_at").doesNotExist());

    mockMvc.perform(get("/api/integrations/castrelsign/certificates").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].serialNumber", is("01")))
        .andExpect(jsonPath("$[0].subjectDn", is("CN=edge-agent")))
        .andExpect(jsonPath("$[0].notAfter", is("2026-07-09T10:00:00Z")))
        .andExpect(jsonPath("$[0].serial_number").doesNotExist());

    mockMvc.perform(get("/api/integrations/castrelsign/audit-events").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].eventType", is("AGENT_BLOCKED")))
        .andExpect(jsonPath("$[0].agentId", is("edge-agent")))
        .andExpect(jsonPath("$[0].createdAt", is("2026-06-09T10:10:00Z")));
  }

  @Test
  void adminDownloadsHostnameAutoEnrollmentPackageZipWithoutClientMaterial() throws Exception {
    Cookie admin = loginCookie("admin");
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values ('castrelsign', 'https://castrelsign:8443', 'v1:fixture', true, current_timestamp, current_timestamp)
        """);
    Map<?, ?> token = Map.of("id", 7, "token", "enroll-token-zip", "name", "hostname auto enrollment",
        "max_uses", 1, "used_count", 0);
    doReturn(token).when(client).createEnrollmentToken(anyMap());
    doReturn("-----BEGIN CERTIFICATE-----\nfixture-ca\n-----END CERTIFICATE-----\n").when(client).rootCaPem();

    MvcResult result = mockMvc.perform(post("/api/integrations/castrelsign/enrollment-packages")
            .cookie(admin)
            .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"tenantId":"default","ttlSeconds":3600}
                """))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, String> entries = unzip(result.getResponse().getContentAsByteArray());
    org.assertj.core.api.Assertions.assertThat(entries.keySet()).containsExactlyInAnyOrder(
        "agent.yaml", "certs/ca.pem", "bin/castrelyx-agent-linux-amd64",
        "bin/castrelyx-agent-windows-amd64.exe", "install.bat", "install.ps1", "install.sh", "install.md");
    org.assertj.core.api.Assertions.assertThat(entries.keySet()).doesNotContain("certs/client.key", "certs/client.pem");
    org.assertj.core.api.Assertions.assertThat(entries.get("agent.yaml"))
        .contains("manager_url: https://castrelsign:8443")
        .contains("enrollment_token: enroll-token-zip")
        .contains("agent_id: __HOSTNAME__")
        .contains("tls_server_name: castrelsign")
        .contains("ingest_transport: tcp_mtls")
        .contains("tcp_ingest_addr: castrelsign:9443")
        .contains("tcp_ingest_server_name: castrelsign")
        .doesNotContain("cert_dir: ./certs")
        .doesNotContain("spool_dir: ./spool");
    org.assertj.core.api.Assertions.assertThat(entries.get("install.bat"))
        .contains("powershell.exe")
        .contains("install.ps1");
    org.assertj.core.api.Assertions.assertThat(entries.get("install.ps1"))
        .contains("[System.Net.Dns]::GetHostName()")
        .contains("__HOSTNAME__")
        .contains("$env:ProgramData")
        .contains("CastrelyxAgent")
        .contains("New-Service")
        .contains("Start-Service")
        .contains("castrelyx-agent-windows-amd64.exe");
    org.assertj.core.api.Assertions.assertThat(entries.get("install.sh"))
        .contains("hostname")
        .contains("__HOSTNAME__")
        .contains("/etc/castrelyx/agent.yaml")
        .contains("/etc/systemd/system/castrelyx-agent.service")
        .contains("systemctl enable --now castrelyx-agent")
        .contains("castrelyx-agent-linux-amd64");
    org.assertj.core.api.Assertions.assertThat(entries.get("install.md"))
        .contains("Windows")
        .contains("install.bat")
        .contains("Linux")
        .contains("install.sh");

    ArgumentCaptor<Map<String, Object>> tokenRequest = ArgumentCaptor.forClass(Map.class);
    verify(client).createEnrollmentToken(tokenRequest.capture());
    org.assertj.core.api.Assertions.assertThat(tokenRequest.getValue())
        .containsEntry("ttl_seconds", 3600)
        .containsEntry("max_uses", 1)
        .doesNotContainKey("agent_id");
    verify(client).rootCaPem();
  }

  @Test
  void adminCannotDownloadEnrollmentPackageForBlockedAgent() throws Exception {
    Cookie admin = loginCookie("admin");
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values ('castrelsign', 'https://castrelsign:8443', 'v1:fixture', true, current_timestamp, current_timestamp)
        """);
    doReturn(List.of(Map.of("agent_id", "edge-01", "status", "BLOCKED"))).when(client).listAgents();

    mockMvc.perform(post("/api/integrations/castrelsign/enrollment-packages")
            .cookie(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"agentId":"edge-01","tenantId":"default","ttlSeconds":3600,"maxUses":1,"tlsServerName":"castrelsign"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("blocked agents must be reactivated before issuing a new enrollment package")));
  }

  @Test
  void adminBlocksAndReactivatesAgentsThroughProxy() throws Exception {
    Cookie admin = loginCookie("admin");

    mockMvc.perform(post("/api/integrations/castrelsign/agents/edge-01/block").cookie(admin))
        .andExpect(status().isNoContent());
    verify(client).blockAgent(eq("edge-01"));

    mockMvc.perform(post("/api/integrations/castrelsign/agents/edge-01/reactivate").cookie(admin))
        .andExpect(status().isNoContent());
    verify(client).reactivateAgent(eq("edge-01"));
  }

  @Test
  void operatorCannotDownloadEnrollmentPackage() throws Exception {
    Cookie operator = loginCookie("operator");

    mockMvc.perform(post("/api/integrations/castrelsign/enrollment-packages")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"agentId":"edge-01","tenantId":"default"}
                """))
        .andExpect(status().isForbidden());
  }

  private Cookie loginCookie(String username) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"password"}
                """.formatted(username)))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie(AuthController.SESSION_COOKIE);
  }

  private static Map<String, String> unzip(byte[] zipBytes) throws Exception {
    java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
