package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AgentApiControllerTest {
  private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    registry.add("castrelsign.enrollment-token", () -> "test-token");
    registry.add("castrelsign.admin-token", () -> "admin-token");
  }

  @BeforeAll
  static void ensureTempDir() throws Exception {
    Files.createDirectories(tempDir);
  }

  @Test
  void enrollSignsCsrAndStoresAgentCertificate() throws Exception {
    String token = createEnrollmentToken("agent-01", 3600, 1);
    String csrPem = csr("agent-01", ecKeyPair());
    String requestJson = """
        {
          "agent_id": "agent-01",
          "hostname": "host01",
          "version": "0.1.0",
          "csr_pem": %s
        }
        """.formatted(MAPPER.writeValueAsString(csrPem));

    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content(requestJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent_id").value("agent-01"))
        .andExpect(jsonPath("$.ca_cert_pem").isNotEmpty())
        .andExpect(jsonPath("$.client_cert_pem").isNotEmpty())
        .andExpect(jsonPath("$.expires_at").isNotEmpty())
        .andExpect(jsonPath("$.ingest_url").value("https://manager.local/api/agent/ingest"));

    Integer agents = jdbcTemplate.queryForObject("select count(*) from agents where agent_id = 'agent-01'", Integer.class);
    Integer certs = jdbcTemplate.queryForObject("select count(*) from issued_certificates where agent_id = 'agent-01'", Integer.class);
    assertThat(agents).isEqualTo(1);
    assertThat(certs).isEqualTo(1);
  }

  @Test
  void enrollRejectsInvalidBearerToken() throws Exception {
    String csrPem = csr("agent-invalid-token", ecKeyPair());
    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer wrong")
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-invalid-token","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminCreatesOneTimeEnrollmentTokenAndEnrollConsumesIt() throws Exception {
    String token = createEnrollmentToken("agent-tokened", 3600, 1);
    String csrPem = csr("agent-tokened", ecKeyPair());
    String requestJson = """
        {
          "agent_id": "agent-tokened",
          "hostname": "host-tokened",
          "version": "0.1.0",
          "csr_pem": %s
        }
        """.formatted(MAPPER.writeValueAsString(csrPem));

    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content(requestJson))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content(requestJson))
        .andExpect(status().isUnauthorized());

    Integer used = jdbcTemplate.queryForObject("select used_count from enrollment_tokens where agent_id = 'agent-tokened'", Integer.class);
    assertThat(used).isEqualTo(1);
  }

  @Test
  void adminCreatedEnrollmentTokenDoesNotStorePlaintextToken() throws Exception {
    String token = createEnrollmentToken("agent-hashed", 3600, 1);

    Integer rawMatches = jdbcTemplate.queryForObject("select count(*) from enrollment_tokens where token_hash = ?", Integer.class, token);
    assertThat(rawMatches).isZero();
  }

  @Test
  void adminEnrollmentTokenApiRejectsInvalidAdminToken() throws Exception {
    mockMvc.perform(post("/api/admin/enrollment-tokens")
        .header("Authorization", "Bearer wrong")
        .contentType("application/json")
        .content("""
            {"name":"denied","agent_id":"agent-denied","ttl_seconds":3600,"max_uses":1}
            """))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void enrollRejectsTokenRestrictedToDifferentAgent() throws Exception {
    String token = createEnrollmentToken("agent-allowed", 3600, 1);
    String csrPem = csr("agent-denied", ecKeyPair());

    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-denied","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidCsrDoesNotConsumeEnrollmentToken() throws Exception {
    String token = createEnrollmentToken("agent-bad-csr", 3600, 1);
    String invalidCsrPem = csr("different-agent", ecKeyPair());

    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-bad-csr","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(invalidCsrPem))))
        .andExpect(status().isBadRequest());

    Integer used = jdbcTemplate.queryForObject("select used_count from enrollment_tokens where agent_id = 'agent-bad-csr'", Integer.class);
    assertThat(used).isZero();

    String validCsrPem = csr("agent-bad-csr", ecKeyPair());
    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-bad-csr","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(validCsrPem))))
        .andExpect(status().isOk());
  }

  @Test
  void revokedEnrollmentTokenCannotEnroll() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/admin/enrollment-tokens")
        .header("Authorization", "Bearer admin-token")
        .contentType("application/json")
        .content("""
            {"name":"revoked","agent_id":"agent-revoked","ttl_seconds":3600,"max_uses":1}
            """))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode tokenResponse = MAPPER.readTree(created.getResponse().getContentAsString());
    long tokenId = tokenResponse.get("id").asLong();
    String token = tokenResponse.get("token").asText();

    mockMvc.perform(post("/api/admin/enrollment-tokens/" + tokenId + "/revoke")
        .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isNoContent());

    String csrPem = csr("agent-revoked", ecKeyPair());
    mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-revoked","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void ingestRequiresGzipBatchAndStoresItems() throws Exception {
    String token = createEnrollmentToken("agent-ingest", 3600, 1);
    String csrPem = csr("agent-ingest", ecKeyPair());
    MvcResult enroll = mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {
              "agent_id": "agent-ingest",
              "hostname": "host01",
              "version": "0.1.0",
              "csr_pem": %s
            }
            """.formatted(MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode response = MAPPER.readTree(enroll.getResponse().getContentAsString());
    java.security.cert.X509Certificate clientCert = certificate(response.get("client_cert_pem").asText());

    byte[] body = gzip("""
        {
          "schema_version": "1.0",
          "source": "agent",
          "source_id": "agent-ingest",
          "tenant_id": "default",
          "observed_at": "2026-06-07T00:00:00Z",
          "sent_at": "2026-06-07T00:00:01Z",
          "items": [
            {"kind": "event", "type": "health", "key": "heartbeat", "payload": {"status": "ok"}}
          ]
        }
        """);

    mockMvc.perform(post("/api/agent/ingest")
        .requestAttr(CERTIFICATE_ATTRIBUTE, new java.security.cert.X509Certificate[] {clientCert})
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .content(body))
        .andExpect(status().isAccepted());

    Integer batches = jdbcTemplate.queryForObject("select count(*) from ingest_batches where agent_id = 'agent-ingest'", Integer.class);
    Integer items = jdbcTemplate.queryForObject("select count(*) from ingest_items where item_key = 'heartbeat'", Integer.class);
    assertThat(batches).isEqualTo(1);
    assertThat(items).isEqualTo(1);
  }

  @Test
  void renewRequiresClientCertificate() throws Exception {
    mockMvc.perform(post("/api/agent/renew")
        .contentType("application/json")
        .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void renewSignsNewCsrForMatchingClientCertificate() throws Exception {
    X509Certificate clientCert = enrollCertificate("agent-renew");
    String renewalCsr = csr("agent-renew", ecKeyPair());

    mockMvc.perform(post("/api/agent/renew")
        .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
        .contentType("application/json")
        .content("""
            {
              "agent_id": "agent-renew",
              "hostname": "host-renew",
              "version": "0.1.1",
              "csr_pem": %s
            }
            """.formatted(MAPPER.writeValueAsString(renewalCsr))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agent_id").value("agent-renew"))
        .andExpect(jsonPath("$.client_cert_pem").isNotEmpty());

    Integer certs = jdbcTemplate.queryForObject("select count(*) from issued_certificates where agent_id = 'agent-renew'", Integer.class);
    assertThat(certs).isEqualTo(2);
  }

  @Test
  void ingestRejectsMissingClientCertificate() throws Exception {
    mockMvc.perform(post("/api/agent/ingest")
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .content(gzip("""
            {"schema_version":"1.0","source":"agent","source_id":"agent-missing","tenant_id":"default","items":[]}
            """)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void ingestRejectsNonGzipBody() throws Exception {
    X509Certificate clientCert = enrollCertificate("agent-nongzip");

    mockMvc.perform(post("/api/agent/ingest")
        .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
        .contentType("application/json")
        .content("""
            {"schema_version":"1.0","source":"agent","source_id":"agent-nongzip","tenant_id":"default","items":[]}
            """))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void ingestRejectsSourceIdMismatch() throws Exception {
    String token = createEnrollmentToken("agent-cert", 3600, 1);
    String csrPem = csr("agent-cert", ecKeyPair());
    MvcResult enroll = mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":"agent-cert","hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isOk())
        .andReturn();
    java.security.cert.X509Certificate clientCert = certificate(MAPPER.readTree(enroll.getResponse().getContentAsString()).get("client_cert_pem").asText());

    mockMvc.perform(post("/api/agent/ingest")
        .requestAttr(CERTIFICATE_ATTRIBUTE, new java.security.cert.X509Certificate[] {clientCert})
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .content(gzip("""
            {"schema_version":"1.0","source":"agent","source_id":"different","tenant_id":"default","items":[]}
            """)))
        .andExpect(status().isForbidden());
  }

  private X509Certificate enrollCertificate(String agentId) throws Exception {
    String token = createEnrollmentToken(agentId, 3600, 1);
    String csrPem = csr(agentId, ecKeyPair());
    MvcResult enroll = mockMvc.perform(post("/api/agent/enroll")
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .content("""
            {"agent_id":%s,"hostname":"host01","version":"0.1.0","csr_pem":%s}
            """.formatted(MAPPER.writeValueAsString(agentId), MAPPER.writeValueAsString(csrPem))))
        .andExpect(status().isOk())
        .andReturn();
    return certificate(MAPPER.readTree(enroll.getResponse().getContentAsString()).get("client_cert_pem").asText());
  }

  private String createEnrollmentToken(String agentId, int ttlSeconds, int maxUses) throws Exception {
    MvcResult created = mockMvc.perform(post("/api/admin/enrollment-tokens")
        .header("Authorization", "Bearer admin-token")
        .contentType("application/json")
        .content("""
            {"name":%s,"agent_id":%s,"ttl_seconds":%d,"max_uses":%d}
            """.formatted(
                MAPPER.writeValueAsString("token-" + agentId),
                MAPPER.writeValueAsString(agentId),
                ttlSeconds,
                maxUses)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.token_hash").doesNotExist())
        .andReturn();
    return MAPPER.readTree(created.getResponse().getContentAsString()).get("token").asText();
  }

  private static KeyPair ecKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String csr(String commonName, KeyPair keyPair) throws Exception {
    byte[] der = new JcaPKCS10CertificationRequestBuilder(
        new X500Name("CN=" + commonName + ",O=Castrelyx,OU=agent"), keyPair.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate()))
        .getEncoded();
    return PemFiles.toPem("CERTIFICATE REQUEST", der);
  }

  private static java.security.cert.X509Certificate certificate(String pem) throws Exception {
    String normalized = pem
        .replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "")
        .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    return (java.security.cert.X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(der));
  }

  private static byte[] gzip(String value) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }
}
