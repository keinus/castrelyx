package org.castrelyx.castrelsign.kms;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class KeyManagementServiceTest {
  @TempDir Path dir;
  private CastrelSignProperties properties;
  @BeforeEach void configure() throws Exception {
    properties = new CastrelSignProperties(); properties.setDataDir(dir.resolve("data"));
    properties.setKeyStorePassword("test-password-for-tls"); properties.setTlsServerName("localhost");
    properties.getKms().setMasterKeyFile(dir.resolve("master.key"));
    byte[] master = new byte[32]; new SecureRandom().nextBytes(master);
    ProtectedFiles.write(dir.resolve("master.key"), Base64.getEncoder().encode(master));
  }
  @AfterEach void close() throws Exception { KeyManagementService.closeAt(properties.getDataDir()); }
  private KeyManagementService open() { return KeyManagementService.open(properties); }
  private static byte[] verify(KeyManagementService.KeyView key, KeyManagementService.SignatureValue signature, byte[] message) throws Exception {
    byte[] publicDer = Base64.getDecoder().decode(key.publicKeyPem().replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicDer));
    var verifier = Signature.getInstance("Ed25519"); verifier.initVerify(publicKey); verifier.update(message);
    assertThat(verifier.verify(Base64.getDecoder().decode(signature.signature()))).isTrue(); return publicDer;
  }
  @Test void signaturesUseEncryptedStableMaterialAndEnforcePurposePermissions() throws Exception {
    var kms = open(); var key = kms.ensureUpdate(); byte[] payload = "manifest with exact bytes".getBytes(StandardCharsets.UTF_8);
    verify(key, kms.sign(key.ref(), KeyManagementService.UPDATER, payload, "release:test", null), payload);
    assertThatThrownBy(() -> kms.sign(key.ref(), KeyManagementService.ISSUER, payload, null, null)).isInstanceOf(ResponseStatusException.class);
    assertThat(kms.dependencies(key.ref())).hasSize(1);
    assertThat(kms.auditEvents(0, 100).toString()).contains("DENIED", "SIGN").doesNotContain("manifest with exact bytes", "PRIVATE KEY");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + properties.getDataDir().resolve("kms/keys.sqlite"));
        var s = db.createStatement(); var row = s.executeQuery("select material from kms_keys")) {
      assertThat(row.next()).isTrue(); assertThat(row.getBytes(1).length).isGreaterThan(48);
      assertThatThrownBy(() -> KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(row.getBytes(1))))
          .isInstanceOf(java.security.spec.InvalidKeySpecException.class);
    }
    KeyManagementService.closeAt(properties.getDataDir());
    assertThat(open().ensureUpdate().ref()).isEqualTo(key.ref());
  }
  @Test void rotationRequiresDifferentApproverAndPreservesOldPublicKeys() {
    var kms = open(); var first = kms.ensureUpdate();
    var next = kms.prepareRotation("alice", KeyManagementService.UPDATE, "rotation-001", Duration.ofDays(3650));
    assertThat(kms.prepareRotation("alice", KeyManagementService.UPDATE, "rotation-001", Duration.ofDays(3650)).ref()).isEqualTo(next.ref());
    assertThat(kms.current(KeyManagementService.UPDATE).ref()).isEqualTo(first.ref());
    String request = kms.request("alice", next.ref(), "ACTIVATE", "planned rotation", Map.of("trustAcknowledgement", "deployment-42"));
    assertThatThrownBy(() -> kms.approve("alice", request)).isInstanceOf(ResponseStatusException.class);
    assertThat(kms.approve("bob", request).state()).isEqualTo("ACTIVE");
    assertThat(kms.get(first.ref()).state()).isEqualTo("VERIFY_ONLY");
    assertThatThrownBy(() -> kms.approve("bob", request)).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> kms.sign(first.ref(), KeyManagementService.UPDATER, new byte[]{1}, null, null)).isInstanceOf(ResponseStatusException.class);
    assertThat(kms.get(first.ref()).publicKeyPem()).isEqualTo(first.publicKeyPem());
  }
  @Test void compromiseCannotBeResumedAndStopsRealCertificateIssuance() throws Exception {
    var kms = open(); var ca = CertificateAuthority.initializeManaged(properties.getDataDir().resolve("certs"), "localhost",
        Duration.ofDays(30), Duration.ofDays(3650), properties.getKeyStorePassword().toCharArray(), kms);
    var root = kms.current(KeyManagementService.CA);
    assertThat(Files.exists(properties.getDataDir().resolve("certs/ca.key"))).isFalse();
    assertThat(Files.readString(properties.getDataDir().resolve("certs/server.key"))).contains("BEGIN ENCRYPTED PRIVATE KEY");
    kms.suspend("alice", root.ref(), true, "incident-42");
    assertThat(kms.trusts(ca.rootCertificate())).isFalse();
    String resume = kms.request("alice", root.ref(), "RESUME", "attempt resume", Map.of());
    assertThatThrownBy(() -> kms.approve("bob", resume)).isInstanceOf(ResponseStatusException.class);
    var signer = kms.contentSigner(root.ref(), KeyManagementService.ISSUER, "cert-test", Instant.now().plusSeconds(30));
    signer.getOutputStream().write(1);
    assertThatThrownBy(signer::getSignature).isInstanceOf(ResponseStatusException.class);
  }
  @Test void deletesOnlyRetiredKeysAfterDependencyReleaseGracePeriodAndFreshApproval() throws Exception {
    MutableClock clock = new MutableClock();
    try (var kms = new KeyManagementService(properties, clock)) {
      var old = kms.ensureUpdate(); kms.sign(old.ref(), KeyManagementService.UPDATER, new byte[]{1}, "release:old", null);
      var next = kms.prepareRotation("alice", KeyManagementService.UPDATE, "rotate-delete", Duration.ofDays(3650));
      kms.approve("bob", kms.request("alice", next.ref(), "ACTIVATE", "activate", Map.of("trustAcknowledgement", "all-clients")));
      String blocked = kms.request("alice", old.ref(), "SCHEDULE_DELETION", "retire", Map.of());
      assertThatThrownBy(() -> kms.approve("bob", blocked)).isInstanceOf(ResponseStatusException.class);
      kms.releaseDependency(KeyManagementService.UPDATER, "release:old");
      kms.approve("bob", blocked);
      String destroy = kms.request("alice", old.ref(), "DESTROY", "destroy retired key", Map.of("backupPurgeEvidence", "no remaining copies"));
      assertThatThrownBy(() -> kms.approve("bob", destroy)).isInstanceOf(ResponseStatusException.class);
      clock.advance(Duration.ofDays(8));
      assertThatThrownBy(() -> kms.approve("bob", destroy)).isInstanceOf(ResponseStatusException.class);
      var tombstone = kms.approve("bob", kms.request("alice", old.ref(), "DESTROY", "expired grace", Map.of("backupPurgeEvidence", "evidence-42")));
      assertThat(tombstone.state()).isEqualTo("DESTROYED"); assertThat(tombstone.publicKeyPem()).isEqualTo(old.publicKeyPem());
      assertThatThrownBy(() -> kms.sign(old.ref(), KeyManagementService.UPDATER, new byte[]{1}, null, null)).isInstanceOf(ResponseStatusException.class);
    }
  }
  @Test void cancellationNeverReactivatesAndExpiryStopsSigning() throws Exception {
    MutableClock clock = new MutableClock(); properties.getKms().setSigningLifetimeDays(2);
    try (var kms = new KeyManagementService(properties, clock)) {
      var first = kms.ensureUpdate(); var next = kms.prepareRotation("a", KeyManagementService.UPDATE, "rotation-002", Duration.ofDays(3650));
      kms.approve("b", kms.request("a", next.ref(), "SCHEDULE_DELETION", "unused staged key", Map.of()));
      assertThat(kms.cancelDeletion("a", next.ref()).state()).isEqualTo("SUSPENDED");
      clock.advance(Duration.ofDays(3));
      assertThatThrownBy(() -> kms.sign(first.ref(), KeyManagementService.UPDATER, new byte[]{1}, null, null)).isInstanceOf(ResponseStatusException.class);
    }
  }
  @Test void rejectsWrongMasterTamperingMissingStoreAndRollback() throws Exception {
    var kms = open(); var key = kms.ensureUpdate();
    Path db = properties.getDataDir().resolve("kms/keys.sqlite");
    KeyManagementService.closeAt(properties.getDataDir()); byte[] oldDb = Files.readAllBytes(db);
    kms = open(); kms.suspend("alice", key.ref(), true, "compromise"); KeyManagementService.closeAt(properties.getDataDir());
    byte[] currentDb = Files.readAllBytes(db); Files.write(db, oldDb);
    assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("KMS rollback detected");
    Files.write(db, currentDb);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) { statement.execute("update kms_keys set state='ACTIVE'"); }
    assertThatThrownBy(this::open).hasRootCauseMessage("KMS state authentication failed");
    Files.write(db, currentDb); byte[] master = Files.readAllBytes(dir.resolve("master.key"));
    Files.writeString(dir.resolve("master.key"), Base64.getEncoder().encodeToString(new byte[32]));
    assertThatThrownBy(this::open).hasRootCauseMessage("KMS state authentication failed");
    Files.write(dir.resolve("master.key"), master); Files.delete(db);
    assertThatThrownBy(this::open).hasRootCauseMessage("initialized KMS database is missing");
  }
  @Test void rejectsPartialLegacyUpdatePairInsteadOfReplacingIt() throws Exception {
    Files.createDirectories(properties.getDataDir().resolve("update-signing"));
    Files.writeString(properties.getDataDir().resolve("update-signing/public.pem"), "existing-public-key");
    assertThatThrownBy(() -> open().ensureUpdate()).isInstanceOf(ResponseStatusException.class).hasMessageContaining("incomplete");
    assertThat(Files.readString(properties.getDataDir().resolve("update-signing/public.pem"))).isEqualTo("existing-public-key");
  }
  @Test void signingQuotaAndParallelCallsPreserveAuditIntegrity() throws Exception {
    properties.getKms().setSignsPerMinute(20);
    try (var kms = new KeyManagementService(properties, Clock.fixed(Instant.now(), ZoneOffset.UTC))) {
    var key = kms.ensureUpdate();
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Callable<KeyManagementService.SignatureValue>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) calls.add(() -> kms.sign(key.ref(), KeyManagementService.UPDATER, new byte[]{1, 2, 3}, null, null));
      for (var result : pool.invokeAll(calls)) verify(key, result.get(), new byte[]{1, 2, 3});
    }
    assertThatThrownBy(() -> kms.sign(key.ref(), KeyManagementService.UPDATER, new byte[]{1}, null, null))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
    assertThat(kms.health().get("status")).isEqualTo("UP");
    }
  }
  @Test void backupRestoresAllFilesAndRejectsStaleOrCorruptArchive() throws Exception {
    var kms = open(); var key = kms.ensureUpdate(); Files.writeString(properties.getDataDir().resolve("application-policy.txt"), "policy-v1");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + properties.getDataDir().resolve("castrelsign.sqlite")); var s = db.createStatement()) {
      s.execute("create table recovery_test(value text)"); s.execute("insert into recovery_test values('restored-with-keys')");
    }
    Path archive = dir.resolve("backup.cskb"), backupKey = dir.resolve("backup.key");
    byte[] random = new byte[32]; new SecureRandom().nextBytes(random); Files.write(backupKey, Base64.getEncoder().encode(random));
    assertThatThrownBy(() -> KmsBackupTool.create(properties, archive, backupKey)).hasMessageContaining("stop CastrelSign");
    KeyManagementService.closeAt(properties.getDataDir()); KmsBackupTool.create(properties, archive, backupKey);
    Files.move(properties.getDataDir(), dir.resolve("retired-data"));
    KmsBackupTool.restore(properties, archive, backupKey);
    assertThat(Files.readString(properties.getDataDir().resolve("application-policy.txt"))).isEqualTo("policy-v1");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + properties.getDataDir().resolve("castrelsign.sqlite")); var s = db.createStatement(); var row = s.executeQuery("select value from recovery_test")) {
      assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("restored-with-keys");
    }
    kms = open(); assertThat(kms.current(KeyManagementService.UPDATE).ref()).isEqualTo(key.ref());
    assertThat(kms.dependencies(key.ref()).toString()).contains("BACKUP");
    kms.suspend("alice", key.ref(), true, "new incident after backup"); KeyManagementService.closeAt(properties.getDataDir());
    Files.move(properties.getDataDir(), dir.resolve("retired-data-2"));
    assertThatThrownBy(() -> KmsBackupTool.restore(properties, archive, backupKey)).hasMessageContaining("latest external checkpoint");
    byte[] corrupted = Files.readAllBytes(archive); corrupted[15] ^= 1; Files.write(dir.resolve("corrupt.cskb"), corrupted);
    assertThatThrownBy(() -> KmsBackupTool.restore(properties, dir.resolve("corrupt.cskb"), backupKey)).isInstanceOf(GeneralSecurityException.class);
    assertThat(properties.getDataDir()).doesNotExist();
  }
  @Test void productionRejectsUnseparatedSecretsAndDevelopmentEnvironment() {
    properties.getKms().setMode("production");
    assertThatThrownBy(this::open).hasRootCauseMessage("production requires an external master-key file, administrators file, environment and a strong TLS keystore password");
  }
  @Test void caRotationIssuesWithNewRootAndRetainsOldTrustUntilCompromise() throws Exception {
    var kms = open(); var authority = CertificateAuthority.initializeManaged(properties.getDataDir().resolve("certs"), "localhost",
        Duration.ofDays(30), Duration.ofDays(3650), properties.getKeyStorePassword().toCharArray(), kms);
    var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    var pair = generator.generateKeyPair();
    var builder = new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(new org.bouncycastle.asn1.x500.X500Name("CN=rotation-client"), pair.getPublic());
    var csr = builder.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()));
    var parsed = new CsrService().parseAndValidate(PemUtil.toPem("CERTIFICATE REQUEST", csr.getEncoded()), "rotation-client");
    var old = kms.current(KeyManagementService.CA); var first = authority.signAgentCertificate(parsed, Duration.ofDays(1));
    var next = kms.prepareRotation("alice", KeyManagementService.CA, "ca-rotation-1", Duration.ofDays(3650));
    assertThat(kms.trustBundle().split("BEGIN CERTIFICATE").length).isEqualTo(3);
    kms.approve("bob", kms.request("alice", next.ref(), "ACTIVATE", "trust migration", Map.of("trustAcknowledgement", "both roots installed")));
    var second = authority.signAgentCertificate(parsed, Duration.ofDays(1)); second.verify(KeyManagementService.certificate(next.certificatePem()).getPublicKey());
    assertThat(authority.trustsClientCertificate(first)).isTrue(); assertThat(authority.trustsClientCertificate(second)).isTrue();
    CertificateAuthority.refreshManaged(properties.getDataDir().resolve("certs"), "localhost", Duration.ofDays(30), Duration.ofDays(3650), properties.getKeyStorePassword().toCharArray(), kms);
    assertThat(kms.tlsInventory()).hasSize(2);
    kms.suspend("alice", old.ref(), true, "old root compromised");
    assertThat(authority.trustsClientCertificate(first)).isFalse(); assertThat(authority.trustsClientCertificate(second)).isTrue();
    kms.suspend("alice", next.ref(), false, "emergency suspension");
    assertThatThrownBy(() -> authority.signAgentCertificate(parsed, Duration.ofDays(1))).isInstanceOf(ResponseStatusException.class);
  }
  private static class MutableClock extends Clock {
    private Instant now = Instant.now(); void advance(Duration duration) { now = now.plus(duration); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
