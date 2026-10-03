package org.castrelyx.castrelsign.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One writer per data directory. All key changes and their audit record commit together. */
public final class KeyManagementService implements AutoCloseable {
  private static final Map<Path, KeyManagementService> INSTANCES = new HashMap<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String CA = "ca-root", UPDATE = "update-signing";
  public static final String ISSUER = "service:certificate-issuer", UPDATER = "service:update-signer", TLS = "service:tls";
  private final Path dataDir, checkpoint;
  private final KmsSettings settings;
  private final Clock clock;
  private final byte[] master;
  private final SigningBackend backend;
  private final Pkcs11SigningBackend pkcs11;
  private final Connection db;
  private final FileChannel lockChannel;
  private final FileLock processLock;
  private final Map<String, long[]> rates = new HashMap<>();
  private boolean closed;

  public record Ref(String keyId, int version) {}
  public record KeyView(String keyId, String alias, int version, String algorithm, String purpose, String owner,
      String tenant, String environment, String state, String createdAt, String signingExpiresAt, String deleteAt,
      String publicKeyPem, String certificatePem, String backend) { public Ref ref() { return new Ref(keyId, version); } }
  public record SignatureValue(Ref key, String algorithm, String signature) {}
  @FunctionalInterface private interface Work<T> { T run() throws Exception; }

  public static synchronized KeyManagementService open(CastrelSignProperties properties) {
    Path path = properties.getDataDir().toAbsolutePath().normalize();
    KeyManagementService existing = INSTANCES.get(path);
    if (existing != null && !existing.closed) return existing;
    try {
      var service = new KeyManagementService(properties, Clock.systemUTC());
      INSTANCES.put(path, service);
      return service;
    } catch (Exception error) { throw new IllegalStateException("cannot open KMS; restore existing material instead of regenerating it", error); }
  }
  public static synchronized void closeAt(Path dataDir) throws Exception {
    KeyManagementService service = INSTANCES.remove(dataDir.toAbsolutePath().normalize());
    if (service != null) service.close();
  }
  static synchronized void assertNotOpen(Path path) {
    var service = INSTANCES.get(path.toAbsolutePath().normalize());
    if (service != null && !service.closed) throw new IllegalStateException("stop CastrelSign before backup or restore");
  }

  KeyManagementService(CastrelSignProperties properties, Clock clock) throws Exception {
    this.clock = clock;
    dataDir = properties.getDataDir().toAbsolutePath().normalize();
    settings = properties.getKms(); settings.validate(dataDir, properties.getKeyStorePassword());
    Files.createDirectories(dataDir);
    Path dir = dataDir.resolve("kms"); Files.createDirectories(dir); ProtectedFiles.restrict(dir);
    lockChannel = FileChannel.open(dir.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired = lockChannel.tryLock();
    if (acquired == null) { lockChannel.close(); throw new IllegalStateException("another KMS writer is running"); }
    processLock = acquired;
    Connection connection = null;
    try {
      Path marker = dir.resolve("initialized"), database = dir.resolve("keys.sqlite");
      boolean exists = Files.exists(database), initialized = Files.exists(marker);
      if (initialized && !exists) throw new IllegalStateException("initialized KMS database is missing");
      Path keyPath = settings.getMasterKeyFile() == null ? dataDir.resolve("secrets/kms-master.key")
          : settings.getMasterKeyFile().toAbsolutePath().normalize();
      checkpoint = keyPath.resolveSibling(keyPath.getFileName() + ".checkpoint");
      if (!Files.exists(keyPath)) {
        if (exists || initialized || settings.production()) throw new IllegalStateException("KMS wrapping key is missing");
        byte[] fresh = new byte[32]; new SecureRandom().nextBytes(fresh);
        ProtectedFiles.write(keyPath, Base64.getEncoder().encode(fresh)); Arrays.fill(fresh, (byte) 0);
        ProtectedFiles.restrict(keyPath.getParent());
      }
      if (Files.isSymbolicLink(keyPath)) throw new IllegalStateException("master-key symlinks are not supported");
      master = Base64.getDecoder().decode(Files.readString(keyPath).trim());
      if (master.length != 32) throw new IllegalStateException("KMS master key must contain 32 base64-encoded bytes");
      backend = new EncryptedSoftwareBackend(master);
      pkcs11 = settings.getPkcs11Config() == null ? null : new Pkcs11SigningBackend(settings);
      Class.forName("org.sqlite.JDBC");
      db = connection = DriverManager.getConnection("jdbc:sqlite:" + database);
      sql("PRAGMA busy_timeout=5000"); sql("PRAGMA foreign_keys=ON"); sql("PRAGMA secure_delete=ON");
      sql("PRAGMA synchronous=FULL"); sql("PRAGMA journal_mode=DELETE");
      schema(); ProtectedFiles.restrict(database);
      if (!exists) {
        if (Files.exists(checkpoint)) throw new IllegalStateException("database missing but external checkpoint exists");
        sql("BEGIN IMMEDIATE");
        update("insert into kms_meta(name,value) values('tenant',?),('environment',?)", settings.getTenant(), settings.getEnvironment());
        audit("system:bootstrap", "INITIALIZE", null, "SUCCESS", ""); seal(); writeCheckpoint(); sql("COMMIT");
      } else {
        verifyIntegrity();
        if (!settings.getTenant().equals(scalar("select value from kms_meta where name='tenant'"))
            || !settings.getEnvironment().equals(scalar("select value from kms_meta where name='environment'")))
          throw new IllegalStateException("KMS tenant/environment cannot be changed in place");
        verifyAudit(); checkCheckpoint();
      }
      if (!initialized) ProtectedFiles.write(marker, "v1\n".getBytes(StandardCharsets.UTF_8));
      writeCheckpoint();
    } catch (Exception error) {
      if (connection != null) connection.close(); processLock.release(); lockChannel.close(); throw error;
    }
  }

  private void schema() throws SQLException {
    sql("create table if not exists kms_meta(name text primary key,value text not null)");
    sql("""
        create table if not exists kms_keys(key_id text not null,alias text not null,version integer not null,
        algorithm text not null,purpose text not null,owner text not null,tenant text not null,environment text not null,
        state text not null,public_der blob not null,material blob,certificate text,created_at text not null,
        expires_at text not null,delete_at text,backend text not null,primary key(key_id,version),unique(alias,version))
        """);
    sql("create table if not exists kms_aliases(alias text primary key,key_id text not null,version integer not null)");
    sql("create table if not exists kms_grants(key_id text,subject text,operation text,primary key(key_id,subject,operation))");
    sql("""
        create table if not exists kms_requests(id text primary key,action text not null,key_id text,version integer,
        requested_by text not null,reason text not null,payload text not null,expires_at text not null,
        status text not null,approved_by text)
        """);
    sql("create table if not exists kms_dependencies(key_id text,version integer,kind text,reference text,expires_at text,primary key(key_id,version,kind,reference))");
    sql("create table if not exists kms_audit(sequence integer primary key,event text not null,previous_hash text not null,hash text not null)");
    sql("create table if not exists kms_mfa(actor text primary key,last_step integer not null)");
    sql("create table if not exists kms_tls(version integer primary key,key_id text not null,state text not null,certificate text not null,issuer_key_id text not null,issuer_version integer not null,expires_at text not null)");
  }

  public synchronized KeyView ensureCa(Duration validFor) {
    return ensure(CA, "SHA256withECDSA", "CERTIFICATE_SIGNING", dataDir.resolve("certs/ca.key"),
        dataDir.resolve("certs/ca.pem"), validFor);
  }
  public synchronized KeyView ensureUpdate() {
    return ensure(UPDATE, "Ed25519", "UPDATE_SIGNING", dataDir.resolve("update-signing/private.pem"),
        dataDir.resolve("update-signing/public.pem"), Duration.ofDays(settings.getSigningLifetimeDays()));
  }
  private KeyView ensure(String alias, String algorithm, String purpose, Path privatePath, Path publicPath, Duration validFor) {
    Optional<KeyView> found = findCurrent(alias);
    if (found.isPresent()) return found.get();
    return operation("system:bootstrap", "REGISTER", null, () -> {
      if (!rows("select key_id from kms_keys where alias=?", alias).isEmpty()) throw conflict("key alias missing; repair required");
      boolean priv = Files.exists(privatePath), pub = Files.exists(publicPath);
      List<Map<String, Object>> history = legacyDependencies(alias);
      Path expected = dataDir.resolve("kms").resolve(alias + ".expected");
      if (priv || pub || !history.isEmpty()) ProtectedFiles.write(expected, "existing-key-required\n".getBytes(StandardCharsets.UTF_8));
      else if (Files.exists(expected)) throw conflict("previously observed signing key is missing; restore required");
      if (priv != pub) throw conflict("legacy key pair is incomplete; refusing regeneration");
      if (!priv && !history.isEmpty()) throw conflict("historical certificates or releases exist but their signing key is missing");
      KeyPair pair = null; X509Certificate certificate = null;
      if (priv) {
        PrivateKey privateKey = readPrivate(privatePath);
        PublicKey publicKey;
        if (CA.equals(alias)) {
          certificate = certificate(Files.readString(publicPath)); certificate.checkValidity();
          if (certificate.getBasicConstraints() < 0 || certificate.getKeyUsage() == null || !certificate.getKeyUsage()[5])
            throw conflict("legacy CA is invalid");
          certificate.verify(certificate.getPublicKey()); publicKey = certificate.getPublicKey();
        } else publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decodePem(Files.readString(publicPath))));
        pair = new KeyPair(publicKey, privateKey); verifyPair(pair, algorithm);
      }
      String id = UUID.randomUUID().toString();
      KeyView key = insertKey(id, alias, 1, algorithm, purpose, "platform-security", pair, certificate, validFor, "ACTIVE");
      update("insert into kms_aliases values(?,?,?)", alias, id, 1);
      update("insert into kms_grants values(?,?,?)", id, CA.equals(alias) ? ISSUER : UPDATER, "SIGN");
      if (CA.equals(alias)) update("insert into kms_grants values(?,?,?)", id, TLS, "SIGN");
      for (var dependency : history) update("insert or ignore into kms_dependencies values(?,?,?,?,?)", id, 1, purpose, dependency.get("reference"), dependency.get("expires_at"));
      return key;
    });
  }

  private KeyView insertKey(String id, String alias, int version, String algorithm, String purpose, String owner,
      KeyPair supplied, X509Certificate suppliedCertificate, Duration validFor, String state) throws Exception {
    Ref ref = new Ref(id, version);
    // Java 21 SunPKCS11's documented signature set covers ECDSA, not Ed25519.
    // Keep the update key explicitly in the software backend; never silently fall back for a CA token failure.
    if (pkcs11 != null && CA.equals(alias)) {
      if (supplied != null) throw conflict("legacy software keys require an explicit trust migration before PKCS11 registration");
      String tokenAlias = alias + ".v" + version;
      X509Certificate certificate = pkcs11.certificate(tokenAlias); certificate.checkValidity();
      if (CA.equals(alias)) {
        requireP256(certificate.getPublicKey()); certificate.verify(certificate.getPublicKey());
        if (certificate.getBasicConstraints() < 0 || certificate.getKeyUsage() == null || !certificate.getKeyUsage()[5]) throw conflict("PKCS11 CA certificate cannot issue certificates");
      }
      byte[] reference = tokenAlias.getBytes(StandardCharsets.UTF_8), challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
      byte[] proof = pkcs11.sign(algorithm, reference, binding(ref, algorithm), challenge);
      Signature verifier = Signature.getInstance(algorithm); verifier.initVerify(certificate.getPublicKey()); verifier.update(challenge);
      if (!verifier.verify(proof)) throw conflict("PKCS11 key and certificate do not match");
      Instant expires = clock.instant().plus(Duration.ofDays(settings.getSigningLifetimeDays()));
      if (certificate.getNotAfter().toInstant().isBefore(expires)) expires = certificate.getNotAfter().toInstant();
      update("insert into kms_keys values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, alias, version, algorithm, purpose, owner,
          settings.getTenant(), settings.getEnvironment(), state, certificate.getPublicKey().getEncoded(), reference,
          PemUtil.certificateToPem(certificate), clock.instant().toString(), expires.toString(), null, "PKCS11");
      return key(ref);
    }
    KeyPair pair = supplied;
    if (pair == null) {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519".equals(algorithm) ? "Ed25519" : "EC");
      if (!"Ed25519".equals(algorithm)) gen.initialize(new ECGenParameterSpec("secp256r1"));
      pair = gen.generateKeyPair();
    }
    if ("SHA256withECDSA".equals(algorithm)) requireP256(pair.getPublic());
    X509Certificate cert = suppliedCertificate;
    if (CA.equals(alias) && cert == null) cert = rootCertificate(pair, validFor, version);
    byte[] plain = pair.getPrivate().getEncoded(); byte[] encrypted;
    try { encrypted = backend.protect(plain, binding(ref, algorithm)); } finally { Arrays.fill(plain, (byte) 0); }
    Instant expires = clock.instant().plus(Duration.ofDays(settings.getSigningLifetimeDays()));
    if (cert != null && cert.getNotAfter().toInstant().isBefore(expires)) expires = cert.getNotAfter().toInstant();
    update("insert into kms_keys values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, alias, version, algorithm, purpose, owner,
        settings.getTenant(), settings.getEnvironment(), state, pair.getPublic().getEncoded(), encrypted,
        cert == null ? null : PemUtil.certificateToPem(cert), clock.instant().toString(), expires.toString(), null, "ENCRYPTED_SOFTWARE");
    return key(ref);
  }

  public synchronized Optional<KeyView> findCurrent(String alias) {
    return read(() -> {
      var result = rows("select key_id,version from kms_aliases where alias=?", alias);
      return result.isEmpty() ? Optional.empty() : Optional.of(key(new Ref((String) result.getFirst().get("key_id"), ((Number) result.getFirst().get("version")).intValue())));
    });
  }
  public KeyView current(String alias) { return findCurrent(alias).orElseThrow(() -> conflict("key is not registered")); }
  public synchronized List<KeyView> keys() { return read(() -> rows("select * from kms_keys order by alias,version").stream().map(this::view).toList()); }
  public synchronized KeyView get(Ref ref) { return read(() -> key(ref)); }
  private KeyView key(Ref ref) throws SQLException {
    var list = rows("select * from kms_keys where key_id=? and version=?", ref.keyId(), ref.version());
    if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key version not found");
    return view(list.getFirst());
  }
  private KeyView view(Map<String, Object> row) {
    return new KeyView((String) row.get("key_id"), (String) row.get("alias"), ((Number) row.get("version")).intValue(),
        (String) row.get("algorithm"), (String) row.get("purpose"), (String) row.get("owner"), (String) row.get("tenant"),
        (String) row.get("environment"), (String) row.get("state"), (String) row.get("created_at"), (String) row.get("expires_at"),
        (String) row.get("delete_at"), PemUtil.toPem("PUBLIC KEY", (byte[]) row.get("public_der")), (String) row.get("certificate"), (String) row.get("backend"));
  }

  /** Records a dependency before releasing the signature; orphan records fail safely until expiry. */
  public synchronized SignatureValue sign(Ref ref, String actor, byte[] message, String dependency, Instant expiresAt) {
    if (message == null || message.length == 0 || message.length > 1024 * 1024) throw new IllegalArgumentException("invalid signing payload size");
    return operation(actor, "SIGN", ref, () -> {
      KeyView key = key(ref);
      if (!"ACTIVE".equals(key.state()) || !Instant.parse(key.signingExpiresAt()).isAfter(clock.instant())) throw forbidden("key is not active for signing");
      if (rows("select 1 from kms_grants where key_id=? and subject=? and operation='SIGN'", ref.keyId(), actor).isEmpty()) throw forbidden("key permission denied");
      long minute = clock.instant().getEpochSecond() / 60;
      long[] rate = rates.computeIfAbsent(ref.keyId(), ignored -> new long[]{minute, 0});
      if (rate[0] != minute) { rate[0] = minute; rate[1] = 0; }
      if (++rate[1] > settings.getSignsPerMinute()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "key signing quota exceeded");
      byte[] material = (byte[]) rows("select material from kms_keys where key_id=? and version=?", ref.keyId(), ref.version()).getFirst().get("material");
      if (material == null) throw conflict("key material unavailable");
      String provider = (String) rows("select backend from kms_keys where key_id=? and version=?", ref.keyId(), ref.version()).getFirst().get("backend");
      SigningBackend selected = "PKCS11".equals(provider) ? pkcs11 : backend;
      if (selected == null) throw conflict("configured signing backend is unavailable; software fallback is prohibited");
      byte[] signature = selected.sign(key.algorithm(), material, binding(ref, key.algorithm()), message);
      if (dependency != null) update("insert or replace into kms_dependencies values(?,?,?,?,?)", ref.keyId(), ref.version(),
          key.purpose(), dependency, expiresAt == null ? null : expiresAt.toString());
      return new SignatureValue(ref, key.algorithm(), Base64.getEncoder().encodeToString(signature));
    });
  }
  public ContentSigner contentSigner(Ref ref, String actor, String dependency, Instant expiresAt) {
    return new ContentSigner() {
      private final ByteArrayOutputStream output = new ByteArrayOutputStream();
      @Override public AlgorithmIdentifier getAlgorithmIdentifier() { return new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA"); }
      @Override public OutputStream getOutputStream() { return output; }
      @Override public byte[] getSignature() { return Base64.getDecoder().decode(sign(ref, actor, output.toByteArray(), dependency, expiresAt).signature()); }
    };
  }

  public synchronized KeyView prepareRotation(String actor, String alias, String requestId, Duration caLifetime) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,100}")) throw new IllegalArgumentException("requestId must be 8-100 URL-safe characters");
    return operation(actor, "PREPARE_ROTATION", null, () -> {
      String marker = "rotation:" + actor + ":" + requestId;
      var prior = rows("select payload from kms_requests where id=?", marker);
      if (!prior.isEmpty()) {
        var ref = JSON.readValue((String) prior.getFirst().get("payload"), Ref.class);
        KeyView saved = key(ref);
        if (!saved.alias().equals(alias)) throw conflict("idempotency key used for another alias");
        return saved;
      }
      KeyView old = current(alias);
      if (!rows("select 1 from kms_keys where key_id=? and state='PRE_ACTIVE'", old.keyId()).isEmpty()) throw conflict("a prepared version already exists");
      int version = ((Number) rows("select max(version) as n from kms_keys where key_id=?", old.keyId()).getFirst().get("n")).intValue() + 1;
      KeyView created = insertKey(old.keyId(), alias, version, old.algorithm(), old.purpose(), old.owner(), null, null,
          CA.equals(alias) ? caLifetime : Duration.ofDays(settings.getSigningLifetimeDays()), "PRE_ACTIVE");
      update("insert into kms_requests values(?,?,?,?,?,?,?,?,?,?)", marker, "PREPARE_ROTATION", old.keyId(), version,
          actor, "idempotency", JSON.writeValueAsString(created.ref()), clock.instant().toString(), "EXECUTED", null);
      return created;
    });
  }

  public synchronized KeyView suspend(String actor, Ref ref, boolean compromised, String reason) {
    requireReason(reason);
    return operation(actor, compromised ? "COMPROMISE" : "SUSPEND", ref, () -> {
      KeyView key = key(ref);
      if (Set.of("DESTROYED", "COMPROMISED", "PENDING_DELETION").contains(key.state())) throw conflict("invalid key state transition");
      update("update kms_keys set state=? where key_id=? and version=?", compromised ? "COMPROMISED" : "SUSPENDED", ref.keyId(), ref.version());
      audit(actor, "STATE_CHANGE_REASON", ref, "SUCCESS", reason);
      return key(ref);
    });
  }

  public synchronized String request(String actor, Ref ref, String action, String reason, Map<String, String> payload) {
    requireReason(reason);
    if (!Set.of("ACTIVATE", "RESUME", "SCHEDULE_DELETION", "DESTROY", "GRANT", "REVOKE", "ACK_BACKUP_PURGE").contains(action)) throw new IllegalArgumentException("unsupported approval action");
    Map<String, String> safePayload = payload == null ? Map.of() : Map.copyOf(payload);
    if (safePayload.size() > 3 || safePayload.values().stream().anyMatch(v -> v.length() > 500)) throw new IllegalArgumentException("approval payload too large");
    return operation(actor, "REQUEST_" + action, ref, () -> {
      key(ref); String id = UUID.randomUUID().toString();
      update("insert into kms_requests values(?,?,?,?,?,?,?,?,?,?)", id, action, ref.keyId(), ref.version(), actor, reason,
          JSON.writeValueAsString(safePayload), clock.instant().plus(Duration.ofHours(24)).toString(), "PENDING", null);
      return id;
    });
  }
  public synchronized Map<String, Object> approval(String id) {
    return read(() -> {
      var requests = rows("select * from kms_requests where id=?", id);
      if (requests.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "approval not found");
      return requests.getFirst();
    });
  }
  public synchronized KeyView approve(String approver, String id) {
    return operation(approver, "APPROVE", null, () -> {
      Map<String, Object> request = approval(id);
      if (approver.equals(request.get("requested_by"))) throw forbidden("a different administrator must approve");
      if (!"PENDING".equals(request.get("status")) || !Instant.parse((String) request.get("expires_at")).isAfter(clock.instant())) throw conflict("approval expired or already used");
      Ref ref = new Ref((String) request.get("key_id"), ((Number) request.get("version")).intValue()); KeyView key = key(ref);
      var payload = JSON.readTree((String) request.get("payload"));
      switch ((String) request.get("action")) {
        case "ACTIVATE" -> {
          if (!"PRE_ACTIVE".equals(key.state())) throw conflict("only prepared versions can be activated");
          if (payload.path("trustAcknowledgement").asText().isBlank()) throw conflict("trust distribution acknowledgement is required");
          if (!Instant.parse(key.signingExpiresAt()).isAfter(clock.instant())) throw conflict("prepared key has expired");
          KeyView prior = current(key.alias());
          update("update kms_keys set state='VERIFY_ONLY' where key_id=? and version=? and state='ACTIVE'", prior.keyId(), prior.version());
          update("update kms_keys set state='ACTIVE' where key_id=? and version=?", ref.keyId(), ref.version());
          update("update kms_aliases set version=? where alias=?", ref.version(), key.alias());
        }
        case "RESUME" -> {
          if (!"SUSPENDED".equals(key.state())) throw conflict("only suspended keys can resume");
          boolean current = current(key.alias()).ref().equals(ref);
          update("update kms_keys set state=? where key_id=? and version=?", current ? "ACTIVE" : "VERIFY_ONLY", ref.keyId(), ref.version());
        }
        case "SCHEDULE_DELETION" -> {
          if (current(key.alias()).ref().equals(ref) || !Set.of("VERIFY_ONLY", "SUSPENDED", "COMPROMISED", "PRE_ACTIVE").contains(key.state())) throw conflict("current or invalid key cannot be deleted");
          requireNoDependencies(ref);
          update("update kms_keys set state='PENDING_DELETION',delete_at=? where key_id=? and version=?",
              clock.instant().plus(Duration.ofDays(settings.getDeletionGraceDays())).toString(), ref.keyId(), ref.version());
        }
        case "DESTROY" -> {
          if (!"PENDING_DELETION".equals(key.state()) || Instant.parse(key.deleteAt()).isAfter(clock.instant())) throw conflict("deletion waiting period has not elapsed");
          requireNoDependencies(ref);
          if ("PKCS11".equals(rows("select backend from kms_keys where key_id=? and version=?", ref.keyId(), ref.version()).getFirst().get("backend")))
            throw conflict("physical HSM destruction requires a provider-specific verified workflow; no software tombstone is substituted");
          if (payload.path("backupPurgeEvidence").asText().isBlank()) throw conflict("backup purge evidence is required");
          update("update kms_keys set material=null,state='DESTROYED' where key_id=? and version=?", ref.keyId(), ref.version());
        }
        case "GRANT", "REVOKE" -> {
          String subject = payload.path("subject").asText();
          Set<String> allowed = CA.equals(key.alias()) ? Set.of(ISSUER, TLS) : Set.of(UPDATER);
          if (!allowed.contains(subject) || !"SIGN".equals(payload.path("operation").asText())) throw conflict("invalid service or operation for key purpose");
          if ("GRANT".equals(request.get("action"))) update("insert or ignore into kms_grants values(?,?,'SIGN')", ref.keyId(), subject);
          else update("delete from kms_grants where key_id=? and subject=? and operation='SIGN'", ref.keyId(), subject);
        }
        case "ACK_BACKUP_PURGE" -> {
          String backupId = payload.path("backupId").asText();
          if (backupId.isBlank() || payload.path("evidence").asText().isBlank()) throw conflict("backup id and external purge evidence required");
          if (update("delete from kms_dependencies where key_id=? and version=? and kind='BACKUP' and reference=?", ref.keyId(), ref.version(), backupId) != 1)
            throw conflict("backup dependency not found");
        }
        default -> throw conflict("unsupported approval action");
      }
      update("update kms_requests set status='EXECUTED',approved_by=? where id=?", approver, id);
      // Includes request id, immutable proposed action and both identities, but never secrets.
      audit(approver, "EXECUTE_" + request.get("action"), ref, "SUCCESS", id + ":" + request.get("requested_by"));
      return key(ref);
    });
  }
  public synchronized KeyView cancelDeletion(String actor, Ref ref) {
    return operation(actor, "CANCEL_DELETION", ref, () -> {
      if (!"PENDING_DELETION".equals(key(ref).state())) throw conflict("key is not pending deletion");
      // Cancellation never restores signing or trust automatically.
      update("update kms_keys set state='SUSPENDED',delete_at=null where key_id=? and version=?", ref.keyId(), ref.version());
      return key(ref);
    });
  }
  private void requireNoDependencies(Ref ref) throws SQLException {
    if (!rows("select 1 from kms_dependencies where key_id=? and version=? and (expires_at is null or expires_at>?)", ref.keyId(), ref.version(), clock.instant().toString()).isEmpty())
      throw conflict("key has live certificate, release or backup dependencies");
  }
  public synchronized void releaseDependency(String actor, String reference) {
    operation(actor, "RELEASE_DEPENDENCY", null, () -> { update("delete from kms_dependencies where kind='UPDATE_SIGNING' and reference=?", reference); return null; });
  }
  public synchronized List<Map<String, Object>> dependencies(Ref ref) { return read(() -> rows("select kind,reference,expires_at from kms_dependencies where key_id=? and version=?", ref.keyId(), ref.version())); }
  public synchronized List<Map<String, Object>> grants(Ref ref) { return read(() -> rows("select subject,operation from kms_grants where key_id=?", ref.keyId())); }
  public synchronized List<Map<String, Object>> requests() { return read(() -> rows("select * from kms_requests where action<>'PREPARE_ROTATION' order by expires_at desc limit 200")); }
  public synchronized List<Map<String, Object>> auditEvents(long after, int limit) { return read(() -> rows("select * from kms_audit where sequence>? order by sequence limit ?", Math.max(0, after), Math.max(1, Math.min(1000, limit)))); }
  public synchronized void record(String actor, String action, String result, String requestId) {
    operation(actor, action, null, () -> { audit(actor, action + "_DETAIL", null, result, requestId); return null; });
  }
  public synchronized boolean useMfaStep(String actor, long step) {
    return operation(actor, "MFA", null, () -> {
      var current = rows("select last_step from kms_mfa where actor=?", actor);
      if (!current.isEmpty() && ((Number) current.getFirst().get("last_step")).longValue() >= step) throw forbidden("MFA code was already used");
      update("insert or replace into kms_mfa values(?,?)", actor, step); return true;
    });
  }

  public synchronized String trustBundle() {
    return keys().stream().filter(k -> CA.equals(k.alias()) && trusted(k.state()))
        .map(KeyView::certificatePem).filter(Objects::nonNull).reduce("", String::concat);
  }
  public synchronized boolean trusts(X509Certificate leaf) {
    for (KeyView key : keys()) {
      if (!CA.equals(key.alias()) || !trusted(key.state()) || key.certificatePem() == null) continue;
      try { X509Certificate root = certificate(key.certificatePem()); root.checkValidity(); leaf.verify(root.getPublicKey()); return true; }
      catch (Exception ignored) { }
    }
    return false;
  }
  public static boolean trusted(String state) { return Set.of("PRE_ACTIVE", "ACTIVE", "VERIFY_ONLY").contains(state); }
  public synchronized Map<String, Object> health() {
    return read(() -> {
      verifyAudit();
      long inactive = keys().stream().filter(k -> current(k.alias()).ref().equals(k.ref()) && (!"ACTIVE".equals(k.state()) || Instant.parse(k.signingExpiresAt()).isBefore(clock.instant()))).count();
      long expiring = keys().stream().filter(k -> "ACTIVE".equals(k.state()) && Instant.parse(k.signingExpiresAt()).isBefore(clock.instant().plus(Duration.ofDays(30)))).count();
      boolean backendMissing = keys().stream().anyMatch(k -> "PKCS11".equals(k.backend())) && pkcs11 == null;
      return Map.of("status", inactive == 0 && !backendMissing ? "UP" : "DEGRADED", "backend", pkcs11 == null ? "ENCRYPTED_SOFTWARE" : "MIXED_PKCS11_SOFTWARE", "mode", settings.getMode(),
          "inactiveCurrentKeys", inactive, "keysExpiringWithin30Days", expiring, "auditEvents", rows("select count(*) as n from kms_audit").getFirst().get("n"), "singleWriter", true);
    });
  }
  public Path dataDir() { return dataDir; }
  public KmsSettings settings() { return settings; }

  public synchronized void registerTls(X509Certificate certificate, Ref issuer) throws Exception {
    String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded()));
    if (read(() -> !rows("select 1 from kms_tls where key_id=? and state='ACTIVE'", fingerprint).isEmpty())) return;
    operation(TLS, "REGISTER_TLS", issuer, () -> {
      int version = ((Number) rows("select coalesce(max(version),0)+1 as n from kms_tls").getFirst().get("n")).intValue();
      update("update kms_tls set state='RETIRED' where state='ACTIVE'");
      update("insert into kms_tls values(?,?,'ACTIVE',?,?,?,?)", version, fingerprint, PemUtil.certificateToPem(certificate), issuer.keyId(), issuer.version(), certificate.getNotAfter().toInstant().toString());
      update("insert or ignore into kms_dependencies values(?,?,?,?,?)", issuer.keyId(), issuer.version(), "TLS_CERTIFICATE", "server:" + certificate.getSerialNumber().toString(16), certificate.getNotAfter().toInstant().toString());
      return null;
    });
  }
  public synchronized List<Map<String, Object>> tlsInventory() { return read(() -> rows("select * from kms_tls order by version")); }

  synchronized void registerBackup(String id) {
    operation("operator:offline", "BACKUP", null, () -> {
      for (var key : rows("select key_id,version from kms_keys where material is not null"))
        update("insert into kms_dependencies values(?,?,'BACKUP',?,null)", key.get("key_id"), key.get("version"), id);
      return null;
    });
  }
  synchronized byte[] backupCheckpoint() throws Exception { checkCheckpoint(); return Files.readAllBytes(checkpoint); }

  private <T> T read(Work<T> work) {
    try { if (closed) throw new IllegalStateException("KMS is closed"); verifyIntegrity(); checkCheckpoint(); return work.run(); }
    catch (ResponseStatusException error) { throw error; }
    catch (Exception error) { throw unavailable(error); }
  }
  private <T> T operation(String actor, String action, Ref ref, Work<T> work) {
    boolean begun = false;
    try {
      if (closed) throw new IllegalStateException("KMS is closed");
      verifyIntegrity(); checkCheckpoint(); sql("BEGIN IMMEDIATE"); begun = true;
      T result = work.run(); audit(actor, action, ref, "SUCCESS", ""); seal();
      // Persist the independent rollback barrier BEFORE committing irreversible state.
      // A crash/commit failure in between intentionally requires recovery, rather than accepting old state.
      writeCheckpoint(); sql("COMMIT"); begun = false;
      return result;
    } catch (Exception error) {
      if (begun) try { sql("ROLLBACK"); } catch (Exception rollback) { error.addSuppressed(rollback); }
      if (error instanceof ResponseStatusException || error instanceof IllegalArgumentException) {
        try {
          sql("BEGIN IMMEDIATE"); audit(actor, action, ref, "DENIED", error instanceof ResponseStatusException r ? Integer.toString(r.getStatusCode().value()) : "invalid-input");
          seal(); writeCheckpoint(); sql("COMMIT");
        } catch (Exception auditError) { try { sql("ROLLBACK"); } catch (Exception ignored) {} throw unavailable(auditError); }
        if (error instanceof ResponseStatusException status) throw status;
        throw (IllegalArgumentException) error;
      }
      try {
        verifyIntegrity(); checkCheckpoint(); sql("BEGIN IMMEDIATE");
        audit(actor, action, ref, "ERROR", error.getClass().getSimpleName()); seal(); writeCheckpoint(); sql("COMMIT");
      } catch (Exception auditFailure) { try { sql("ROLLBACK"); } catch (Exception ignored) {} }
      org.slf4j.LoggerFactory.getLogger(KeyManagementService.class).error("KMS operation {} failed ({})", action, error.getClass().getSimpleName());
      throw unavailable(error);
    }
  }
  private void audit(String actor, String action, Ref ref, String result, String detail) throws Exception {
    var tail = rows("select sequence,hash from kms_audit order by sequence desc limit 1");
    long sequence = tail.isEmpty() ? 1 : ((Number) tail.getFirst().get("sequence")).longValue() + 1;
    String previous = tail.isEmpty() ? "GENESIS" : (String) tail.getFirst().get("hash");
    Map<String, Object> event = new LinkedHashMap<>(); event.put("sequence", sequence); event.put("timestamp", clock.instant().toString());
    event.put("actor", actor); event.put("operation", action); event.put("keyId", ref == null ? null : ref.keyId()); event.put("version", ref == null ? null : ref.version());
    event.put("tenant", settings.getTenant()); event.put("environment", settings.getEnvironment()); event.put("result", result); event.put("detail", detail);
    String json = JSON.writeValueAsString(event);
    update("insert into kms_audit values(?,?,?,?)", sequence, json, previous, hmac("audit:" + previous + ":" + json));
  }
  private String stateMac() throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (String table : List.of("kms_keys", "kms_aliases", "kms_grants", "kms_requests", "kms_dependencies", "kms_mfa", "kms_tls")) {
      digest.update(table.getBytes(StandardCharsets.UTF_8));
      String order = switch (table) {
        case "kms_keys" -> " order by key_id,version";
        case "kms_dependencies" -> " order by key_id,version,kind,reference";
        default -> " order by 1,2";
      };
      for (Map<String, Object> row : rows("select * from " + table + order)) digest.update(JSON.writeValueAsBytes(row));
    }
    digest.update(JSON.writeValueAsBytes(rows("select sequence,hash from kms_audit order by sequence desc limit 1")));
    return hmac("state:" + settings.getTenant() + ":" + settings.getEnvironment() + ":" + HexFormat.of().formatHex(digest.digest()));
  }
  private void seal() throws Exception { update("insert or replace into kms_meta values('mac',?)", stateMac()); }
  private void verifyIntegrity() throws Exception {
    String stored = scalar("select value from kms_meta where name='mac'");
    if (stored == null || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), stateMac().getBytes(StandardCharsets.UTF_8))) throw new GeneralSecurityException("KMS state authentication failed");
  }
  private void verifyAudit() throws Exception {
    String previous = "GENESIS"; long expected = 1;
    for (var row : rows("select * from kms_audit order by sequence")) {
      String json = (String) row.get("event");
      if (((Number) row.get("sequence")).longValue() != expected++ || !previous.equals(row.get("previous_hash"))
          || !hmac("audit:" + previous + ":" + json).equals(row.get("hash"))) throw new GeneralSecurityException("audit chain verification failed");
      previous = (String) row.get("hash");
    }
  }
  private void checkCheckpoint() throws Exception {
    if (!Files.exists(checkpoint)) throw new GeneralSecurityException("external KMS checkpoint is missing");
    String[] parts = Files.readString(checkpoint).split("\n");
    if (parts.length != 3 || !hmac("checkpoint:" + parts[0] + ":" + parts[1]).equals(parts[2])) throw new GeneralSecurityException("invalid KMS checkpoint");
    var rows = rows("select hash from kms_audit where sequence=?", Long.parseLong(parts[0]));
    if (rows.isEmpty() || !parts[1].equals(rows.getFirst().get("hash"))) throw new GeneralSecurityException("KMS rollback detected");
  }
  private void writeCheckpoint() throws Exception {
    var tail = rows("select sequence,hash from kms_audit order by sequence desc limit 1").getFirst();
    String seq = tail.get("sequence").toString(), hash = (String) tail.get("hash");
    ProtectedFiles.write(checkpoint, (seq + "\n" + hash + "\n" + hmac("checkpoint:" + seq + ":" + hash)).getBytes(StandardCharsets.UTF_8));
  }
  private String hmac(String text) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(master, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }
  private void sql(String sql) throws SQLException { try (Statement s = db.createStatement()) { s.execute(sql); } }
  private int update(String sql, Object... values) throws SQLException { try (PreparedStatement s = db.prepareStatement(sql)) { bind(s, values); return s.executeUpdate(); } }
  private List<Map<String, Object>> rows(String sql, Object... values) throws SQLException {
    try (PreparedStatement s = db.prepareStatement(sql)) {
      bind(s, values); try (ResultSet rs = s.executeQuery()) {
        List<Map<String, Object>> result = new ArrayList<>();
        while (rs.next()) { Map<String, Object> row = new LinkedHashMap<>(); for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i)); result.add(row); }
        return result;
      }
    }
  }
  private static void bind(PreparedStatement statement, Object[] values) throws SQLException { for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); }
  private String scalar(String sql) throws SQLException { var list = rows(sql); return list.isEmpty() ? null : (String) list.getFirst().values().iterator().next(); }
  private String binding(Ref ref, String algorithm) { return "castrelsign:v1:" + settings.getTenant() + ":" + settings.getEnvironment() + ":" + ref.keyId() + ":" + ref.version() + ":" + algorithm; }
  public static X509Certificate certificate(String pem) throws CertificateException { return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))); }
  private static PrivateKey readPrivate(Path file) throws Exception {
    try (PEMParser parser = new PEMParser(Files.newBufferedReader(file))) {
      Object value = parser.readObject(); var converter = new JcaPEMKeyConverter();
      if (value instanceof PEMKeyPair pair) return converter.getKeyPair(pair).getPrivate();
      if (value instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo key) return converter.getPrivateKey(key);
      throw new GeneralSecurityException("unsupported legacy private key");
    }
  }
  private static byte[] decodePem(String pem) { return Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "")); }
  private List<Map<String, Object>> legacyDependencies(String alias) throws Exception {
    Path database = dataDir.resolve("castrelsign.sqlite");
    if (!Files.exists(database)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      for (String table : CA.equals(alias) ? List.of("issued_certificates", "application_certificates") : List.of("agent_releases")) {
        try (PreparedStatement exists = connection.prepareStatement("select name from sqlite_master where type='table' and name=?")) {
          exists.setString(1, table); try (var rs = exists.executeQuery()) { if (!rs.next()) continue; }
        }
        String query = CA.equals(alias) ? "select serial_number,not_after from " + table : "select id from agent_releases where status<>'REVOKED'";
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(query)) {
          while (rs.next()) { Map<String, Object> row = new HashMap<>();
            row.put("reference", CA.equals(alias) ? "certificate:" + rs.getString(1) : "legacy-release:" + rs.getLong(1));
            row.put("expires_at", CA.equals(alias) ? rs.getString(2) : null); result.add(row);
          }
        }
      }
    }
    return result;
  }
  private static void verifyPair(KeyPair pair, String algorithm) throws Exception {
    Signature sig = Signature.getInstance(algorithm); byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
    sig.initSign(pair.getPrivate()); sig.update(challenge); byte[] signed = sig.sign(); sig.initVerify(pair.getPublic()); sig.update(challenge);
    if (!sig.verify(signed)) throw new GeneralSecurityException("legacy key pair mismatch");
  }
  public static void requireP256(PublicKey key) throws Exception {
    if (!(key instanceof java.security.interfaces.ECPublicKey ec)) throw new IllegalArgumentException("EC P-256 key required");
    AlgorithmParameters params = AlgorithmParameters.getInstance("EC"); params.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec expected = params.getParameterSpec(ECParameterSpec.class), actual = ec.getParams();
    if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator())
        || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor()) throw new IllegalArgumentException("only EC P-256 is allowed");
  }
  private X509Certificate rootCertificate(KeyPair pair, Duration lifetime, int version) throws Exception {
    BouncyCastleSupport.install(); Instant now = clock.instant();
    X500Name name = new X500Name("CN=CastrelSign Root CA v" + version + ",O=Castrelyx,OU=CastrelSign");
    var builder = new JcaX509v3CertificateBuilder(name, new java.math.BigInteger(159, new SecureRandom()).add(java.math.BigInteger.ONE),
        Date.from(now.minusSeconds(60)), Date.from(now.plus(lifetime)), name, pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(Extension.subjectKeyIdentifier, false, new JcaX509ExtensionUtils().createSubjectKeyIdentifier(pair.getPublic()));
    return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
  }
  private static void requireReason(String reason) { if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("reason must contain 1-500 characters (no secrets)"); }
  private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
  private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
  private static ResponseStatusException unavailable(Exception error) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KMS unavailable; inspect server logs and restore integrity", error); }
  @Override public synchronized void close() throws Exception {
    if (!closed) {
      closed = true;
      try { db.close(); }
      finally { processLock.release(); lockChannel.close(); backend.close(); Arrays.fill(master, (byte) 0); }
    }
  }
}
