package org.castrelyx.castrelvault.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@DependsOn("schemaInitializer")
public class AdminSessionService implements InitializingBean {
  public static final String SESSION_COOKIE = "CASTRELVAULT_SESSION";
  public static final String CSRF_COOKIE = "CASTRELVAULT_CSRF";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbcTemplate;
  private final CastrelVaultProperties properties;
  private final AuditService auditService;
  private final SecurityAttemptService attempts;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

  public AdminSessionService(JdbcTemplate jdbcTemplate, CastrelVaultProperties properties, AuditService auditService,
      SecurityAttemptService attempts) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.auditService = auditService;
    this.attempts = attempts;
  }

  @Override
  public void afterPropertiesSet() {
    Integer count = jdbcTemplate.queryForObject("select count(*) from vault_users where role = 'ADMIN'", Integer.class);
    if (count != null && count > 0) {
      return;
    }
    String username = requireText(properties.getBootstrapAdminUsername(), "CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME is required");
    String password = requireText(properties.getBootstrapAdminPassword(), "CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD is required");
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into vault_users(username, password_hash, role, require_password_change, enabled, created_at, updated_at)
        values (?, ?, 'ADMIN', 1, 1, ?, ?)
        """, username, passwordEncoder.encode(password), now, now);
  }

  public IssuedSession login(String username, String password, HttpServletRequest request) {
    String attemptKey = attemptKey("login", username, request);
    attempts.requireAllowed(attemptKey);
    List<UserRecord> users = jdbcTemplate.query("""
        select id, username, password_hash, role, require_password_change, enabled
        from vault_users
        where username = ?
        """, (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getInt("require_password_change") == 1,
            rs.getInt("enabled") == 1),
        username == null ? "" : username.trim());
    if (users.isEmpty() || !users.getFirst().enabled()
        || !passwordEncoder.matches(password == null ? "" : password, users.getFirst().passwordHash())) {
      attempts.recordFailure(attemptKey);
      auditService.record("ADMIN", username, null, null, "LOGIN", "DENIED", "invalid credentials", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
    UserRecord user = users.getFirst();
    String token = newSessionToken();
    String csrfToken = newSessionToken();
    String expiresAt = Instant.now().plusSeconds(Math.max(60, properties.getSessionTtlSeconds())).toString();
    jdbcTemplate.update("""
        insert into vault_sessions(token_hash, csrf_token_hash, user_id, expires_at, created_at)
        values (?, ?, ?, ?, ?)
        """, hash(token), hash(csrfToken), user.id(), expiresAt, Instant.now().toString());
    attempts.recordSuccess(attemptKey);
    auditService.record("ADMIN", user.username(), null, null, "LOGIN", "ALLOWED", null, request);
    return new IssuedSession(token, csrfToken, user.username(), user.requirePasswordChange(), Instant.parse(expiresAt));
  }

  public AdminPrincipal requireAdmin(HttpServletRequest request) {
    SessionCredential credential = credentialFrom(request);
    if (credential.token() == null || credential.token().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin session is required");
    }
    String tokenHash = hash(credential.token());
    List<AdminPrincipal> sessions = jdbcTemplate.query("""
        select s.id as session_id, s.csrf_token_hash, u.id as user_id, u.username, u.role, u.require_password_change, s.expires_at
        from vault_sessions s
        join vault_users u on u.id = s.user_id
        where s.token_hash = ? and u.enabled = 1
        """, (rs, rowNum) -> {
          Instant expiresAt = Instant.parse(rs.getString("expires_at"));
          if (!expiresAt.isAfter(Instant.now())) {
            return null;
          }
          return new AdminPrincipal(
              rs.getLong("session_id"),
              rs.getLong("user_id"),
              rs.getString("username"),
              rs.getString("role"),
              rs.getInt("require_password_change") == 1,
              rs.getString("csrf_token_hash"));
        }, tokenHash).stream().filter(java.util.Objects::nonNull).toList();
    if (sessions.isEmpty()) {
      jdbcTemplate.update("delete from vault_sessions where token_hash = ?", tokenHash);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin session is invalid or expired");
    }
    AdminPrincipal principal = sessions.getFirst();
    validateCsrf(request, credential, principal);
    return principal;
  }

  public AdminPrincipal requireReadyAdmin(HttpServletRequest request) {
    AdminPrincipal principal = requireAdmin(request);
    principal.requireReady();
    return principal;
  }

  public void changePassword(AdminPrincipal principal, String currentPassword, String newPassword, HttpServletRequest request) {
    UserRecord user = user(principal.userId());
    if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, user.passwordHash())) {
      auditService.record("ADMIN", principal.username(), null, null, "CHANGE_PASSWORD", "DENIED", "current password mismatch", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "current password is invalid");
    }
    if (newPassword == null || newPassword.length() < 12) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new password must be at least 12 characters");
    }
    jdbcTemplate.update("""
        update vault_users
        set password_hash = ?, require_password_change = 0, updated_at = ?
        where id = ?
        """, passwordEncoder.encode(newPassword), Instant.now().toString(), principal.userId());
    auditService.record("ADMIN", principal.username(), null, null, "CHANGE_PASSWORD", "ALLOWED", null, request);
  }

  public void reauthenticate(AdminPrincipal principal, String password) {
    reauthenticate(principal, password, null);
  }

  public void reauthenticate(AdminPrincipal principal, String password, HttpServletRequest request) {
    String attemptKey = attemptKey("reveal", principal.username(), request);
    attempts.requireAllowed(attemptKey);
    UserRecord user = user(principal.userId());
    if (!passwordEncoder.matches(password == null ? "" : password, user.passwordHash())) {
      attempts.recordFailure(attemptKey);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "current password is invalid");
    }
    attempts.recordSuccess(attemptKey);
  }

  public void logout(AdminPrincipal principal, HttpServletRequest request) {
    jdbcTemplate.update("delete from vault_sessions where id = ?", principal.sessionId());
    auditService.record("ADMIN", principal.username(), null, null, "LOGOUT", "ALLOWED", null, request);
  }

  public boolean rawSessionTokenStored(String token) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from vault_sessions where token_hash = ?", Integer.class, token);
    return count != null && count > 0;
  }

  private UserRecord user(long userId) {
    return jdbcTemplate.queryForObject("""
        select id, username, password_hash, role, require_password_change, enabled
        from vault_users
        where id = ?
        """, (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getInt("require_password_change") == 1,
            rs.getInt("enabled") == 1), userId);
  }

  private static SessionCredential credentialFrom(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return new SessionCredential(authorization.substring("Bearer ".length()), true);
    }
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (SESSION_COOKIE.equals(cookie.getName())) {
          return new SessionCredential(cookie.getValue(), false);
        }
      }
    }
    return new SessionCredential(null, false);
  }

  private static void validateCsrf(HttpServletRequest request, SessionCredential credential, AdminPrincipal principal) {
    if (credential.bearer() || !unsafeMethod(request.getMethod())) {
      return;
    }
    String header = request.getHeader("X-CSRF-Token");
    if (header == null || header.isBlank() || !MessageDigest.isEqual(hash(header).getBytes(StandardCharsets.UTF_8),
        principal.csrfToken().getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "csrf token is required");
    }
  }

  private static boolean unsafeMethod(String method) {
    return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);
  }

  private static String attemptKey(String scope, String subject, HttpServletRequest request) {
    String remote = request == null ? "local" : request.getRemoteAddr();
    String normalized = subject == null || subject.isBlank() ? "unknown" : subject.trim();
    return scope + ":" + normalized + ":" + remote;
  }

  private static String newSessionToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "csv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("failed to hash session token", e);
    }
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(message);
    }
    return value.trim();
  }

  private record UserRecord(long id, String username, String passwordHash, String role,
      boolean requirePasswordChange, boolean enabled) {
  }

  private record SessionCredential(String token, boolean bearer) {
  }

  public record IssuedSession(String token, String csrfToken, String username, boolean requirePasswordChange, Instant expiresAt) {
  }
}
