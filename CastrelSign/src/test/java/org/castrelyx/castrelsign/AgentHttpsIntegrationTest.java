package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.castrelyx.castrelsign.api.EnrollmentResponse;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentHttpsIntegrationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final char[] PASSWORD = "integration-pass".toCharArray();

  @TempDir
  static Path tempDir;

  @LocalServerPort
  int port;

  private static CertificateAuthority authority;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    Files.createDirectories(tempDir);
    authority = CertificateAuthority.initialize(
        tempDir.resolve("certs"),
        "localhost",
        Duration.ofDays(30),
        Duration.ofDays(3650));
    CertificateAuthority.KeyStoreFiles stores = authority.writeKeyStores(PASSWORD);

    registry.add("server.ssl.enabled", () -> "true");
    registry.add("server.ssl.key-store", () -> stores.keyStorePath().toString());
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-store-password", () -> String.valueOf(PASSWORD));
    registry.add("server.ssl.trust-store", () -> stores.trustStorePath().toString());
    registry.add("server.ssl.trust-store-type", () -> "PKCS12");
    registry.add("server.ssl.trust-store-password", () -> String.valueOf(PASSWORD));
    registry.add("server.ssl.client-auth", () -> "want");
    registry.add("castrelsign.data-dir", () -> tempDir.toString());
    registry.add("castrelsign.public-base-url", () -> "https://localhost");
    registry.add("castrelsign.enrollment-token", () -> "integration-token");
    registry.add("castrelsign.admin-token", () -> "integration-admin-token");
    registry.add("castrelsign.tls-server-name", () -> "localhost");
  }

  @Test
  void enrollThenIngestOverHttpsWithClientCertificate() throws Exception {
    KeyPair agentKeyPair = ecKeyPair();
    String csrPem = csr("agent-https", agentKeyPair);
    HttpClient enrollClient = httpClient(trustOnlyContext());
    String enrollmentToken = createEnrollmentToken(enrollClient, "agent-https");
    String enrollJson = """
        {
          "agent_id": "agent-https",
          "hostname": "host-https",
          "version": "0.1.0",
          "csr_pem": %s
        }
        """.formatted(MAPPER.writeValueAsString(csrPem));

    HttpResponse<String> enrollResponse = enrollClient.send(HttpRequest.newBuilder()
        .uri(URI.create("https://localhost:" + port + "/api/agent/enroll"))
        .header("Authorization", "Bearer " + enrollmentToken)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(enrollJson))
        .build(), HttpResponse.BodyHandlers.ofString());

    assertThat(enrollResponse.statusCode()).isEqualTo(200);
    EnrollmentResponse enrollment = MAPPER.readValue(enrollResponse.body(), EnrollmentResponse.class);
    assertThat(enrollment.agentId()).isEqualTo("agent-https");
    assertThat(enrollment.caCertPem()).isNotBlank();
    assertThat(enrollment.clientCertPem()).isNotBlank();

    X509Certificate clientCertificate = certificate(enrollment.clientCertPem());
    HttpClient ingestClient = httpClient(clientContext(agentKeyPair, clientCertificate));
    byte[] body = gzip("""
        {
          "schema_version": "1.0",
          "source": "agent",
          "source_id": "agent-https",
          "tenant_id": "default",
          "items": [
            {"kind": "event", "type": "health", "key": "heartbeat", "payload": {"status": "ok"}}
          ]
        }
        """);

    HttpResponse<String> ingestResponse = ingestClient.send(HttpRequest.newBuilder()
        .uri(URI.create("https://localhost:" + port + "/api/agent/ingest"))
        .header("Content-Type", "application/json")
        .header("Content-Encoding", "gzip")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build(), HttpResponse.BodyHandlers.ofString());

    assertThat(ingestResponse.statusCode()).isEqualTo(202);
  }

  private String createEnrollmentToken(HttpClient client, String agentId) throws Exception {
    HttpResponse<String> response = client.send(HttpRequest.newBuilder()
        .uri(URI.create("https://localhost:" + port + "/api/admin/enrollment-tokens"))
        .header("Authorization", "Bearer integration-admin-token")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""
            {"name":"integration","agent_id":%s,"ttl_seconds":3600,"max_uses":1}
            """.formatted(MAPPER.writeValueAsString(agentId))))
        .build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body()).get("token").asText();
  }

  private static HttpClient httpClient(SSLContext sslContext) {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .sslContext(sslContext)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  private static SSLContext trustOnlyContext() throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers(), new SecureRandom());
    return context;
  }

  private static SSLContext clientContext(KeyPair clientKeyPair, X509Certificate clientCertificate) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, PASSWORD);
    keyStore.setKeyEntry("agent", clientKeyPair.getPrivate(), PASSWORD,
        new java.security.cert.Certificate[] {clientCertificate, authority.rootCertificate()});
    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, PASSWORD);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagerFactory.getKeyManagers(), trustManagers(), new SecureRandom());
    return context;
  }

  private static javax.net.ssl.TrustManager[] trustManagers() throws Exception {
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, PASSWORD);
    trustStore.setCertificateEntry("root", authority.rootCertificate());
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);
    return trustManagerFactory.getTrustManagers();
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
        .generateCertificate(new ByteArrayInputStream(der));
  }

  private static byte[] gzip(String value) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }
}
