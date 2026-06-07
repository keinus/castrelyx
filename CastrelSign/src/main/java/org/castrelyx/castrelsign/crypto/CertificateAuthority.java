package org.castrelyx.castrelsign.crypto;

import java.io.IOException;
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
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.castrelyx.castrelsign.crypto.CsrService.ParsedCsr;

public class CertificateAuthority {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path certDir;
  private final X509Certificate rootCertificate;
  private final PrivateKey rootPrivateKey;
  private final X509Certificate serverCertificate;
  private final PrivateKey serverPrivateKey;

  private CertificateAuthority(Path certDir, X509Certificate rootCertificate, PrivateKey rootPrivateKey,
      X509Certificate serverCertificate, PrivateKey serverPrivateKey) {
    this.certDir = certDir;
    this.rootCertificate = rootCertificate;
    this.rootPrivateKey = rootPrivateKey;
    this.serverCertificate = serverCertificate;
    this.serverPrivateKey = serverPrivateKey;
  }

  public static CertificateAuthority initialize(Path certDir, String serverName, Duration certValidFor, Duration rootValidFor) throws Exception {
    BouncyCastleSupport.install();
    Files.createDirectories(certDir);

    Path rootCertPath = certDir.resolve("ca.pem");
    Path rootKeyPath = certDir.resolve("ca.key");
    Path serverCertPath = certDir.resolve("server.pem");
    Path serverKeyPath = certDir.resolve("server.key");

    X509Certificate rootCert;
    PrivateKey rootKey;
    if (Files.exists(rootCertPath) && Files.exists(rootKeyPath)) {
      rootCert = readCertificate(rootCertPath);
      rootKey = readPrivateKey(rootKeyPath);
    } else {
      KeyPair root = ecKeyPair();
      rootCert = selfSignedRoot(root, rootValidFor);
      rootKey = root.getPrivate();
      writeRestricted(rootCertPath, PemUtil.certificateToPem(rootCert));
      writeRestricted(rootKeyPath, PemUtil.privateKeyToPem(rootKey));
    }

    X509Certificate serverCert;
    PrivateKey serverKey;
    if (Files.exists(serverCertPath) && Files.exists(serverKeyPath)) {
      serverCert = readCertificate(serverCertPath);
      serverKey = readPrivateKey(serverKeyPath);
    } else {
      KeyPair server = ecKeyPair();
      serverCert = signedServerCertificate(rootCert, rootKey, server, serverName, certValidFor);
      serverKey = server.getPrivate();
      writeRestricted(serverCertPath, PemUtil.certificateToPem(serverCert));
      writeRestricted(serverKeyPath, PemUtil.privateKeyToPem(serverKey));
    }

    return new CertificateAuthority(certDir, rootCert, rootKey, serverCert, serverKey);
  }

  public X509Certificate rootCertificate() {
    return rootCertificate;
  }

  public String rootCertificatePem() {
    return PemUtil.certificateToPem(rootCertificate);
  }

  public X509Certificate signAgentCertificate(ParsedCsr csr, Duration validFor) {
    try {
      Instant now = Instant.now();
      X500Name issuer = issuerName(rootCertificate);
      X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
          issuer,
          serial(),
          Date.from(now.minusSeconds(60)),
          Date.from(now.plus(validFor)),
          csr.request().getSubject(),
          csr.publicKey());
      addCommonLeafExtensions(builder, rootCertificate, csr.publicKey(), false);
      builder.addExtension(Extension.extendedKeyUsage, false,
          new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
      if (csr.subjectAlternativeNames() != null) {
        builder.addExtension(Extension.subjectAlternativeName, false, csr.subjectAlternativeNames());
      }
      return convert(builder.build(signer(rootPrivateKey)));
    } catch (Exception e) {
      throw new IllegalStateException("failed to sign agent certificate", e);
    }
  }

  public KeyStoreFiles writeKeyStores(char[] password) throws Exception {
    Path keyStorePath = certDir.resolve("server.p12");
    Path trustStorePath = certDir.resolve("truststore.p12");

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, password);
    keyStore.setKeyEntry("castrelsign-server", serverPrivateKey, password,
        new java.security.cert.Certificate[] {serverCertificate, rootCertificate});
    try (var out = Files.newOutputStream(keyStorePath)) {
      keyStore.store(out, password);
    }

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, password);
    trustStore.setCertificateEntry("castrelsign-root", rootCertificate);
    try (var out = Files.newOutputStream(trustStorePath)) {
      trustStore.store(out, password);
    }
    return new KeyStoreFiles(keyStorePath, trustStorePath);
  }

  private static X509Certificate selfSignedRoot(KeyPair keyPair, Duration validFor) throws Exception {
    Instant now = Instant.now();
    X500Name subject = new X500Name("CN=CastrelSign Root CA,O=Castrelyx,OU=CastrelSign");
    X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        subject,
        serial(),
        Date.from(now.minusSeconds(60)),
        Date.from(now.plus(validFor)),
        subject,
        keyPair.getPublic());
    JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keyPair.getPublic()));
    X509Certificate cert = convert(builder.build(signer(keyPair.getPrivate())));
    cert.verify(keyPair.getPublic());
    return cert;
  }

  private static X509Certificate signedServerCertificate(X509Certificate rootCert, PrivateKey rootKey, KeyPair serverKeyPair,
      String serverName, Duration validFor) throws Exception {
    Instant now = Instant.now();
    X500Name issuer = issuerName(rootCert);
    X500Name subject = new X500Name("CN=" + serverName + ",O=Castrelyx,OU=CastrelSign");
    X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        issuer,
        serial(),
        Date.from(now.minusSeconds(60)),
        Date.from(now.plus(validFor)),
        subject,
        serverKeyPair.getPublic());
    addCommonLeafExtensions(builder, rootCert, serverKeyPair.getPublic(), true);
    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
    builder.addExtension(Extension.subjectAlternativeName, false, serverNames(serverName));
    X509Certificate cert = convert(builder.build(signer(rootKey)));
    cert.verify(rootCert.getPublicKey());
    return cert;
  }

  private static X500Name issuerName(X509Certificate certificate) {
    return X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
  }

  private static void addCommonLeafExtensions(X509v3CertificateBuilder builder, X509Certificate issuerCert,
      java.security.PublicKey subjectPublicKey, boolean server) throws Exception {
    JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | (server ? KeyUsage.keyEncipherment : 0)));
    builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(issuerCert));
    builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(subjectPublicKey));
  }

  private static GeneralNames serverNames(String serverName) {
    return new GeneralNames(new GeneralName[] {
        new GeneralName(GeneralName.dNSName, serverName),
        new GeneralName(GeneralName.dNSName, "localhost"),
        new GeneralName(GeneralName.iPAddress, "127.0.0.1")
    });
  }

  private static KeyPair ecKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static ContentSigner signer(PrivateKey key) throws Exception {
    return new JcaContentSignerBuilder("SHA256withECDSA")
        .setProvider(BouncyCastleSupport.PROVIDER)
        .build(key);
  }

  private static X509Certificate convert(org.bouncycastle.cert.X509CertificateHolder holder) throws Exception {
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleSupport.PROVIDER)
        .getCertificate(holder);
  }

  private static BigInteger serial() {
    return new BigInteger(160, RANDOM).abs();
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
      throw new IOException("unsupported private key PEM");
    }
  }

  private static void writeRestricted(Path path, String content) throws IOException {
    Files.writeString(path, content);
    try {
      var permissions = java.util.Set.of(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Windows ACLs are inherited from the data directory in this local deployment.
    }
  }

  public record KeyStoreFiles(Path keyStorePath, Path trustStorePath) {
  }
}
