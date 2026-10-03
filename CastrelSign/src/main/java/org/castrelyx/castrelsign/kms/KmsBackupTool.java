package org.castrelyx.castrelsign.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;

/** Deliberately offline: a running server holds the writer lock and prevents snapshots. */
public final class KmsBackupTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long MAX_ARCHIVE = 512L * 1024 * 1024;
  private KmsBackupTool() {}
  public static void run(String[] args) throws Exception {
    if (args.length != 8 || !Set.of("create", "restore").contains(args[1]))
      throw new IllegalArgumentException("usage: kms-backup create|restore DATA_DIR ARCHIVE BACKUP_KEY_FILE MASTER_KEY_FILE TENANT ENVIRONMENT");
    CastrelSignProperties properties = new CastrelSignProperties(); properties.setDataDir(Path.of(args[2]));
    properties.getKms().setMasterKeyFile(Path.of(args[5])); properties.getKms().setTenant(args[6]); properties.getKms().setEnvironment(args[7]);
    if ("create".equals(args[1])) create(properties, Path.of(args[3]), Path.of(args[4]));
    else restore(properties, Path.of(args[3]), Path.of(args[4]));
    System.out.println("KMS backup " + args[1] + " completed.");
  }
  public static void create(CastrelSignProperties properties, Path archive, Path backupKeyFile) throws Exception {
    Path root = properties.getDataDir().toAbsolutePath().normalize();
    if (!Files.exists(root.resolve("kms/initialized"))) throw new IllegalStateException("an initialized KMS is required");
    if (Files.exists(archive) || archive.toAbsolutePath().normalize().startsWith(root)) throw new IllegalArgumentException("use a new archive path outside the data directory");
    byte[] key = backupKey(backupKeyFile);
    KeyManagementService.assertNotOpen(root);
    try (var kms = KeyManagementService.open(properties)) {
      kms.health(); String id = UUID.randomUUID().toString(); kms.registerBackup(id);
      Map<String, byte[]> entries = new TreeMap<>();
      try (var paths = Files.walk(root)) {
        for (Path path : paths.toList()) {
          if (Files.isSymbolicLink(path)) throw new IllegalStateException("symlinks are not supported in a recovery snapshot");
          if (!Files.isRegularFile(path)) continue;
          String relative = root.relativize(path).toString().replace('\\', '/');
          if (relative.startsWith("secrets/") || relative.endsWith(".lock") || relative.endsWith("-wal") || relative.endsWith("-shm") || relative.endsWith("-journal")) continue;
          if (path.toAbsolutePath().normalize().equals(properties.getKms().getMasterKeyFile().toAbsolutePath().normalize())
              || path.toAbsolutePath().normalize().equals(backupKeyFile.toAbsolutePath().normalize())) throw new IllegalStateException("backup secrets must be outside data directory");
          if (Files.size(path) > MAX_ARCHIVE) throw new IllegalStateException("backup exceeds in-memory tool size limit");
          entries.put(relative, Files.readAllBytes(path));
          if (entries.values().stream().mapToLong(v -> v.length).sum() > MAX_ARCHIVE) throw new IllegalStateException("backup exceeds 512 MiB; use an offline volume snapshot");
        }
      }
      Path appDb = root.resolve("castrelsign.sqlite");
      if (Files.exists(appDb)) {
        Path snapshot = Files.createTempFile(root, ".backup-db-", ".sqlite"); Files.delete(snapshot);
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + appDb)) {
          try (PreparedStatement statement = db.prepareStatement("VACUUM INTO ?")) { statement.setString(1, snapshot.toString()); statement.execute(); }
          ProtectedFiles.restrict(snapshot); entries.put("castrelsign.sqlite", Files.readAllBytes(snapshot));
        } finally { Files.deleteIfExists(snapshot); }
      }
      Map<String, String> hashes = new TreeMap<>();
      for (var entry : entries.entrySet()) hashes.put(entry.getKey(), sha256(entry.getValue()));
      Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("format", 1); manifest.put("backupId", id);
      manifest.put("source", root.toString()); manifest.put("createdAt", Instant.now().toString()); manifest.put("tenant", properties.getKms().getTenant());
      manifest.put("environment", properties.getKms().getEnvironment()); manifest.put("checkpoint", Base64.getEncoder().encodeToString(kms.backupCheckpoint())); manifest.put("files", hashes);
      entries.put("backup-manifest.json", JSON.writeValueAsBytes(manifest));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
      }
      ProtectedFiles.write(archive.toAbsolutePath().normalize(), EncryptedSoftwareBackend.encrypt(key, bytes.toByteArray(), "CastrelSign-backup-v1"));
    } finally { Arrays.fill(key, (byte) 0); KeyManagementService.closeAt(root); }
  }
  public static void restore(CastrelSignProperties properties, Path archive, Path backupKeyFile) throws Exception {
    Path root = properties.getDataDir().toAbsolutePath().normalize(); KeyManagementService.assertNotOpen(root);
    if (Files.exists(root)) try (var files = Files.list(root)) { if (files.findAny().isPresent()) throw new IllegalStateException("restore target must be empty; never overwrite a live store"); }
    if (Files.size(archive) > MAX_ARCHIVE + 1024 * 1024) throw new IllegalArgumentException("backup archive is too large");
    byte[] key = backupKey(backupKeyFile), plain;
    try { plain = EncryptedSoftwareBackend.decrypt(key, Files.readAllBytes(archive), "CastrelSign-backup-v1"); }
    finally { Arrays.fill(key, (byte) 0); }
    Map<String, byte[]> entries = new TreeMap<>(); long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plain))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName(); Path destination = root.resolve(name).normalize();
        if (!destination.startsWith(root) || name.contains("\\") || name.contains(":") || name.startsWith("/") || entry.isDirectory()
            || name.startsWith("secrets/") || name.endsWith(".lock") || entries.containsKey(name)) throw new GeneralSecurityException("invalid backup path");
        byte[] content = zip.readNBytes((int) Math.min(MAX_ARCHIVE - total + 1, Integer.MAX_VALUE)); total += content.length;
        if (total > MAX_ARCHIVE) throw new GeneralSecurityException("expanded backup is too large");
        entries.put(name, content);
      }
    } finally { Arrays.fill(plain, (byte) 0); }
    byte[] manifestBytes = entries.remove("backup-manifest.json");
    if (manifestBytes == null) throw new GeneralSecurityException("missing backup manifest");
    var manifest = JSON.readTree(manifestBytes);
    if (manifest.path("format").asInt() != 1 || !root.toString().equals(manifest.path("source").asText())
        || !properties.getKms().getTenant().equals(manifest.path("tenant").asText())
        || !properties.getKms().getEnvironment().equals(manifest.path("environment").asText())) throw new GeneralSecurityException("backup target or security boundary mismatch");
    if (manifest.path("files").size() != entries.size() || !entries.containsKey("kms/keys.sqlite") || !entries.containsKey("kms/initialized")) throw new GeneralSecurityException("incomplete backup");
    for (var entry : entries.entrySet()) if (!sha256(entry.getValue()).equals(manifest.path("files").path(entry.getKey()).asText())) throw new GeneralSecurityException("backup file digest mismatch");
    Path master = properties.getKms().getMasterKeyFile().toAbsolutePath().normalize();
    Path checkpoint = master.resolveSibling(master.getFileName() + ".checkpoint");
    if (!Files.exists(master) || !Files.exists(checkpoint) || !MessageDigest.isEqual(Files.readAllBytes(checkpoint), Base64.getDecoder().decode(manifest.path("checkpoint").asText())))
      throw new GeneralSecurityException("latest external checkpoint must match snapshot; stale restore requires independent state reconciliation");
    Files.createDirectories(root); ProtectedFiles.restrict(root);
    ProtectedFiles.write(root.resolve(".restore-in-progress"), "incomplete\n".getBytes(StandardCharsets.UTF_8));
    for (var entry : entries.entrySet()) ProtectedFiles.write(root.resolve(entry.getKey()), entry.getValue());
    try (var kms = KeyManagementService.open(properties)) { kms.health(); }
    finally { KeyManagementService.closeAt(root); }
    Files.delete(root.resolve(".restore-in-progress"));
  }
  private static byte[] backupKey(Path path) throws Exception {
    byte[] key = Base64.getDecoder().decode(Files.readString(path).trim());
    if (key.length != 32) throw new IllegalArgumentException("backup key must be 32 base64-encoded random bytes");
    return key;
  }
  private static String sha256(byte[] content) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
}
