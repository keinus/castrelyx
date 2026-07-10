package org.castrelyx.castrelsign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.castrelyx.castrelsign.crypto.BouncyCastleSupport;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.castrelyx.castrelsign.crypto.CsrService;
import org.castrelyx.castrelsign.crypto.PemUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateAuthorityTest {
  @TempDir
  Path tempDir;

  @BeforeAll
  static void installBouncyCastle() {
    BouncyCastleSupport.install();
  }

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
  void reusesHealthyServerMaterialOutsideRenewalWindow() throws Exception {
    CertificateAuthority created = CertificateAuthority.initialize(tempDir, "localhost", Duration.ofDays(30), Duration.ofDays(3650));
    byte[] rootCertificate = Files.readAllBytes(tempDir.resolve("ca.pem"));
    byte[] rootPrivateKey = Files.readAllBytes(tempDir.resolve("ca.key"));
    byte[] serverCertificate = Files.readAllBytes(tempDir.resolve("server.pem"));
    byte[] serverPrivateKey = Files.readAllBytes(tempDir.resolve("server.key"));

    CertificateAuthority loaded = CertificateAuthority.initialize(tempDir, "localhost", Duration.ofDays(30), Duration.ofDays(3650));

    assertThat(created.serverMaterialChanged()).isTrue();
    assertThat(loaded.serverMaterialChanged()).isFalse();
    assertThat(loaded.rootCertificatePem()).contains("BEGIN CERTIFICATE");
    assertThat(Files.readAllBytes(tempDir.resolve("ca.pem"))).containsExactly(rootCertificate);
    assertThat(Files.readAllBytes(tempDir.resolve("ca.key"))).containsExactly(rootPrivateKey);
    assertThat(Files.readAllBytes(tempDir.resolve("server.pem"))).containsExactly(serverCertificate);
    assertThat(Files.readAllBytes(tempDir.resolve("server.key"))).containsExactly(serverPrivateKey);
    assertThat(tempDir.resolve("server.pem.bak")).doesNotExist();
    assertThat(tempDir.resolve("server.key.bak")).doesNotExist();
  }

  @Test
  void rotatesExpiredServerCertificateRebuildsKeyStoreAndPreservesRootCa() throws Exception {
    char[] password = "test-password".toCharArray();
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(3650));
    byte[] rootCertificateBytes = Files.readAllBytes(tempDir.resolve("ca.pem"));
    byte[] rootPrivateKeyBytes = Files.readAllBytes(tempDir.resolve("ca.key"));
    PrivateKey rootPrivateKey = readPrivateKey(tempDir.resolve("ca.key"));
    KeyPair expiredKeyPair = ecKeyPair();
    Instant now = Instant.now();
    X509Certificate expiredCertificate = testServerCertificate(
        authority.rootCertificate(),
        rootPrivateKey,
        expiredKeyPair,
        "manager.local",
        now.minus(Duration.ofDays(20)),
        now.minus(Duration.ofDays(1)),
        true,
        expectedServerNames("manager.local"));
    writeServerMaterial(tempDir, expiredCertificate, expiredKeyPair.getPrivate());
    writeServerKeyStore(tempDir.resolve("server.p12"), password, expiredKeyPair.getPrivate(), expiredCertificate,
        authority.rootCertificate());

    CertificateAuthority renewed = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(3650));
    CertificateAuthority.KeyStoreFiles stores = renewed.writeKeyStores(password);

    X509Certificate renewedCertificate = readCertificate(tempDir.resolve("server.pem"));
    renewedCertificate.checkValidity();
    renewedCertificate.verify(renewed.rootCertificate().getPublicKey());
    assertThat(renewedCertificate.getSerialNumber()).isNotEqualTo(expiredCertificate.getSerialNumber());
    assertThat(renewedCertificate.getNotAfter().toInstant()).isAfter(now.plus(Duration.ofDays(20)));
    assertThat(Files.readAllBytes(tempDir.resolve("ca.pem"))).containsExactly(rootCertificateBytes);
    assertThat(Files.readAllBytes(tempDir.resolve("ca.key"))).containsExactly(rootPrivateKeyBytes);
    assertThat(readCertificate(tempDir.resolve("server.pem.bak")).getSerialNumber())
        .isEqualTo(expiredCertificate.getSerialNumber());
    assertThat(tempDir.resolve("server.key.bak")).exists();
    assertThat(tempDir.resolve("server.p12.bak")).exists();

    KeyStore keyStore = loadKeyStore(stores.keyStorePath(), password);
    X509Certificate keyStoreCertificate = (X509Certificate) keyStore.getCertificate("castrelsign-server");
    assertThat(keyStoreCertificate.getSerialNumber()).isEqualTo(renewedCertificate.getSerialNumber());
    assertThat(((X509Certificate) keyStore.getCertificateChain("castrelsign-server")[1]).getSerialNumber())
        .isEqualTo(renewed.rootCertificate().getSerialNumber());
  }

  @Test
  void rotatesServerCertificateInsideConfiguredRenewalWindowAndPreservesRootCa() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(3650));
    byte[] rootCertificateBytes = Files.readAllBytes(tempDir.resolve("ca.pem"));
    byte[] rootPrivateKeyBytes = Files.readAllBytes(tempDir.resolve("ca.key"));
    KeyPair expiringKeyPair = ecKeyPair();
    Instant now = Instant.now();
    X509Certificate expiringCertificate = testServerCertificate(
        authority.rootCertificate(),
        readPrivateKey(tempDir.resolve("ca.key")),
        expiringKeyPair,
        "manager.local",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(5)),
        true,
        expectedServerNames("manager.local"));
    writeServerMaterial(tempDir, expiringCertificate, expiringKeyPair.getPrivate());

    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(3650));

    X509Certificate renewedCertificate = readCertificate(tempDir.resolve("server.pem"));
    assertThat(renewedCertificate.getSerialNumber()).isNotEqualTo(expiringCertificate.getSerialNumber());
    assertThat(renewedCertificate.getNotAfter().toInstant()).isAfter(now.plus(Duration.ofDays(20)));
    assertThat(Files.readAllBytes(tempDir.resolve("ca.pem"))).containsExactly(rootCertificateBytes);
    assertThat(Files.readAllBytes(tempDir.resolve("ca.key"))).containsExactly(rootPrivateKeyBytes);
  }

  @Test
  void rotatesServerCertificateWhenPrivateKeyDoesNotMatch() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    X509Certificate originalCertificate = readCertificate(tempDir.resolve("server.pem"));
    byte[] rootCertificateBytes = Files.readAllBytes(tempDir.resolve("ca.pem"));
    Files.writeString(tempDir.resolve("server.key"), PemUtil.privateKeyToPem(ecKeyPair().getPrivate()));

    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));

    X509Certificate renewedCertificate = readCertificate(tempDir.resolve("server.pem"));
    renewedCertificate.verify(authority.rootCertificate().getPublicKey());
    assertThat(renewedCertificate.getSerialNumber()).isNotEqualTo(originalCertificate.getSerialNumber());
    assertThat(Files.readAllBytes(tempDir.resolve("ca.pem"))).containsExactly(rootCertificateBytes);
  }

  @Test
  void rotatesServerCertificatesMissingExpectedSanOrServerAuth() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    PrivateKey rootPrivateKey = readPrivateKey(tempDir.resolve("ca.key"));
    Instant now = Instant.now();

    KeyPair wrongSanKeyPair = ecKeyPair();
    X509Certificate wrongSanCertificate = testServerCertificate(
        authority.rootCertificate(),
        rootPrivateKey,
        wrongSanKeyPair,
        "manager.local",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(60)),
        true,
        expectedServerNames("wrong.local"));
    writeServerMaterial(tempDir, wrongSanCertificate, wrongSanKeyPair.getPrivate());
    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    assertThat(readCertificate(tempDir.resolve("server.pem")).getSerialNumber())
        .isNotEqualTo(wrongSanCertificate.getSerialNumber());

    KeyPair missingServerAuthKeyPair = ecKeyPair();
    X509Certificate missingServerAuthCertificate = testServerCertificate(
        authority.rootCertificate(),
        rootPrivateKey,
        missingServerAuthKeyPair,
        "manager.local",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(60)),
        false,
        expectedServerNames("manager.local"));
    writeServerMaterial(tempDir, missingServerAuthCertificate, missingServerAuthKeyPair.getPrivate());
    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    assertThat(readCertificate(tempDir.resolve("server.pem")).getSerialNumber())
        .isNotEqualTo(missingServerAuthCertificate.getSerialNumber());
  }

  @Test
  void rotatesServerCertificateThatDoesNotChainToPreservedRoot() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    byte[] rootCertificateBytes = Files.readAllBytes(tempDir.resolve("ca.pem"));
    Path unrelatedDir = tempDir.resolve("unrelated");
    CertificateAuthority unrelated = CertificateAuthority.initialize(
        unrelatedDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    KeyPair unrelatedServerKeyPair = ecKeyPair();
    Instant now = Instant.now();
    X509Certificate unrelatedCertificate = testServerCertificate(
        unrelated.rootCertificate(),
        readPrivateKey(unrelatedDir.resolve("ca.key")),
        unrelatedServerKeyPair,
        "manager.local",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(60)),
        true,
        expectedServerNames("manager.local"));
    writeServerMaterial(tempDir, unrelatedCertificate, unrelatedServerKeyPair.getPrivate());

    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));

    X509Certificate renewedCertificate = readCertificate(tempDir.resolve("server.pem"));
    renewedCertificate.verify(authority.rootCertificate().getPublicKey());
    assertThat(renewedCertificate.getSerialNumber()).isNotEqualTo(unrelatedCertificate.getSerialNumber());
    assertThat(Files.readAllBytes(tempDir.resolve("ca.pem"))).containsExactly(rootCertificateBytes);
  }

  @Test
  void reusesHealthyExistingLeafBeforeEnforcingLongerConfiguredValidity() throws Exception {
    CertificateAuthority initial = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(15), Duration.ofDays(20));
    X509Certificate before = readCertificate(tempDir.resolve("server.pem"));

    CertificateAuthority reloaded = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(30), Duration.ofDays(20));

    assertThat(initial.serverMaterialChanged()).isTrue();
    assertThat(reloaded.serverMaterialChanged()).isFalse();
    assertThat(readCertificate(tempDir.resolve("server.pem")).getSerialNumber())
        .isEqualTo(before.getSerialNumber());
  }

  @Test
  void rotatesMatchingSecp256k1ServerPairInsteadOfAcceptingItAsP256() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleSupport.PROVIDER);
    generator.initialize(new ECGenParameterSpec("secp256k1"));
    KeyPair secp256k1 = generator.generateKeyPair();
    Instant now = Instant.now();
    X509Certificate wrongCurve = testServerCertificate(
        authority.rootCertificate(),
        readPrivateKey(tempDir.resolve("ca.key")),
        secp256k1,
        "manager.local",
        now.minus(Duration.ofDays(1)),
        now.plus(Duration.ofDays(60)),
        true,
        expectedServerNames("manager.local"));
    writeServerMaterial(tempDir, wrongCurve, secp256k1.getPrivate());

    CertificateAuthority refreshed = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));

    assertThat(refreshed.serverMaterialChanged()).isTrue();
    assertThat(readCertificate(tempDir.resolve("server.pem")).getSerialNumber())
        .isNotEqualTo(wrongCurve.getSerialNumber());
  }

  @Test
  void recoversInterruptedServerPairFromCompleteBackups() throws Exception {
    CertificateAuthority.initialize(tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));
    byte[] originalCertificate = Files.readAllBytes(tempDir.resolve("server.pem"));
    byte[] originalPrivateKey = Files.readAllBytes(tempDir.resolve("server.key"));
    Files.write(tempDir.resolve("server.pem.bak"), originalCertificate);
    Files.write(tempDir.resolve("server.key.bak"), originalPrivateKey);
    Files.writeString(tempDir.resolve("server.key"), PemUtil.privateKeyToPem(ecKeyPair().getPrivate()));
    Files.writeString(tempDir.resolve(".server.pem.pair-update"), "in-progress\n");

    CertificateAuthority recovered = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(90), Duration.ofDays(3650));

    assertThat(recovered.serverMaterialChanged()).isFalse();
    assertThat(Files.readAllBytes(tempDir.resolve("server.pem"))).containsExactly(originalCertificate);
    assertThat(Files.readAllBytes(tempDir.resolve("server.key"))).containsExactly(originalPrivateKey);
    assertThat(tempDir.resolve(".server.pem.pair-update")).doesNotExist();
  }

  @Test
  void refusesToIssueLeafBeyondPreservedRootExpiry() throws Exception {
    CertificateAuthority authority = CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(2), Duration.ofDays(5));

    assertThatThrownBy(() -> CertificateAuthority.initialize(
        tempDir, "manager.local", Duration.ofDays(10), Duration.ofDays(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit trust migration");

    String csrPem = csr("agent-01", ecKeyPair());
    assertThatThrownBy(() -> authority.signAgentCertificate(
        new CsrService().parseAndValidate(csrPem, "agent-01"), Duration.ofDays(10)))
        .isInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("root CA expires before the requested agent certificate; perform an explicit trust migration before issuing new certificates");
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

  private static X509Certificate testServerCertificate(X509Certificate issuerCertificate, PrivateKey issuerPrivateKey,
      KeyPair serverKeyPair, String serverName, Instant notBefore, Instant notAfter, boolean includeServerAuth,
      GeneralNames subjectAlternativeNames) throws Exception {
    X500Name issuer = X500Name.getInstance(issuerCertificate.getSubjectX500Principal().getEncoded());
    X500Name subject = new X500Name("CN=" + serverName + ",O=Castrelyx,OU=CastrelSign");
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        issuer,
        new BigInteger(160, new SecureRandom()).abs(),
        Date.from(notBefore),
        Date.from(notAfter),
        subject,
        serverKeyPair.getPublic());
    JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    builder.addExtension(Extension.authorityKeyIdentifier, false,
        extensionUtils.createAuthorityKeyIdentifier(issuerCertificate));
    builder.addExtension(Extension.subjectKeyIdentifier, false,
        extensionUtils.createSubjectKeyIdentifier(serverKeyPair.getPublic()));
    if (includeServerAuth) {
      builder.addExtension(Extension.extendedKeyUsage, false,
          new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
    }
    builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNames);
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleSupport.PROVIDER)
        .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleSupport.PROVIDER)
            .build(issuerPrivateKey)));
  }

  private static GeneralNames expectedServerNames(String serverName) {
    return new GeneralNames(new GeneralName[] {
        new GeneralName(GeneralName.dNSName, serverName),
        new GeneralName(GeneralName.dNSName, "localhost"),
        new GeneralName(GeneralName.iPAddress, "127.0.0.1")
    });
  }

  private static void writeServerMaterial(Path directory, X509Certificate certificate, PrivateKey privateKey)
      throws Exception {
    Files.writeString(directory.resolve("server.pem"), PemUtil.certificateToPem(certificate));
    Files.writeString(directory.resolve("server.key"), PemUtil.privateKeyToPem(privateKey));
  }

  private static void writeServerKeyStore(Path path, char[] password, PrivateKey privateKey,
      X509Certificate certificate, X509Certificate rootCertificate) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, password);
    keyStore.setKeyEntry("castrelsign-server", privateKey, password,
        new java.security.cert.Certificate[] {certificate, rootCertificate});
    try (var output = Files.newOutputStream(path)) {
      keyStore.store(output, password);
    }
  }

  private static KeyStore loadKeyStore(Path path, char[] password) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(path)) {
      keyStore.load(input, password);
    }
    return keyStore;
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private static PrivateKey readPrivateKey(Path path) throws Exception {
    try (PEMParser parser = new PEMParser(new StringReader(Files.readString(path)))) {
      Object object = parser.readObject();
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BouncyCastleSupport.PROVIDER);
      if (object instanceof PEMKeyPair pair) {
        return converter.getPrivateKey(pair.getPrivateKeyInfo());
      }
      if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo privateKeyInfo) {
        return converter.getPrivateKey(privateKeyInfo);
      }
      throw new IllegalArgumentException("unsupported private key PEM");
    }
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
