package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@AutoConfigureMockMvc
class AgentFileManagerControllerTest {
  private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir
  static Path tempDir;

  @Autowired
  MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("castrelsign.data-dir", () -> tempDir.toString());
    registry.add("castrelsign.public-base-url", () -> "https://manager.local");
    registry.add("castrelsign.admin-token", () -> "admin-token");
  }

  @BeforeAll
  static void ensureTempDir() throws Exception {
    Files.createDirectories(tempDir);
  }

  @Test
  void adminQueuesCommandAndAgentPollsAndCompletesIt() throws Exception {
    X509Certificate clientCert = enrollCertificate("agent-files-life");

    MvcResult created = mockMvc.perform(post("/api/admin/file-manager/agents/agent-files-life/commands")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"operation":"LIST","request":{"path":"/tmp"}}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andReturn();
    String commandId = MAPPER.readTree(created.getResponse().getContentAsString()).get("commandId").asText();

    mockMvc.perform(post("/api/agent/file-manager/check")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commands[0].id").value(commandId))
        .andExpect(jsonPath("$.commands[0].operation").value("LIST"))
        .andExpect(jsonPath("$.commands[0].request.path").value("/tmp"));

    mockMvc.perform(post("/api/agent/file-manager/commands/" + commandId + "/result")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
            .contentType("application/json")
            .content("""
                {"status":"SUCCEEDED","response":{"path":"/tmp","roots":[],"entries":[]}}
                """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(get("/api/admin/file-manager/commands/" + commandId)
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.responseJson").value(org.hamcrest.Matchers.containsString("\"path\":\"/tmp\"")));
  }

  @Test
  void uploadAndDownloadTransfersAreBoundToAgentCommands() throws Exception {
    X509Certificate clientCert = enrollCertificate("agent-files-transfer");
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "upload.txt",
        "text/plain",
        "hello upload".getBytes(StandardCharsets.UTF_8));

    MvcResult upload = mockMvc.perform(multipart("/api/admin/file-manager/agents/agent-files-transfer/uploads")
            .file(file)
            .param("path", "/tmp/upload.txt")
            .param("overwrite", "true")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operation").value("UPLOAD"))
        .andReturn();
    JsonNode uploadCommand = MAPPER.readTree(upload.getResponse().getContentAsString());
    String uploadCommandId = uploadCommand.get("commandId").asText();
    String uploadTransferId = MAPPER.readTree(uploadCommand.get("requestJson").asText()).get("transfer_id").asText();

    mockMvc.perform(get("/api/agent/file-manager/transfers/" + uploadTransferId + "/content")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert}))
        .andExpect(status().isOk())
        .andExpect(content().string("hello upload"));
    completeCommand(clientCert, uploadCommandId, "upload.txt");

    MvcResult download = mockMvc.perform(post("/api/admin/file-manager/agents/agent-files-transfer/downloads")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"path":"/tmp/server.txt"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.operation").value("DOWNLOAD"))
        .andReturn();
    JsonNode downloadCommand = MAPPER.readTree(download.getResponse().getContentAsString());
    String downloadCommandId = downloadCommand.get("commandId").asText();
    String downloadTransferId = MAPPER.readTree(downloadCommand.get("requestJson").asText()).get("transfer_id").asText();

    mockMvc.perform(post("/api/agent/file-manager/transfers/" + downloadTransferId + "/content")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
            .header("X-Castrelyx-Filename", "server.txt")
            .contentType("text/plain")
            .content("hello download"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.sizeBytes").value(14));
    completeCommand(clientCert, downloadCommandId, "server.txt");

    mockMvc.perform(get("/api/admin/file-manager/commands/" + downloadCommandId + "/download")
            .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(content().string("hello download"));
  }

  private void completeCommand(X509Certificate clientCert, String commandId, String filename) throws Exception {
    mockMvc.perform(post("/api/agent/file-manager/commands/" + commandId + "/result")
            .requestAttr(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {clientCert})
            .contentType("application/json")
            .content("""
                {"status":"SUCCEEDED","response":{"name":%s}}
                """.formatted(MAPPER.writeValueAsString(filename))))
        .andExpect(status().isAccepted());
  }

  private X509Certificate enrollCertificate(String agentId) throws Exception {
    String token = createEnrollmentToken(agentId);
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

  private String createEnrollmentToken(String agentId) throws Exception {
    MvcResult created = mockMvc.perform(post("/api/admin/enrollment-tokens")
            .header("Authorization", "Bearer admin-token")
            .contentType("application/json")
            .content("""
                {"name":%s,"agent_id":%s,"ttl_seconds":3600,"max_uses":1}
                """.formatted(MAPPER.writeValueAsString("token-" + agentId), MAPPER.writeValueAsString(agentId))))
        .andExpect(status().isOk())
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
