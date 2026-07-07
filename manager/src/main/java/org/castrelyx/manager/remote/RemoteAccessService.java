package org.castrelyx.manager.remote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.ForbiddenException;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.castrelyx.manager.secret.SecretCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RemoteAccessService {
  private final JdbcTemplate jdbcTemplate;
  private final SecretCrypto secretCrypto;
  private final CastrelSignClient castrelSignClient;
  private final ManagerProperties properties;

  public RemoteAccessService(JdbcTemplate jdbcTemplate, SecretCrypto secretCrypto, CastrelSignClient castrelSignClient,
      ManagerProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.secretCrypto = secretCrypto;
    this.castrelSignClient = castrelSignClient;
    this.properties = properties;
  }

  public RemoteAccessSession createSession(AuthUser user, RemoteAccessRequest request) {
    if (!properties.remoteAccess().enabled()) {
      throw new IllegalStateException("remote access is disabled");
    }
    ResolvedTarget target = resolveTarget(request == null ? new RemoteAccessRequest(null, null, null, null, null, null) : request);
    var keys = SshKeyPairFactory.generate();
    String sessionId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    Instant keyExpiresAt = now.plus(Duration.ofSeconds(properties.remoteAccess().keyTtlSeconds()));
    Instant sessionExpiresAt = now.plus(Duration.ofSeconds(properties.remoteAccess().sessionTtlSeconds()));
    Map<String, Object> payload = Map.of(
        "username", target.sshUser(),
        "publicKey", keys.publicKey(),
        "sessionId", sessionId,
        "expiresAt", keyExpiresAt.toString());
    Map<?, ?> task = castrelSignClient.createAgentRemoteTask(target.agentId(), "ssh_authorize_key", payload,
        properties.remoteAccess().keyTtlSeconds());
    String taskId = stringValue(task.get("taskId"));
    jdbcTemplate.update("""
        insert into remote_access_sessions(
          id, asset_id, asset_uid, agent_id, ssh_user, target_host, target_port, status,
          public_key, public_key_fingerprint, encrypted_private_key, authorization_task_id,
          created_by, created_by_username, created_at, expires_at)
        values (?, ?, ?, ?, ?, ?, ?, 'AUTHORIZING', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        sessionId,
        target.assetId(),
        target.assetUid(),
        target.agentId(),
        target.sshUser(),
        target.host(),
        target.port(),
        keys.publicKey(),
        keys.fingerprint(),
        secretCrypto.encrypt(keys.privateKeyPem()),
        taskId,
        user == null ? null : user.id(),
        user == null ? null : user.username(),
        Timestamp.from(now),
        Timestamp.from(sessionExpiresAt));
    audit(sessionId, target.assetId(), target.agentId(), "SESSION_CREATED",
        "created WebSSH session and queued SSH key authorization task " + taskId, user);
    return getSession(sessionId);
  }

  public RemoteAccessSession getSession(String sessionId) {
    var sessions = jdbcTemplate.query("""
        select id, asset_id, asset_uid, agent_id, ssh_user, target_host, target_port, status,
               public_key_fingerprint, authorization_task_id, revoke_task_id, created_by, created_by_username,
               created_at, expires_at, connected_at, closed_at, close_reason, last_error
        from remote_access_sessions
        where id = ?
        """, RemoteAccessService::session, sessionId);
    if (sessions.isEmpty()) {
      throw new IllegalArgumentException("remote access session not found");
    }
    return sessions.getFirst();
  }

  public RemoteAccessSession requireSessionAccess(String sessionId, AuthUser user) {
    RemoteAccessSession session = getSession(sessionId);
    if (user == null) {
      throw new ForbiddenException("remote access session requires an authenticated user");
    }
    if (user.role() == Role.ADMIN) {
      return session;
    }
    if (session.createdBy() != null && session.createdBy().equals(user.id())) {
      return session;
    }
    throw new ForbiddenException("remote access session belongs to another user");
  }

  public String privateKey(String sessionId) {
    String encrypted = jdbcTemplate.queryForObject(
        "select encrypted_private_key from remote_access_sessions where id = ?",
        String.class,
        sessionId);
    return secretCrypto.decrypt(encrypted);
  }

  public RemoteAccessSession waitForAuthorization(String sessionId) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(properties.remoteAccess().authorizationTimeoutSeconds()));
    RemoteAccessSession session = getSession(sessionId);
    while (Instant.now().isBefore(deadline)) {
      session = refreshAuthorization(session);
      if ("AUTHORIZED".equals(session.status())) {
        return session;
      }
      if ("FAILED".equals(session.status()) || "CLOSED".equals(session.status())) {
        throw new IllegalStateException(session.lastError() == null ? "SSH authorization failed" : session.lastError());
      }
      Thread.sleep(1000);
    }
    failSession(sessionId, "SSH authorization timed out");
    throw new IllegalStateException("SSH authorization timed out");
  }

  public void markConnected(String sessionId) {
    jdbcTemplate.update("""
        update remote_access_sessions
        set status = 'CONNECTED', connected_at = ?, last_error = null
        where id = ?
        """, Timestamp.from(Instant.now()), sessionId);
    RemoteAccessSession session = getSession(sessionId);
    audit(sessionId, session.assetId(), session.agentId(), "SSH_CONNECTED", "WebSSH PTY connected", null);
  }

  public void closeSession(String sessionId, String reason) {
    RemoteAccessSession session = getSession(sessionId);
    if (!"CLOSED".equals(session.status())) {
      jdbcTemplate.update("""
          update remote_access_sessions
          set status = 'CLOSED', closed_at = ?, close_reason = ?
          where id = ?
          """, Timestamp.from(Instant.now()), reason, sessionId);
    }
    queueRevoke(session, reason == null ? "session closed" : reason);
  }

  public boolean allowUnknownHostKeys() {
    return properties.remoteAccess().allowUnknownHostKeys();
  }

  private RemoteAccessSession refreshAuthorization(RemoteAccessSession session) {
    if (!"AUTHORIZING".equals(session.status()) || session.authorizationTaskId() == null) {
      return session;
    }
    Map<?, ?> task = castrelSignClient.getAgentRemoteTask(session.authorizationTaskId());
    String status = stringValue(task.get("status"));
    if ("COMPLETED".equals(status)) {
      jdbcTemplate.update("update remote_access_sessions set status = 'AUTHORIZED', last_error = null where id = ?", session.id());
      audit(session.id(), session.assetId(), session.agentId(), "KEY_AUTHORIZED",
          "agent installed temporary SSH key " + session.publicKeyFingerprint(), null);
      return getSession(session.id());
    }
    if ("FAILED".equals(status)) {
      String message = stringValue(task.get("errorMessage"));
      failSession(session.id(), message == null ? "agent failed to install temporary SSH key" : message);
      return getSession(session.id());
    }
    if (Instant.now().isAfter(session.expiresAt())) {
      failSession(session.id(), "remote access session expired before authorization");
      return getSession(session.id());
    }
    return session;
  }

  private void failSession(String sessionId, String message) {
    jdbcTemplate.update("""
        update remote_access_sessions
        set status = 'FAILED', last_error = ?, closed_at = ?
        where id = ?
        """, message, Timestamp.from(Instant.now()), sessionId);
    RemoteAccessSession session = getSession(sessionId);
    audit(sessionId, session.assetId(), session.agentId(), "SESSION_FAILED", message, null);
  }

  private void queueRevoke(RemoteAccessSession session, String reason) {
    if (session.revokeTaskId() != null || session.agentId() == null) {
      return;
    }
    try {
      Map<?, ?> task = castrelSignClient.createAgentRemoteTask(session.agentId(), "ssh_revoke_key", Map.of(
          "username", session.sshUser(),
          "sessionId", session.id()), 3600);
      String taskId = stringValue(task.get("taskId"));
      jdbcTemplate.update("update remote_access_sessions set revoke_task_id = ? where id = ?", taskId, session.id());
      audit(session.id(), session.assetId(), session.agentId(), "KEY_REVOKE_QUEUED",
          "queued SSH key revoke task " + taskId + ": " + reason, null);
    } catch (RuntimeException exception) {
      jdbcTemplate.update("update remote_access_sessions set last_error = ? where id = ?",
          "failed to queue revoke task: " + exception.getMessage(), session.id());
    }
  }

  private ResolvedTarget resolveTarget(RemoteAccessRequest request) {
    AssetRow asset = request.assetId() == null ? null : assetById(request.assetId());
    if (asset == null && request.assetUid() != null && !request.assetUid().isBlank()) {
      asset = assetByUid(request.assetUid());
    }
    String agentId = firstNonBlank(request.agentId(), asset == null ? null : agentIdForAsset(asset.id()), asset == null ? null : asset.assetUid());
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agentId or agent-backed asset is required");
    }
    String host = firstNonBlank(request.targetHost(), asset == null ? null : asset.managementIp(), asset == null ? null : asset.assetUid(), agentId);
    String sshUser = firstNonBlank(request.sshUser(), properties.remoteAccess().defaultSshUser());
    int port = request.targetPort() == null || request.targetPort() <= 0 ? properties.remoteAccess().defaultPort() : request.targetPort();
    return new ResolvedTarget(asset == null ? null : asset.id(), asset == null ? request.assetUid() : asset.assetUid(), agentId, host, port, sshUser);
  }

  private AssetRow assetById(long id) {
    var rows = jdbcTemplate.query("""
        select id, asset_uid, management_ip
        from assets
        where id = ?
        """, (rs, rowNum) -> new AssetRow(rs.getLong("id"), rs.getString("asset_uid"), rs.getString("management_ip")), id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private AssetRow assetByUid(String assetUid) {
    var rows = jdbcTemplate.query("""
        select id, asset_uid, management_ip
        from assets
        where asset_uid = ?
        """, (rs, rowNum) -> new AssetRow(rs.getLong("id"), rs.getString("asset_uid"), rs.getString("management_ip")), assetUid);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String agentIdForAsset(long assetId) {
    var rows = jdbcTemplate.query("""
        select source_id
        from asset_source_bindings
        where asset_id = ? and source_type = 'AGENT'
        order by last_seen_at desc, id desc
        limit 1
        """, (rs, rowNum) -> rs.getString("source_id"), assetId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void audit(String sessionId, Long assetId, String agentId, String eventType, String message, AuthUser user) {
    jdbcTemplate.update("""
        insert into remote_access_audit_events(session_id, asset_id, agent_id, event_type, message, created_by, created_by_username, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """, sessionId, assetId, agentId, eventType, message, user == null ? null : user.id(),
        user == null ? null : user.username(), Timestamp.from(Instant.now()));
  }

  private static RemoteAccessSession session(ResultSet rs, int rowNum) throws SQLException {
    return new RemoteAccessSession(
        rs.getString("id"),
        nullableLong(rs, "asset_id"),
        rs.getString("asset_uid"),
        rs.getString("agent_id"),
        rs.getString("ssh_user"),
        rs.getString("target_host"),
        rs.getInt("target_port"),
        rs.getString("status"),
        rs.getString("public_key_fingerprint"),
        rs.getString("authorization_task_id"),
        rs.getString("revoke_task_id"),
        nullableLong(rs, "created_by"),
        rs.getString("created_by_username"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("expires_at")),
        instant(rs.getTimestamp("connected_at")),
        instant(rs.getTimestamp("closed_at")),
        rs.getString("close_reason"),
        rs.getString("last_error"));
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  public record RemoteAccessRequest(
      Long assetId,
      String assetUid,
      String agentId,
      String targetHost,
      Integer targetPort,
      String sshUser) {
  }

  private record AssetRow(long id, String assetUid, String managementIp) {
  }

  private record ResolvedTarget(Long assetId, String assetUid, String agentId, String host, int port, String sshUser) {
  }
}
