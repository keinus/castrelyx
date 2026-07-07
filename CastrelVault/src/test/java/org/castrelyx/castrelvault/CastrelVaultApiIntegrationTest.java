package org.castrelyx.castrelvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.castrelyx.castrelvault.application.CastrelSignAccessClient;
import org.castrelyx.castrelvault.application.CastrelSignAccessClient.AccessDecision;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.castrelyx.castrelvault.integration.CastrelSignApplicationAdminClient;
import org.castrelyx.castrelvault.integration.ManagerMigrationAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CastrelVaultApiIntegrationTest {
  private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BOOTSTRAP_PASSWORD = "bootstrap-password-123";
  private static final String READY_PASSWORD = "ready-password-123";
  private static final String KNOWN_SECRET = "known-api-token-value-987654";

  @TempDir
  static Path tempDir;

  @Autowired
  MockMvc mockMvc;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @MockitoBean
  CastrelSignAccessClient castrelSignAccessClient;

  @MockitoBean
  CastrelSignApplicationAdminClient applicationAdminClient;

  @MockitoBean
  ManagerMigrationAdminClient managerMigrationClient;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("castrelvault.data-dir", () -> tempDir.toString());
    registry.add("castrelvault.master-keys", () -> "key-1:" + key("a"));
    registry.add("castrelvault.active-master-key-id", () -> "key-1");
    registry.add("castrelvault.bootstrap-admin-username", () -> "admin");
    registry.add("castrelvault.bootstrap-admin-password", () -> BOOTSTRAP_PASSWORD);
    registry.add("castrelvault.session-ttl-seconds", () -> "3600");
  }

  @BeforeEach
  void cleanState() {
    jdbcTemplate.update("delete from vault_application_access_cache");
    jdbcTemplate.update("delete from vault_audit_events");
    jdbcTemplate.update("delete from vault_secret_versions");
    jdbcTemplate.update("update vault_secrets set current_version_id = null");
    jdbcTemplate.update("delete from vault_secrets");
    jdbcTemplate.update("delete from vault_sessions");
    jdbcTemplate.update("""
        update vault_users
        set password_hash = ?, require_password_change = 1, updated_at = ?
        where username = 'admin'
        """, new BCryptPasswordEncoder(12).encode(BOOTSTRAP_PASSWORD), Instant.now().toString());
    doReturn(new CastrelSignApplicationAdminClient.Status(false, "", "UNCONFIGURED", "test"))
        .when(applicationAdminClient).status();
    doReturn(Map.of("configured", false, "status", "UNCONFIGURED"))
        .when(managerMigrationClient).status();
  }

  @Test
  void startsWithEmbeddedSqliteDatabaseUnderConfiguredDataDir() {
    assertThat(tempDir.resolve("vault.db")).exists();
  }

  @Test
  void firstLoginRequiresPasswordChangeAndStoresOnlySessionHash() throws Exception {
    MockHttpServletResponse login = mockMvc.perform(post("/api/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"%s"}
                """.formatted(BOOTSTRAP_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(cookie().exists(AdminSessionService.SESSION_COOKIE))
        .andExpect(jsonPath("$.requiresPasswordChange").value(true))
        .andReturn()
        .getResponse();

    String token = login.getCookie(AdminSessionService.SESSION_COOKIE).getValue();
    Integer rawTokenMatches = jdbcTemplate.queryForObject(
        "select count(*) from vault_sessions where token_hash = ?", Integer.class, token);
    Integer hashedTokenMatches = jdbcTemplate.queryForObject(
        "select count(*) from vault_sessions where token_hash = ?", Integer.class, AdminSessionService.hash(token));

    assertThat(rawTokenMatches).isZero();
    assertThat(hashedTokenMatches).isEqualTo(1);
  }

  @Test
  void secretLifecycleKeepsPlaintextOutOfNormalApisDatabaseAuditAndStaticAssets() throws Exception {
    var auth = readyAdmin();
    MvcResult created = mockMvc.perform(post("/api/secrets")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "path": "/integrations/logparser/admin-token",
                  "displayName": "Logparser admin token",
                  "type": "API_TOKEN",
                  "tags": ["integration"],
                  "payload": {"value": "%s"}
                }
                """.formatted(KNOWN_SECRET)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.masked").value("********"))
        .andExpect(jsonPath("$", not(containsString(KNOWN_SECRET))))
        .andReturn();
    String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/secrets").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", not(containsString(KNOWN_SECRET))));
    mockMvc.perform(get("/api/secrets/" + id).cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", not(containsString(KNOWN_SECRET))));

    assertDatabaseDoesNotContain(KNOWN_SECRET);

    mockMvc.perform(post("/api/secrets/" + id + "/reveal")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","reason":"operator break glass"}
                """.formatted(READY_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(jsonPath("$.payload.value").value(KNOWN_SECRET));

    mockMvc.perform(get("/api/audit-events").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", not(containsString(KNOWN_SECRET))))
        .andExpect(jsonPath("$[0].action").value("REVEAL_SECRET"));

    assertStaticAssetsDoNotContain(KNOWN_SECRET);
  }

  @Test
  void revealRequiresCurrentPasswordReasonAndEnabledSecretAndAuditsFailures() throws Exception {
    var auth = readyAdmin();
    String id = createSecret(auth, "/servers/core/login", "SERVER_LOGIN", "server-password-123");

    mockMvc.perform(post("/api/secrets/" + id + "/reveal")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"wrong-password","reason":"maintenance"}
                """))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/secrets/" + id + "/reveal")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","reason":""}
                """.formatted(READY_PASSWORD)))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/secrets/" + id + "/disable")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken()))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/secrets/" + id + "/reveal")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","reason":"maintenance"}
                """.formatted(READY_PASSWORD)))
        .andExpect(status().isConflict());

    Integer denied = jdbcTemplate.queryForObject("""
        select count(*) from vault_audit_events
        where action = 'REVEAL_SECRET' and result = 'DENIED'
        """, Integer.class);
    assertThat(denied).isGreaterThanOrEqualTo(3);
  }

  @Test
  void applicationCertificateResolvesPermittedSecretAndRejectsMissingExpiredOrDeniedPrincipals() throws Exception {
    var auth = readyAdmin();
    createSecret(auth, "/apps/manager/api-token", "API_TOKEN", "manager-token-777");
    TestCertificate permitted = new TestCertificate("manager-app", new BigInteger("abc", 16), false);
    doReturn(AccessDecision.allowed(Instant.now().plusSeconds(60)))
        .when(castrelSignAccessClient)
        .checkVaultAccess("manager-app", "vault:resolve", "abc");

    mockMvc.perform(post("/api/v1/secrets/resolve")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {permitted})
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"reference":"vault:///apps/manager/api-token"}
                """))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(jsonPath("$.secrets['/apps/manager/api-token'].value").value("manager-token-777"));

    mockMvc.perform(post("/api/v1/secrets/resolve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"/apps/manager/api-token"}
                """))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/v1/secrets/resolve")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {new TestCertificate("expired-app", new BigInteger("123", 16), true)})
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"/apps/manager/api-token"}
                """))
        .andExpect(status().isUnauthorized());

    TestCertificate denied = new TestCertificate("blocked-app", new BigInteger("def", 16), false);
    doReturn(AccessDecision.denied("application principal is missing Vault permission"))
        .when(castrelSignAccessClient)
        .checkVaultAccess("blocked-app", "vault:resolve", "def");

    mockMvc.perform(post("/api/v1/secrets/resolve")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {denied})
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"/apps/manager/api-token"}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void disabledAndDeletedSecretsCannotResolve() throws Exception {
    var auth = readyAdmin();
    String id = createSecret(auth, "/apps/disabled/api-token", "API_TOKEN", "disabled-token");
    mockMvc.perform(post("/api/secrets/" + id + "/disable")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken()))
        .andExpect(status().isOk());

    TestCertificate certificate = new TestCertificate("manager-app", new BigInteger("aaa", 16), false);
    doReturn(AccessDecision.allowed(Instant.now().plusSeconds(60)))
        .when(castrelSignAccessClient)
        .checkVaultAccess("manager-app", "vault:resolve", "aaa");

    mockMvc.perform(post("/api/v1/secrets/resolve")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate})
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"/apps/disabled/api-token"}
                """))
        .andExpect(status().isConflict());

    mockMvc.perform(delete("/api/secrets/" + id)
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken()))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/secrets/" + id).cookie(auth.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void adminStatusVersionsAuditSearchAndCsrfProtectionWork() throws Exception {
    var auth = readyAdmin();
    String id = createSecret(auth, "/integrations/castrelsign/admin-token", "API_TOKEN", "initial-token");

    mockMvc.perform(post("/api/secrets/" + id + "/rotate")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"payload":{"value":"rotated-token"}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(2));

    mockMvc.perform(post("/api/secrets/" + id + "/disable")
            .cookie(auth.cookie()))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/api/admin/status").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeMasterKeyId").value("key-1"))
        .andExpect(jsonPath("$.secrets.total").value(1))
        .andExpect(jsonPath("$.secrets.versions").value(2))
        .andExpect(jsonPath("$.castrelSign.state").value("UNCONFIGURED"));

    mockMvc.perform(get("/api/secrets/" + id + "/versions").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[0].keyId").value("key-1"))
        .andExpect(jsonPath("$[0].payloadContentHash").isNotEmpty());

    mockMvc.perform(get("/api/audit-events/search")
            .cookie(auth.cookie())
            .queryParam("action", "ROTATE_SECRET")
            .queryParam("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.events[0].action").value("ROTATE_SECRET"))
        .andExpect(jsonPath("$", not(containsString("rotated-token"))));
  }

  @Test
  void vaultUiProxyEndpointsManageApplicationsAndManagerMigrationWithoutBrowserHeldTokens() throws Exception {
    var auth = readyAdmin();
    doReturn(List.of(Map.of("principalId", "manager-app", "status", "ACTIVE", "permissions", List.of("vault:resolve"))))
        .when(applicationAdminClient).applications();
    doReturn(Map.of("principalId", "new-app", "status", "ACTIVE"))
        .when(applicationAdminClient).createApplication(any());
    doReturn(Map.of("principalId", "new-app", "permissions", List.of("vault:resolve")))
        .when(applicationAdminClient).grantPermission(anyString(), any());
    doReturn(List.of(Map.of("principalId", "manager-app", "serialNumber", "abc", "status", "ACTIVE")))
        .when(applicationAdminClient).certificates();
    doReturn(List.of(Map.of("id", 7, "principalId", "manager-app")))
        .when(applicationAdminClient).tokens();
    doReturn(Map.of("id", 8, "principalId", "new-app", "token", "one-use-token"))
        .when(applicationAdminClient).createToken(any());
    doReturn(Map.of("vaultEnabled", true, "pendingIntegrationSecrets", 1, "pendingSnmpCredentials", 1))
        .when(managerMigrationClient).dryRun();
    doReturn(Map.of("migratedIntegrationSecrets", 1, "migratedSnmpCredentials", 1, "status", "migrated"))
        .when(managerMigrationClient).run(anyString());

    mockMvc.perform(get("/api/applications").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].principalId").value("manager-app"));

    mockMvc.perform(post("/api/applications")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"principal_id":"new-app","display_name":"New App"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principalId").value("new-app"));

    mockMvc.perform(post("/api/applications/new-app/permissions")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"permission":"vault:resolve"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("vault:resolve"));

    mockMvc.perform(get("/api/applications/certificates").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].serialNumber").value("abc"));

    mockMvc.perform(get("/api/applications/tokens").cookie(auth.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].token").doesNotExist());

    mockMvc.perform(post("/api/applications/tokens")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"new token","principal_id":"new-app","ttl_seconds":3600}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("one-use-token"));

    mockMvc.perform(post("/api/manager-migration/dry-run")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingIntegrationSecrets").value(1));

    mockMvc.perform(post("/api/manager-migration/run")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.migratedSnmpCredentials").value(1));
  }

  private AdminAuth readyAdmin() throws Exception {
    MockHttpServletResponse login = mockMvc.perform(post("/api/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"%s"}
                """.formatted(READY_PASSWORD)))
        .andReturn()
        .getResponse();
    if (login.getStatus() == 200) {
      return new AdminAuth(login.getCookie(AdminSessionService.SESSION_COOKIE),
          login.getCookie(AdminSessionService.CSRF_COOKIE).getValue());
    }

    MockHttpServletResponse bootstrapLogin = mockMvc.perform(post("/api/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"%s"}
                """.formatted(BOOTSTRAP_PASSWORD)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse();
    var cookie = bootstrapLogin.getCookie(AdminSessionService.SESSION_COOKIE);
    var csrfToken = bootstrapLogin.getCookie(AdminSessionService.CSRF_COOKIE).getValue();
    mockMvc.perform(post("/api/admin/change-password")
            .cookie(cookie)
            .header("X-CSRF-Token", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(BOOTSTRAP_PASSWORD, READY_PASSWORD)))
        .andExpect(status().isOk());
    return new AdminAuth(cookie, csrfToken);
  }

  private String createSecret(AdminAuth auth, String path, String type, String value) throws Exception {
    MvcResult created = mockMvc.perform(post("/api/secrets")
            .cookie(auth.cookie())
            .header("X-CSRF-Token", auth.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"%s","displayName":"%s","type":"%s","payload":{"value":"%s"}}
                """.formatted(path, path, type, value)))
        .andExpect(status().isOk())
        .andReturn();
    return MAPPER.readTree(created.getResponse().getContentAsString()).get("id").asText();
  }

  private record AdminAuth(jakarta.servlet.http.Cookie cookie, String csrfToken) {
  }

  private void assertDatabaseDoesNotContain(String value) {
    List<byte[]> blobs = jdbcTemplate.query("""
        select encrypted_dek from vault_secret_versions
        union all
        select ciphertext from vault_secret_versions
        """, (rs, rowNum) -> rs.getBytes(1));
    for (byte[] blob : blobs) {
      assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain(value);
    }
    String auditText = String.join("\n", jdbcTemplate.query("""
        select actor_type || ' ' || coalesce(actor_id, '') || ' ' || coalesce(secret_path, '') || ' ' || action || ' ' || result || ' ' || coalesce(reason, '') || ' ' || source_metadata
        from vault_audit_events
        """, (rs, rowNum) -> rs.getString(1)));
    assertThat(auditText).doesNotContain(value);
  }

  private void assertStaticAssetsDoNotContain(String value) throws Exception {
    Path staticRoot = Path.of("src/main/resources/static");
    try (var files = Files.walk(staticRoot)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        assertThat(Files.readString(file)).doesNotContain(value);
      }
    }
  }

  private static String key(String seed) {
    String value = (seed.repeat(32) + "00000000000000000000000000000000").substring(0, 32);
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static class TestCertificate extends X509Certificate {
    private final String commonName;
    private final BigInteger serial;
    private final boolean expired;

    TestCertificate(String commonName, BigInteger serial, boolean expired) {
      this.commonName = commonName;
      this.serial = serial;
      this.expired = expired;
    }

    @Override
    public void checkValidity() throws CertificateExpiredException {
      if (expired) {
        throw new CertificateExpiredException("expired");
      }
    }

    @Override
    public void checkValidity(Date date) throws CertificateExpiredException, CertificateNotYetValidException {
      checkValidity();
    }

    @Override
    public int getVersion() {
      return 3;
    }

    @Override
    public BigInteger getSerialNumber() {
      return serial;
    }

    @Override
    public Principal getIssuerDN() {
      return () -> "CN=CastrelSign Root CA";
    }

    @Override
    public Principal getSubjectDN() {
      return () -> "CN=" + commonName;
    }

    @Override
    public X500Principal getSubjectX500Principal() {
      return new X500Principal("CN=" + commonName);
    }

    @Override
    public X500Principal getIssuerX500Principal() {
      return new X500Principal("CN=CastrelSign Root CA");
    }

    @Override
    public Date getNotBefore() {
      return Date.from(Instant.now().minusSeconds(60));
    }

    @Override
    public Date getNotAfter() {
      return Date.from(expired ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(3600));
    }

    @Override
    public byte[] getTBSCertificate() throws CertificateEncodingException {
      return new byte[0];
    }

    @Override
    public byte[] getSignature() {
      return new byte[0];
    }

    @Override
    public String getSigAlgName() {
      return "none";
    }

    @Override
    public String getSigAlgOID() {
      return "1.2.3";
    }

    @Override
    public byte[] getSigAlgParams() {
      return new byte[0];
    }

    @Override
    public boolean[] getIssuerUniqueID() {
      return null;
    }

    @Override
    public boolean[] getSubjectUniqueID() {
      return null;
    }

    @Override
    public boolean[] getKeyUsage() {
      return null;
    }

    @Override
    public int getBasicConstraints() {
      return -1;
    }

    @Override
    public byte[] getEncoded() throws CertificateEncodingException {
      return new byte[0];
    }

    @Override
    public void verify(PublicKey key) {
    }

    @Override
    public void verify(PublicKey key, String sigProvider) {
    }

    @Override
    public String toString() {
      return "TestCertificate[" + commonName + "]";
    }

    @Override
    public PublicKey getPublicKey() {
      return null;
    }

    @Override
    public Set<String> getCriticalExtensionOIDs() {
      return Set.of();
    }

    @Override
    public Set<String> getNonCriticalExtensionOIDs() {
      return Set.of();
    }

    @Override
    public byte[] getExtensionValue(String oid) {
      return null;
    }

    @Override
    public boolean hasUnsupportedCriticalExtension() {
      return false;
    }
  }
}
