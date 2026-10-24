package org.castrelyx.manager.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

  @ParameterizedTest
  @EnumSource(Role.class)
  void existingSessionsAndPasswordAuthenticationAreRejectedWhileAccountIsDisabled(Role role) {
    AuthUser user = authProvider.createLocalUser("disabled-account", "test-password", "QA disabled", role);
    String first = authProvider.createSession(user), second = authProvider.createSession(user);
    assertThat(authProvider.currentUser(first).role()).isEqualTo(role);
    assertThat(authProvider.currentUser(second).role()).isEqualTo(role);
    jdbcTemplate.update("update users set enabled=false where id=?", user.id());
    assertThatThrownBy(() -> authProvider.currentUser(first)).isInstanceOf(AuthException.class);
    assertThatThrownBy(() -> authProvider.currentUser(second)).isInstanceOf(AuthException.class);
    assertThatThrownBy(() -> authProvider.authenticate("disabled-account", "test-password"))
        .isInstanceOf(AuthException.class);
  }
}
