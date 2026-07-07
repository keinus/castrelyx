package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

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
class ApplicationPrincipalControllerTest {
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
    registry.add("castrelsign.public-base-url", () -> "https://castrelsign.local");
    registry.add("castrelsign.admin-token", () -> "admin-token");
  }

  @BeforeAll
  static void ensureTempDir() throws Exception {
    Files.createDirectories(tempDir);
  }

  @Test
  void adminCreatesApplicationPrincipalPermissionAndOneUseEnrollmentToken() throws Exception {
    mockMvc.perform(post("/api/admin/applications")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"principal_id":"manager-app","display_name":"Manager"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principalId").value("manager-app"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    mockMvc.perform(post("/api/admin/applications/manager-app/permissions")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"permission":"vault:resolve"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("vault:resolve"));

    MvcResult created = mockMvc.perform(post("/api/admin/application-enrollment-tokens")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"name":"manager enrollment","principal_id":"manager-app","ttl_seconds":3600}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.token_hash").doesNotExist())
        .andReturn();

    String token = MAPPER.readTree(created.getResponse().getContentAsString()).get("token").asText();
    Integer rawTokenMatches = jdbcTemplate.queryForObject(
        "select count(*) from application_enrollment_tokens where token_hash = ?", Integer.class, token);
    assertThat(rawTokenMatches).isZero();

    mockMvc.perform(get("/api/admin/application-enrollment-tokens")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].token").doesNotExist())
        .andExpect(jsonPath("$[0].token_hash").doesNotExist());
  }

  @Test
  void applicationEnrollmentIssuesCertificateAndVaultAccessDecisionUsesStatusPermissionAndSerial() throws Exception {
    createPrincipal("vault-client", true);
    String token = createToken("vault-client");
    String csr = csr("vault-client", ecKeyPair());

    MvcResult enrolled = mockMvc.perform(post("/api/applications/enroll")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("""
                {"principal_id":"vault-client","csr_pem":%s}
                """.formatted(MAPPER.writeValueAsString(csr))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principal_id").value("vault-client"))
        .andExpect(jsonPath("$.client_cert_pem").isNotEmpty())
        .andReturn();

    JsonNode response = MAPPER.readTree(enrolled.getResponse().getContentAsString());
    X509Certificate cert = certificate(response.get("client_cert_pem").asText());
    String serial = cert.getSerialNumber().toString(16);

    mockMvc.perform(get("/api/admin/applications/certificates")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].principalId").value("vault-client"))
        .andExpect(jsonPath("$[0].serialNumber").value(serial))
        .andExpect(jsonPath("$[0].status").value("ACTIVE"));

    mockMvc.perform(get("/api/applications/vault-client/vault-access")
            .queryParam("permission", "vault:resolve")
            .queryParam("serial_number", serial))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true));

    mockMvc.perform(post("/api/applications/enroll")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("""
                {"principal_id":"vault-client","csr_pem":%s}
                """.formatted(MAPPER.writeValueAsString(csr))))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/admin/applications/vault-client/block")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/applications/vault-client/vault-access")
            .queryParam("permission", "vault:resolve")
            .queryParam("serial_number", serial))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("application principal is not active"));
  }

  @Test
  void vaultAccessDeniesMissingVaultPermissionAndUnknownCertificateSerial() throws Exception {
    createPrincipal("no-permission-app", false);

    mockMvc.perform(get("/api/applications/no-permission-app/vault-access")
            .queryParam("permission", "vault:resolve")
            .queryParam("serial_number", "01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("application principal is missing Vault permission"));

    createPrincipal("serial-app", true);
    mockMvc.perform(get("/api/applications/serial-app/vault-access")
            .queryParam("permission", "vault:resolve")
            .queryParam("serial_number", "not-issued"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.reason").value("application certificate is not active"));
  }

  private void createPrincipal(String principalId, boolean grantPermission) throws Exception {
    mockMvc.perform(post("/api/admin/applications")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"principal_id":%s,"display_name":%s}
                """.formatted(MAPPER.writeValueAsString(principalId), MAPPER.writeValueAsString(principalId))))
        .andExpect(status().isOk());
    if (grantPermission) {
      mockMvc.perform(post("/api/admin/applications/" + principalId + "/permissions")
              .header("Authorization", "Bearer admin-token")
              .contentType("application/json")
              .content("""
                  {"permission":"vault:resolve"}
                  """))
          .andExpect(status().isOk());
    }
  }

  private String createToken(String principalId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/admin/application-enrollment-tokens")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"name":"token","principal_id":%s,"ttl_seconds":3600}
                """.formatted(MAPPER.writeValueAsString(principalId))))
        .andExpect(status().isOk())
        .andReturn();
    return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }

  private static KeyPair ecKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String csr(String commonName, KeyPair keyPair) throws Exception {
    byte[] der = new JcaPKCS10CertificationRequestBuilder(
        new X500Name("CN=" + commonName + ",O=Castrelyx,OU=application"), keyPair.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate()))
        .getEncoded();
    return PemFiles.toPem("CERTIFICATE REQUEST", der);
  }

  private static X509Certificate certificate(String pem) throws Exception {
    String normalized = pem
        .replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "")
        .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    return (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(der));
  }
}
