package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.castrelyx.castrelsign.crypto.CsrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateAuthorityTest {
  @TempDir
  Path tempDir;

  @Test
  void signsClientAuthCertificateFromAgentCsrWithoutServerGeneratingPrivateKey() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(3650));
    KeyPair agentKeyPair = ecKeyPair();
    String csrPem = csr("agent-01", agentKeyPair);

    X509Certificate clientCert = authority.signAgentCertificate(new CsrService().parseAndValidate(csrPem, "agent-01"), Duration.ofDays(30));

    assertThat(clientCert.getSubjectX500Principal().getName()).contains("CN=agent-01");
    assertThat(clientCert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.2");
    assertThat(clientCert.getIssuerX500Principal().getName()).contains("CN=CastrelSign Root CA");
    clientCert.verify(authority.rootCertificate().getPublicKey());
    assertThat(clientCert.getPublicKey()).isInstanceOf(ECPublicKey.class);
    assertThat(clientCert.getPublicKey()).isEqualTo(agentKeyPair.getPublic());
  }

  @Test
  void writesValidServerKeyStoreAndTrustStore() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(tempDir, "localhost", Duration.ofDays(30), Duration.ofDays(3650));

    CertificateAuthority.KeyStoreFiles stores = authority.writeKeyStores("test-password".toCharArray());

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var input = java.nio.file.Files.newInputStream(stores.keyStorePath())) {
      keyStore.load(input, "test-password".toCharArray());
    }
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    try (var input = java.nio.file.Files.newInputStream(stores.trustStorePath())) {
      trustStore.load(input, "test-password".toCharArray());
    }

    assertThat(keyStore.containsAlias("castrelsign-server")).isTrue();
    assertThat(trustStore.containsAlias("castrelsign-root")).isTrue();
  }

  @Test
  void loadsExistingCaAndServerPrivateKeys() throws Exception {
    CertificateAuthority.initialize(tempDir, "localhost", Duration.ofDays(30), Duration.ofDays(3650));

    CertificateAuthority loaded = CertificateAuthority.initialize(tempDir, "localhost", Duration.ofDays(30), Duration.ofDays(3650));

    assertThat(loaded.rootCertificatePem()).contains("BEGIN CERTIFICATE");
  }

  @Test
  void rejectsCsrWhenCommonNameDoesNotMatchAgentId() throws Exception {
    String csrPem = csr("other-agent", ecKeyPair());

    assertThatThrownBy(() -> new CsrService().parseAndValidate(csrPem, "agent-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CSR common name must match agent_id");
  }

  @Test
  void rejectsMalformedCsrPem() {
    assertThatThrownBy(() -> new CsrService().parseAndValidate("not a csr", "agent-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failed to parse csr_pem");
  }

  @Test
  void rejectsCsrWithoutCommonName() throws Exception {
    String csrPem = csrWithSubject("O=Castrelyx,OU=agent", ecKeyPair());

    assertThatThrownBy(() -> new CsrService().parseAndValidate(csrPem, "agent-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CSR common name is required");
  }

  private static KeyPair ecKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String csr(String commonName, KeyPair keyPair) throws Exception {
    return csrWithSubject("CN=" + commonName + ",O=Castrelyx,OU=agent", keyPair);
  }

  private static String csrWithSubject(String subject, KeyPair keyPair) throws Exception {
    PKCS10CertificationRequest request = new JcaPKCS10CertificationRequestBuilder(
        new X500Name(subject), keyPair.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate()));
    return PemFiles.toPem("CERTIFICATE REQUEST", request.getEncoded());
  }
}
