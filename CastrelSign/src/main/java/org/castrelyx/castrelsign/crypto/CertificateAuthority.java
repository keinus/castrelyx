package org.castrelyx.castrelsign.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.AlgorithmParameters;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

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
  private static final Duration MAX_SERVER_RENEWAL_WINDOW = Duration.ofDays(30);
  private static final String SERVER_AUTH_OID = KeyPurposeId.id_kp_serverAuth.getId();
  private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_READ_WRITE = Set.of(
      java.nio.file.attribute.PosixFilePermission.OWNER_READ,
      java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);

  private final Path certDir;
  private final X509Certificate rootCertificate;
  private final PrivateKey rootPrivateKey;
  private final X509Certificate serverCertificate;
  private final PrivateKey serverPrivateKey;
  private final boolean serverMaterialChanged;

  private CertificateAuthority(Path certDir, X509Certificate rootCertificate, PrivateKey rootPrivateKey,
      X509Certificate serverCertificate, PrivateKey serverPrivateKey, boolean serverMaterialChanged) {
    this.certDir = certDir;
    this.rootCertificate = rootCertificate;
    this.rootPrivateKey = rootPrivateKey;
    this.serverCertificate = serverCertificate;
    this.serverPrivateKey = serverPrivateKey;
    this.serverMaterialChanged = serverMaterialChanged;
  }

  public static CertificateAuthority initialize(Path certDir, String serverName, Duration certValidFor, Duration rootValidFor) throws Exception {
    BouncyCastleSupport.install();
    requirePositive(certValidFor, "certificate validity");
    requirePositive(rootValidFor, "root certificate validity");
    if (serverName == null || serverName.isBlank()) {
      throw new IllegalArgumentException("server name is required");
    }
    Files.createDirectories(certDir);

    Path lockPath = certDir.resolve(".certificate-authority.lock");
    try (FileChannel lockChannel = FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
        FileLock ignored = lockChannel.lock()) {
      setRestrictedPermissions(lockPath);
      return initializeLocked(certDir, serverName, certValidFor, rootValidFor);
    }
  }

  public static RefreshResult refreshServerMaterial(
      Path certDir,
      String serverName,
      Duration certValidFor,
      Duration rootValidFor,
      char[] keyStorePassword) throws Exception {
    BouncyCastleSupport.install();
    requirePositive(certValidFor, "certificate validity");
    requirePositive(rootValidFor, "root certificate validity");
    if (serverName == null || serverName.isBlank()) {
      throw new IllegalArgumentException("server name is required");
    }
    if (keyStorePassword == null || keyStorePassword.length == 0) {
      throw new IllegalArgumentException("key store password is required");
    }
    Files.createDirectories(certDir);

    Path lockPath = certDir.resolve(".certificate-authority.lock");
    try (FileChannel lockChannel = FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
        FileLock ignored = lockChannel.lock()) {
      setRestrictedPermissions(lockPath);
      CertificateAuthority authority = initializeLocked(certDir, serverName, certValidFor, rootValidFor);
      boolean keyStoresChanged = authority.serverMaterialChanged || !authority.keyStoresMatch(keyStorePassword);
      KeyStoreFiles stores = keyStoresChanged
          ? authority.writeKeyStoresLocked(keyStorePassword)
          : new KeyStoreFiles(certDir.resolve("server.p12"), certDir.resolve("truststore.p12"));
      return new RefreshResult(authority, stores, keyStoresChanged);
    }
  }

  private static CertificateAuthority initializeLocked(
      Path certDir,
      String serverName,
      Duration certValidFor,
      Duration rootValidFor) throws Exception {

    Path rootCertPath = certDir.resolve("ca.pem");
    Path rootKeyPath = certDir.resolve("ca.key");
    Path serverCertPath = certDir.resolve("server.pem");
    Path serverKeyPath = certDir.resolve("server.key");

    recoverInterruptedPair(rootCertPath, rootKeyPath);
    recoverInterruptedPair(serverCertPath, serverKeyPath);

    boolean rootCertExists = Files.exists(rootCertPath);
    boolean rootKeyExists = Files.exists(rootKeyPath);
    X509Certificate rootCert;
    PrivateKey rootKey;
    if (rootCertExists != rootKeyExists) {
      throw new IllegalStateException("root CA material is incomplete; refusing to replace the existing root");
    }
    if (rootCertExists) {
      rootCert = readCertificate(rootCertPath);
      rootKey = readPrivateKey(rootKeyPath);
      validateRootMaterial(rootCert, rootKey);
    } else {
      KeyPair root = ecKeyPair();
      rootCert = selfSignedRoot(root, rootValidFor);
      rootKey = root.getPrivate();
      replaceMaterialPair(
          rootCertPath, PemUtil.certificateToPem(rootCert),
          rootKeyPath, PemUtil.privateKeyToPem(rootKey),
          false);
    }
    ServerMaterial serverMaterial = loadReusableServerMaterial(
        serverCertPath,
        serverKeyPath,
        rootCert,
        serverName,
        certValidFor,
        Instant.now());
    boolean serverMaterialChanged = serverMaterial == null;
    if (serverMaterial == null) {
      ensureRootCovers(rootCert, certValidFor, Instant.now(), "server certificate");
      KeyPair server = ecKeyPair();
      X509Certificate serverCert = signedServerCertificate(rootCert, rootKey, server, serverName, certValidFor);
      PrivateKey serverKey = server.getPrivate();
      replaceMaterialPair(
          serverCertPath, PemUtil.certificateToPem(serverCert),
          serverKeyPath, PemUtil.privateKeyToPem(serverKey),
          true);
      serverMaterial = new ServerMaterial(serverCert, serverKey);
    }

    return new CertificateAuthority(
        certDir,
        rootCert,
        rootKey,
        serverMaterial.certificate(),
        serverMaterial.privateKey(),
        serverMaterialChanged);
  }

  private static ServerMaterial loadReusableServerMaterial(Path certificatePath, Path privateKeyPath,
      X509Certificate rootCertificate, String serverName, Duration configuredValidity, Instant now) {
    if (!Files.exists(certificatePath) || !Files.exists(privateKeyPath)) {
      return null;
    }
    try {
      X509Certificate certificate = readCertificate(certificatePath);
      PrivateKey privateKey = readPrivateKey(privateKeyPath);
      validateServerMaterial(certificate, privateKey, rootCertificate, serverName, configuredValidity, now);
      return new ServerMaterial(certificate, privateKey);
    } catch (Exception invalidMaterial) {
      return null;
    }
  }

  private static void validateRootMaterial(X509Certificate certificate, PrivateKey privateKey) throws Exception {
    certificate.checkValidity();
    if (certificate.getBasicConstraints() < 0) {
      throw new IllegalArgumentException("root certificate is not a CA");
    }
    boolean[] keyUsage = certificate.getKeyUsage();
    if (keyUsage == null || keyUsage.length <= 5 || !keyUsage[5]) {
      throw new IllegalArgumentException("root certificate cannot sign certificates");
    }
    certificate.verify(certificate.getPublicKey());
    verifyMatchingEcKey(certificate, privateKey);
  }

  private static void validateServerMaterial(X509Certificate certificate, PrivateKey privateKey,
      X509Certificate rootCertificate, String serverName, Duration configuredValidity, Instant now) throws Exception {
    certificate.checkValidity(Date.from(now));
    Duration renewalWindow = serverRenewalWindow(configuredValidity);
    if (!certificate.getNotAfter().toInstant().isAfter(now.plus(renewalWindow))) {
      throw new IllegalArgumentException("server certificate is inside its renewal window");
    }
    if (!certificate.getIssuerX500Principal().equals(rootCertificate.getSubjectX500Principal())) {
      throw new IllegalArgumentException("server certificate issuer does not match the root CA");
    }
    if (certificate.getNotAfter().after(rootCertificate.getNotAfter())) {
      throw new IllegalArgumentException("server certificate expires after the root CA");
    }
    certificate.verify(rootCertificate.getPublicKey());
    verifyMatchingEcKey(certificate, privateKey);
    if (certificate.getBasicConstraints() >= 0) {
      throw new IllegalArgumentException("server certificate must be a leaf certificate");
    }
    boolean[] keyUsage = certificate.getKeyUsage();
    if (keyUsage == null || keyUsage.length <= 2 || !keyUsage[0] || !keyUsage[2]) {
      throw new IllegalArgumentException("server certificate is missing required key usage");
    }
    List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
    if (extendedKeyUsage == null || !extendedKeyUsage.contains(SERVER_AUTH_OID)) {
      throw new IllegalArgumentException("server certificate is missing serverAuth");
    }
    if (!hasExpectedServerNames(certificate, serverName)) {
      throw new IllegalArgumentException("server certificate is missing required subject alternative names");
    }
  }

  /**
   * Renew during the last third of the configured lifetime, capped at 30 days.
   * The default 30-day certificate therefore rotates when 10 days remain.
   */
  private static Duration serverRenewalWindow(Duration configuredValidity) {
    requirePositive(configuredValidity, "certificate validity");
    Duration fraction = configuredValidity.dividedBy(3);
    if (fraction.isZero()) {
      fraction = Duration.ofSeconds(1);
    }
    return fraction.compareTo(MAX_SERVER_RENEWAL_WINDOW) < 0 ? fraction : MAX_SERVER_RENEWAL_WINDOW;
  }

  private static boolean hasExpectedServerNames(X509Certificate certificate, String serverName) throws Exception {
    Collection<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
    if (alternativeNames == null) {
      return false;
    }
    Set<String> dnsNames = new HashSet<>();
    Set<String> ipAddresses = new HashSet<>();
    for (List<?> alternativeName : alternativeNames) {
      if (alternativeName.size() < 2 || !(alternativeName.get(0) instanceof Number type)) {
        continue;
      }
      String value = String.valueOf(alternativeName.get(1));
      if (type.intValue() == GeneralName.dNSName) {
        dnsNames.add(value.toLowerCase(Locale.ROOT));
      } else if (type.intValue() == GeneralName.iPAddress) {
        ipAddresses.add(value);
      }
    }
    return dnsNames.contains(serverName.toLowerCase(Locale.ROOT))
        && dnsNames.contains("localhost")
        && ipAddresses.contains("127.0.0.1");
  }

  private static void verifyMatchingEcKey(X509Certificate certificate, PrivateKey privateKey) throws Exception {
    if (!(certificate.getPublicKey() instanceof ECPublicKey publicKey)
        || !(privateKey instanceof ECPrivateKey ecPrivateKey)) {
      throw new IllegalArgumentException("certificate material must use an EC P-256 key");
    }
    ECParameterSpec expected = p256Parameters();
    if (!sameCurve(publicKey.getParams(), expected) || !sameCurve(ecPrivateKey.getParams(), expected)) {
      throw new IllegalArgumentException("certificate material must use the secp256r1 EC curve");
    }
    byte[] challenge = new byte[32];
    RANDOM.nextBytes(challenge);
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(privateKey, RANDOM);
    signer.update(challenge);
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(publicKey);
    verifier.update(challenge);
    if (!verifier.verify(signature)) {
      throw new IllegalArgumentException("private key does not match certificate public key");
    }
  }

  private static ECParameterSpec p256Parameters() throws Exception {
    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    return parameters.getParameterSpec(ECParameterSpec.class);
  }

  private static boolean sameCurve(ECParameterSpec left, ECParameterSpec right) {
    return left.getCurve().equals(right.getCurve())
        && left.getGenerator().equals(right.getGenerator())
        && left.getOrder().equals(right.getOrder())
        && left.getCofactor() == right.getCofactor();
  }

  private static void requirePositive(Duration duration, String label) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void ensureRootCovers(
      X509Certificate rootCertificate,
      Duration leafValidity,
      Instant now,
      String leafLabel) {
    if (rootCertificate.getNotAfter().toInstant().isBefore(now.plus(leafValidity))) {
      throw new IllegalStateException(
          "root CA expires before the requested " + leafLabel
              + "; perform an explicit trust migration before issuing new certificates");
    }
  }

  public X509Certificate rootCertificate() {
    return rootCertificate;
  }

  public boolean serverMaterialChanged() {
    return serverMaterialChanged;
  }

  public String rootCertificatePem() {
    return PemUtil.certificateToPem(rootCertificate);
  }

  public X509Certificate signAgentCertificate(ParsedCsr csr, Duration validFor) {
    try {
      requirePositive(validFor, "agent certificate validity");
      Instant now = Instant.now();
      ensureRootCovers(rootCertificate, validFor, now, "agent certificate");
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
    Path lockPath = certDir.resolve(".certificate-authority.lock");
    try (FileChannel lockChannel = FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
        FileLock ignored = lockChannel.lock()) {
      setRestrictedPermissions(lockPath);
      return writeKeyStoresLocked(password);
    }
  }

  private KeyStoreFiles writeKeyStoresLocked(char[] password) throws Exception {
    Path keyStorePath = certDir.resolve("server.p12");
    Path trustStorePath = certDir.resolve("truststore.p12");

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, password);
    keyStore.setKeyEntry("castrelsign-server", serverPrivateKey, password,
        new java.security.cert.Certificate[] {serverCertificate, rootCertificate});
    byte[] encodedKeyStore;
    try (var out = new ByteArrayOutputStream()) {
      keyStore.store(out, password);
      encodedKeyStore = out.toByteArray();
    }
    replaceWithBackup(keyStorePath, encodedKeyStore);

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, password);
    trustStore.setCertificateEntry("castrelsign-root", rootCertificate);
    byte[] encodedTrustStore;
    try (var out = new ByteArrayOutputStream()) {
      trustStore.store(out, password);
      encodedTrustStore = out.toByteArray();
    }
    replaceWithBackup(trustStorePath, encodedTrustStore);
    return new KeyStoreFiles(keyStorePath, trustStorePath);
  }

  private boolean keyStoresMatch(char[] password) {
    try {
      Path keyStorePath = certDir.resolve("server.p12");
      Path trustStorePath = certDir.resolve("truststore.p12");
      if (!Files.isRegularFile(keyStorePath) || !Files.isRegularFile(trustStorePath)) {
        return false;
      }

      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(keyStorePath)) {
        keyStore.load(input, password);
      }
      X509Certificate storedServer = (X509Certificate) keyStore.getCertificate("castrelsign-server");
      PrivateKey storedPrivateKey = (PrivateKey) keyStore.getKey("castrelsign-server", password);
      java.security.cert.Certificate[] chain = keyStore.getCertificateChain("castrelsign-server");
      if (storedServer == null
          || storedPrivateKey == null
          || chain == null
          || chain.length < 2
          || !Arrays.equals(storedServer.getEncoded(), serverCertificate.getEncoded())
          || !Arrays.equals(chain[1].getEncoded(), rootCertificate.getEncoded())) {
        return false;
      }
      verifyMatchingEcKey(serverCertificate, storedPrivateKey);

      KeyStore trustStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(trustStorePath)) {
        trustStore.load(input, password);
      }
      java.security.cert.Certificate storedRoot = trustStore.getCertificate("castrelsign-root");
      return storedRoot != null && Arrays.equals(storedRoot.getEncoded(), rootCertificate.getEncoded());
    } catch (Exception invalidOrMissingStore) {
      return false;
    }
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

  private static void replaceMaterialPair(Path certificatePath, String certificatePem,
      Path privateKeyPath, String privateKeyPem, boolean preserveBackups) throws IOException {
    byte[] previousCertificate = readExistingBytes(certificatePath);
    byte[] previousPrivateKey = readExistingBytes(privateKeyPath);
    Path transactionMarker = pairTransactionMarker(certificatePath);
    writeRestrictedAtomically(transactionMarker, "in-progress\n".getBytes(StandardCharsets.UTF_8));
    if (preserveBackups) {
      writeBackup(certificatePath, previousCertificate);
      writeBackup(privateKeyPath, previousPrivateKey);
    }
    try {
      writeRestrictedAtomically(privateKeyPath, privateKeyPem.getBytes(StandardCharsets.UTF_8));
      writeRestrictedAtomically(certificatePath, certificatePem.getBytes(StandardCharsets.UTF_8));
      syncDirectory(certificatePath.getParent());
      Files.deleteIfExists(transactionMarker);
      syncDirectory(certificatePath.getParent());
    } catch (IOException replacementFailure) {
      restore(certificatePath, previousCertificate, replacementFailure);
      restore(privateKeyPath, previousPrivateKey, replacementFailure);
      throw replacementFailure;
    }
  }

  private static void recoverInterruptedPair(Path certificatePath, Path privateKeyPath) throws IOException {
    Path transactionMarker = pairTransactionMarker(certificatePath);
    if (!Files.exists(transactionMarker)) {
      return;
    }

    Path certificateBackup = certificatePath.resolveSibling(certificatePath.getFileName() + ".bak");
    Path privateKeyBackup = privateKeyPath.resolveSibling(privateKeyPath.getFileName() + ".bak");
    if (Files.exists(certificateBackup) && Files.exists(privateKeyBackup)) {
      writeRestrictedAtomically(certificatePath, Files.readAllBytes(certificateBackup));
      writeRestrictedAtomically(privateKeyPath, Files.readAllBytes(privateKeyBackup));
    } else {
      // A transaction marker proves these files were created by an interrupted local
      // generation. Without a complete previous pair, remove both sides and regenerate.
      Files.deleteIfExists(certificatePath);
      Files.deleteIfExists(privateKeyPath);
    }
    Files.deleteIfExists(transactionMarker);
    syncDirectory(certificatePath.getParent());
  }

  private static Path pairTransactionMarker(Path certificatePath) {
    return certificatePath.resolveSibling("." + certificatePath.getFileName() + ".pair-update");
  }

  private static void replaceWithBackup(Path path, byte[] content) throws IOException {
    writeBackup(path, readExistingBytes(path));
    writeRestrictedAtomically(path, content);
  }

  private static byte[] readExistingBytes(Path path) throws IOException {
    return Files.exists(path) ? Files.readAllBytes(path) : null;
  }

  private static void writeBackup(Path path, byte[] content) throws IOException {
    if (content == null) {
      return;
    }
    writeRestrictedAtomically(path.resolveSibling(path.getFileName() + ".bak"), content);
  }

  private static void restore(Path path, byte[] previousContent, IOException replacementFailure) {
    try {
      if (previousContent == null) {
        Files.deleteIfExists(path);
      } else {
        writeRestrictedAtomically(path, previousContent);
      }
    } catch (IOException restoreFailure) {
      replacementFailure.addSuppressed(restoreFailure);
    }
  }

  private static void writeRestrictedAtomically(Path path, byte[] content) throws IOException {
    Files.createDirectories(path.getParent());
    Path tempPath = Files.createTempFile(path.getParent(), "." + path.getFileName() + "-", ".tmp");
    try {
      setRestrictedPermissions(tempPath);
      try (FileChannel channel = FileChannel.open(
          tempPath,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      moveAtomically(tempPath, path);
      setRestrictedPermissions(path);
      syncDirectory(path.getParent());
    } finally {
      Files.deleteIfExists(tempPath);
    }
  }

  private static void syncDirectory(Path directory) throws IOException {
    if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void setRestrictedPermissions(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
    } catch (UnsupportedOperationException ignored) {
      // Windows ACLs are inherited from the restricted data directory.
    }
  }

  private record ServerMaterial(X509Certificate certificate, PrivateKey privateKey) {
  }

  public record RefreshResult(
      CertificateAuthority authority,
      KeyStoreFiles keyStores,
      boolean keyStoresChanged) {
  }

  public record KeyStoreFiles(Path keyStorePath, Path trustStorePath) {
  }
}
