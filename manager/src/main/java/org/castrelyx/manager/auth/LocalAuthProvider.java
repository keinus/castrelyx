package org.castrelyx.manager.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LocalAuthProvider implements AuthProvider {
  private static final Duration SESSION_TTL = Duration.ofHours(12);
  private final JdbcTemplate jdbcTemplate;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public LocalAuthProvider(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean setupRequired() {
    Integer count = jdbcTemplate.queryForObject("select count(*) from users where role = 'ADMIN'", Integer.class);
    return count == null || count == 0;
  }

  public AuthUser createLocalUser(String username, String password, String displayName, Role role) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    if (password == null || password.length() < 8) {
      throw new IllegalArgumentException("password must be at least 8 characters");
    }
    jdbcTemplate.update("""
        insert into users(username, password_hash, display_name, role, enabled, created_at, updated_at)
        values (?, ?, ?, ?, true, ?, ?)
        """,
        username.trim(),
        passwordEncoder.encode(password),
        displayName,
        role.name(),
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
    Long id = jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, username.trim());
    return new AuthUser(id == null ? 0 : id, username.trim(), displayName, role);
  }

  @Override
  public AuthUser authenticate(String username, String password) {
    AuthRecord record = findByUsername(username);
    if (!record.enabled() || !passwordEncoder.matches(password == null ? "" : password, record.passwordHash())) {
      throw new AuthException("invalid username or password");
    }
    return record.toUser();
  }

  @Override
  public AuthUser currentUser(String sessionToken) {
    if (sessionToken == null || sessionToken.isBlank()) {
      throw new AuthException("missing session");
    }
    String hash = tokenHash(sessionToken);
    Instant now = Instant.now();
    var users = jdbcTemplate.query("""
        select u.id, u.username, u.display_name, u.role, u.enabled
        from user_sessions s
        join users u on u.id = s.user_id
        where s.session_token_hash = ? and s.expires_at > ?
        """,
        (rs, rowNum) -> new AuthUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            Role.valueOf(rs.getString("role"))),
        hash,
        Timestamp.from(now));
    if (users.isEmpty()) {
      throw new AuthException("invalid session");
    }
    return users.getFirst();
  }

  @Override
  public String createSession(AuthUser user) {
    String token = UUID.randomUUID() + "." + UUID.randomUUID();
    jdbcTemplate.update("""
        insert into user_sessions(session_token_hash, user_id, expires_at, created_at)
        values (?, ?, ?, ?)
        """,
        tokenHash(token),
        user.id(),
        Timestamp.from(Instant.now().plus(SESSION_TTL)),
        Timestamp.from(Instant.now()));
    return token;
  }

  @Override
  public void revokeSession(String sessionToken) {
    if (sessionToken != null && !sessionToken.isBlank()) {
      jdbcTemplate.update("delete from user_sessions where session_token_hash = ?", tokenHash(sessionToken));
    }
  }

  private AuthRecord findByUsername(String username) {
    var records = jdbcTemplate.query("""
        select id, username, password_hash, display_name, role, enabled
        from users
        where username = ?
        """,
        (rs, rowNum) -> new AuthRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            Role.valueOf(rs.getString("role")),
            rs.getBoolean("enabled")),
        username);
    if (records.isEmpty()) {
      throw new AuthException("invalid username or password");
    }
    return records.getFirst();
  }

  private static String tokenHash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private record AuthRecord(long id, String username, String passwordHash, String displayName, Role role, boolean enabled) {
    AuthUser toUser() {
      return new AuthUser(id, username, displayName, role);
    }
  }
}
