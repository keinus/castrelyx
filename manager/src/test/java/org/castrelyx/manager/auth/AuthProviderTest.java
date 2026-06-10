package org.castrelyx.manager.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AuthProviderTest {
  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from users");
  }

  @Test
  void authenticatesLocalUserAndStoresOnlySessionTokenHash() {
    AuthUser admin = authProvider.createLocalUser("admin", "correct-password", "Administrator", Role.ADMIN);

    AuthUser authenticated = authProvider.authenticate("admin", "correct-password");
    String sessionToken = authProvider.createSession(admin);
    String storedHash = jdbcTemplate.queryForObject("select session_token_hash from user_sessions", String.class);

    assertThat(authenticated.role()).isEqualTo(Role.ADMIN);
    assertThat(authProvider.currentUser(sessionToken).username()).isEqualTo("admin");
    assertThat(storedHash).isNotBlank();
    assertThat(storedHash).doesNotContain(sessionToken);
    assertThatThrownBy(() -> authProvider.authenticate("admin", "wrong-password"))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void revokesSessions() {
    AuthUser user = authProvider.createLocalUser("operator", "password", "Operator", Role.OPERATOR);
    String token = authProvider.createSession(user);

    authProvider.revokeSession(token);

    assertThatThrownBy(() -> authProvider.currentUser(token))
        .isInstanceOf(AuthException.class);
  }
}
