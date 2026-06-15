package org.castrelyx.manager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class LogparserClientTest {
  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  LogparserClient client;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from integration_configs");
  }

  @Test
  void doesNotGenerateRelativeDeepLinksWhenBaseUrlIsMissing() {
    assertThat(client.deepLinks()).isEmpty();
  }

  @Test
  void deepLinksUseConfiguredAbsoluteBaseUrl() {
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values (?, ?, null, true, ?, ?)
        """, "logparser", "http://192.168.50.21:8765", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

    assertThat(client.deepLinks())
        .extracting((link) -> link.get("url"))
        .containsExactly(
            "http://192.168.50.21:8765/",
            "http://192.168.50.21:8765/#input-adapters",
            "http://192.168.50.21:8765/#live-tail");
  }
}
