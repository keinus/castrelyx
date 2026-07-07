package org.castrelyx.castrelvault.secret;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.auth.AdminPrincipal;
import org.castrelyx.castrelvault.crypto.EnvelopeCrypto;
import org.castrelyx.castrelvault.crypto.EnvelopeCrypto.EncryptedPayload;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SecretService {
  private final JdbcTemplate jdbcTemplate;
  private final EnvelopeCrypto crypto;
  private final ObjectMapper objectMapper;
  private final AuditService auditService;

  public SecretService(JdbcTemplate jdbcTemplate, EnvelopeCrypto crypto, ObjectMapper objectMapper, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.crypto = crypto;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
  }

  public List<SecretResponse> list() {
    return jdbcTemplate.query("""
        select s.id, s.path, s.display_name, s.type, s.tags_json, s.description, s.enabled, s.deleted,
               s.created_at, s.updated_at, s.current_version_id, v.version_number
        from vault_secrets s
        left join vault_secret_versions v on v.id = s.current_version_id
        where s.deleted = 0
        order by s.path
        """, (rs, rowNum) -> response(new SecretRow(
            rs.getString("id"),
            rs.getString("path"),
            rs.getString("display_name"),
            SecretType.valueOf(rs.getString("type")),
            tags(rs.getString("tags_json")),
            rs.getString("description"),
            rs.getInt("enabled") == 1,
            rs.getInt("deleted") == 1,
            rs.getString("created_at"),
            rs.getString("updated_at"),
            longOrNull(rs.getLong("current_version_id"), rs.wasNull()),
            intOrNull(rs.getInt("version_number"), rs.wasNull()))));
  }

  public SecretSummary summary() {
    Integer total = jdbcTemplate.queryForObject("select count(*) from vault_secrets where deleted = 0", Integer.class);
    Integer enabled = jdbcTemplate.queryForObject("select count(*) from vault_secrets where deleted = 0 and enabled = 1", Integer.class);
    Integer disabled = jdbcTemplate.queryForObject("select count(*) from vault_secrets where deleted = 0 and enabled = 0", Integer.class);
    Integer deleted = jdbcTemplate.queryForObject("select count(*) from vault_secrets where deleted = 1", Integer.class);
    Integer versions = jdbcTemplate.queryForObject("select count(*) from vault_secret_versions", Integer.class);
    return new SecretSummary(
        total == null ? 0 : total,
        enabled == null ? 0 : enabled,
        disabled == null ? 0 : disabled,
        deleted == null ? 0 : deleted,
        versions == null ? 0 : versions);
  }

  public SecretResponse get(String id) {
    return response(secretById(id));
  }

  public List<SecretVersionResponse> versions(String id) {
    SecretRow secret = secretById(id);
    return jdbcTemplate.query("""
        select id, version_number, key_id, payload_content_hash, created_at, creator_principal
        from vault_secret_versions
        where secret_id = ?
        order by version_number desc
        """, (rs, rowNum) -> new SecretVersionResponse(
            rs.getLong("id"),
            rs.getInt("version_number"),
            rs.getString("key_id"),
            rs.getString("payload_content_hash"),
            rs.getString("created_at"),
            rs.getString("creator_principal"),
            secret.currentVersionId() != null && secret.currentVersionId() == rs.getLong("id")), id);
  }

  public SecretResponse create(CreateSecretRequest request, AdminPrincipal actor) {
    SecretType type = request.type() == null ? SecretType.GENERIC : request.type();
    validatePayload(type, request.payload());
    String id = UUID.randomUUID().toString();
    String path = validatePath(request.path());
    String displayName = request.displayName() == null || request.displayName().isBlank() ? path : request.displayName().trim();
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into vault_secrets(id, path, display_name, type, tags_json, description, enabled, deleted, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, 1, 0, ?, ?)
        """, id, path, displayName, type.name(), tagsJson(request.tags()), blankToNull(request.description()), now, now);
    VersionRecord version = createVersion(id, 1, request.payload(), actor.username());
    jdbcTemplate.update("update vault_secrets set current_version_id = ?, updated_at = ? where id = ?",
        version.id(), Instant.now().toString(), id);
    auditService.record("ADMIN", actor.username(), path, version.versionNumber(), "CREATE_SECRET", "ALLOWED", null, null);
    return get(id);
  }

  public SecretResponse updateMetadata(String id, UpdateSecretRequest request, AdminPrincipal actor) {
    SecretRow current = secretById(id);
    String displayName = request.displayName() == null || request.displayName().isBlank() ? current.displayName() : request.displayName().trim();
    String description = request.description() == null ? current.description() : blankToNull(request.description());
    String tags = request.tags() == null ? tagsJson(current.tags()) : tagsJson(request.tags());
    jdbcTemplate.update("""
        update vault_secrets
        set display_name = ?, description = ?, tags_json = ?, updated_at = ?
        where id = ? and deleted = 0
        """, displayName, description, tags, Instant.now().toString(), id);
    auditService.record("ADMIN", actor.username(), current.path(), current.currentVersionNumber(), "UPDATE_SECRET_METADATA", "ALLOWED", null, null);
    return get(id);
  }

  public SecretResponse rotate(String id, RotateSecretRequest request, AdminPrincipal actor) {
    SecretRow secret = secretById(id);
    validatePayload(secret.type(), request.payload());
    ensureEnabled(secret);
    int nextVersion = nextVersionNumber(id);
    VersionRecord version = createVersion(id, nextVersion, request.payload(), actor.username());
    jdbcTemplate.update("update vault_secrets set current_version_id = ?, updated_at = ? where id = ?",
        version.id(), Instant.now().toString(), id);
    auditService.record("ADMIN", actor.username(), secret.path(), version.versionNumber(), "ROTATE_SECRET", "ALLOWED", null, null);
    return get(id);
  }

  public SecretResponse setEnabled(String id, boolean enabled, AdminPrincipal actor) {
    SecretRow secret = secretById(id);
    jdbcTemplate.update("update vault_secrets set enabled = ?, updated_at = ? where id = ? and deleted = 0",
        enabled ? 1 : 0, Instant.now().toString(), id);
    auditService.record("ADMIN", actor.username(), secret.path(), secret.currentVersionNumber(),
        enabled ? "ENABLE_SECRET" : "DISABLE_SECRET", "ALLOWED", null, null);
    return get(id);
  }

  public void delete(String id, AdminPrincipal actor) {
    SecretRow secret = secretById(id);
    jdbcTemplate.update("update vault_secrets set deleted = 1, enabled = 0, updated_at = ? where id = ?",
        Instant.now().toString(), id);
    auditService.record("ADMIN", actor.username(), secret.path(), secret.currentVersionNumber(), "DELETE_SECRET", "ALLOWED", null, null);
  }

  public DecryptedSecret reveal(String id) {
    SecretRow secret = secretById(id);
    ensureEnabled(secret);
    VersionPayload version = currentVersion(secret);
    return new DecryptedSecret(secret.id(), secret.path(), version.versionNumber(), decrypt(version));
  }

  public DecryptedSecret resolveByPath(String path) {
    SecretRow secret = secretByPath(path);
    ensureEnabled(secret);
    VersionPayload version = currentVersion(secret);
    return new DecryptedSecret(secret.id(), secret.path(), version.versionNumber(), decrypt(version));
  }

  public DecryptedSecret resolveById(String id) {
    return reveal(id);
  }

  private VersionRecord createVersion(String secretId, int versionNumber, JsonNode payload, String creator) {
    byte[] plaintext = payloadBytes(payload);
    EncryptedPayload encrypted = crypto.encrypt(plaintext);
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into vault_secret_versions(secret_id, version_number, key_id, wrapped_dek_nonce, encrypted_dek,
                                           encryption_nonce, ciphertext, payload_content_hash, created_at, creator_principal)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, secretId);
      ps.setInt(2, versionNumber);
      ps.setString(3, encrypted.keyId());
      ps.setBytes(4, encrypted.wrappedKeyNonce());
      ps.setBytes(5, encrypted.encryptedDataKey());
      ps.setBytes(6, encrypted.encryptionNonce());
      ps.setBytes(7, encrypted.ciphertext());
      ps.setString(8, encrypted.payloadContentHash());
      ps.setString(9, Instant.now().toString());
      ps.setString(10, creator);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("failed to store secret version");
    }
    return new VersionRecord(key.longValue(), versionNumber, encrypted.keyId());
  }

  private SecretRow secretById(String id) {
    List<SecretRow> rows = jdbcTemplate.query("""
        select s.id, s.path, s.display_name, s.type, s.tags_json, s.description, s.enabled, s.deleted,
               s.created_at, s.updated_at, s.current_version_id, v.version_number
        from vault_secrets s
        left join vault_secret_versions v on v.id = s.current_version_id
        where s.id = ? and s.deleted = 0
        """, (rs, rowNum) -> new SecretRow(
            rs.getString("id"),
            rs.getString("path"),
            rs.getString("display_name"),
            SecretType.valueOf(rs.getString("type")),
            tags(rs.getString("tags_json")),
            rs.getString("description"),
            rs.getInt("enabled") == 1,
            rs.getInt("deleted") == 1,
            rs.getString("created_at"),
            rs.getString("updated_at"),
            longOrNull(rs.getLong("current_version_id"), rs.wasNull()),
            intOrNull(rs.getInt("version_number"), rs.wasNull())), id);
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "secret was not found");
    }
    return rows.getFirst();
  }

  private SecretRow secretByPath(String path) {
    List<SecretRow> rows = jdbcTemplate.query("""
        select s.id, s.path, s.display_name, s.type, s.tags_json, s.description, s.enabled, s.deleted,
               s.created_at, s.updated_at, s.current_version_id, v.version_number
        from vault_secrets s
        left join vault_secret_versions v on v.id = s.current_version_id
        where s.path = ? and s.deleted = 0
        """, (rs, rowNum) -> new SecretRow(
            rs.getString("id"),
            rs.getString("path"),
            rs.getString("display_name"),
            SecretType.valueOf(rs.getString("type")),
            tags(rs.getString("tags_json")),
            rs.getString("description"),
            rs.getInt("enabled") == 1,
            rs.getInt("deleted") == 1,
            rs.getString("created_at"),
            rs.getString("updated_at"),
            longOrNull(rs.getLong("current_version_id"), rs.wasNull()),
            intOrNull(rs.getInt("version_number"), rs.wasNull())), validatePath(path));
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "secret was not found");
    }
    return rows.getFirst();
  }

  private VersionPayload currentVersion(SecretRow secret) {
    if (secret.currentVersionId() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "secret has no current version");
    }
    return jdbcTemplate.queryForObject("""
        select id, secret_id, version_number, key_id, wrapped_dek_nonce, encrypted_dek,
               encryption_nonce, ciphertext, payload_content_hash
        from vault_secret_versions
        where id = ?
        """, (rs, rowNum) -> new VersionPayload(
            rs.getLong("id"),
            rs.getString("secret_id"),
            rs.getInt("version_number"),
            rs.getString("key_id"),
            rs.getBytes("wrapped_dek_nonce"),
            rs.getBytes("encrypted_dek"),
            rs.getBytes("encryption_nonce"),
            rs.getBytes("ciphertext"),
            rs.getString("payload_content_hash")), secret.currentVersionId());
  }

  private JsonNode decrypt(VersionPayload version) {
    try {
      byte[] plaintext = crypto.decrypt(version.keyId(), version.wrappedDekNonce(), version.encryptedDek(),
          version.encryptionNonce(), version.ciphertext());
      return objectMapper.readTree(plaintext);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "secret version is not decryptable");
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "secret payload is invalid");
    }
  }

  private int nextVersionNumber(String secretId) {
    Integer next = jdbcTemplate.queryForObject("""
        select coalesce(max(version_number), 0) + 1
        from vault_secret_versions
        where secret_id = ?
        """, Integer.class, secretId);
    return next == null ? 1 : next;
  }

  private byte[] payloadBytes(JsonNode payload) {
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (Exception e) {
      throw new IllegalArgumentException("secret payload is invalid", e);
    }
  }

  private SecretResponse response(SecretRow secret) {
    return new SecretResponse(
        secret.id(),
        secret.path(),
        secret.displayName(),
        secret.type(),
        secret.tags(),
        secret.description(),
        secret.enabled(),
        secret.createdAt(),
        secret.updatedAt(),
        secret.currentVersionNumber(),
        Map.of("configured", secret.currentVersionId() != null, "masked", "********"));
  }

  private static void ensureEnabled(SecretRow secret) {
    if (!secret.enabled() || secret.deleted()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "disabled or deleted secrets cannot be revealed or resolved");
    }
  }

  private static void validatePayload(SecretType type, JsonNode payload) {
    if (payload == null || payload.isNull() || payload.isMissingNode()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret payload is required");
    }
    if (!payload.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret payload must be a JSON object");
    }
    SecretType normalized = type == null ? SecretType.GENERIC : type;
    switch (normalized) {
      case API_TOKEN, SSH_KEY, GENERIC -> requireAny(payload, "value", "token", "privateKey");
      case SERVER_LOGIN, WINDOWS_LOGIN -> requireAny(payload, "password", "value");
      case SNMP_V2C -> requireAny(payload, "community", "value");
      case SNMP_V3 -> requireAny(payload, "username", "user", "value");
      case DB_PASSWORD -> requireAny(payload, "password", "value");
      case CERTIFICATE_KEY -> requireAny(payload, "privateKey", "key", "value");
      default -> requireAny(payload, "value");
    }
  }

  private static void requireAny(JsonNode payload, String... fields) {
    for (String field : fields) {
      JsonNode node = payload.get(field);
      if (node != null && !node.isNull() && (!node.isTextual() || !node.asText().isBlank())) {
        return;
      }
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret payload is missing required field");
  }

  private static String validatePath(String path) {
    if (path == null || path.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret path is required");
    }
    String trimmed = path.trim();
    if (!trimmed.startsWith("/") || trimmed.contains("..")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret path must start with / and not contain ..");
    }
    return trimmed;
  }

  private String tagsJson(List<String> tags) {
    try {
      return objectMapper.writeValueAsString(cleanTags(tags));
    } catch (Exception e) {
      throw new IllegalArgumentException("tags are invalid", e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> tags(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, List.class);
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<String> cleanTags(List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return List.of();
    }
    List<String> cleaned = new ArrayList<>();
    for (String tag : tags) {
      if (tag != null && !tag.isBlank()) {
        cleaned.add(tag.trim());
      }
    }
    return List.copyOf(cleaned);
  }

  private static Long longOrNull(long value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private static Integer intOrNull(int value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record SecretRow(String id, String path, String displayName, SecretType type, List<String> tags,
      String description, boolean enabled, boolean deleted, String createdAt, String updatedAt,
      Long currentVersionId, Integer currentVersionNumber) {
  }

  private record VersionRecord(long id, int versionNumber, String keyId) {
  }

  private record VersionPayload(long id, String secretId, int versionNumber, String keyId, byte[] wrappedDekNonce,
      byte[] encryptedDek, byte[] encryptionNonce, byte[] ciphertext, String payloadContentHash) {
  }

  public record CreateSecretRequest(
      String path,
      String displayName,
      SecretType type,
      List<String> tags,
      String description,
      JsonNode payload) {
  }

  public record UpdateSecretRequest(String displayName, List<String> tags, String description) {
  }

  public record RotateSecretRequest(JsonNode payload) {
  }

  public record SecretSummary(
      int total,
      int enabled,
      int disabled,
      int deleted,
      int versions) {
  }

  public record SecretVersionResponse(
      long id,
      int version,
      String keyId,
      String payloadContentHash,
      String createdAt,
      String creatorPrincipal,
      boolean current) {
  }

  public record SecretResponse(
      String id,
      String path,
      String displayName,
      SecretType type,
      List<String> tags,
      String description,
      boolean enabled,
      String createdAt,
      String updatedAt,
      Integer currentVersion,
      Map<String, Object> payload) {
  }

  public record DecryptedSecret(String id, String path, int version, JsonNode payload) {
    public Map<String, Object> asResponse() {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("id", id);
      response.put("path", path);
      response.put("version", version);
      response.put("payload", payload);
      return response;
    }
  }
}
