package org.castrelyx.manager.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@ActiveProfiles("test")
@SpringBootTest
class LogparserClientTest {
  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  LogparserClient client;

  @Autowired
  IntegrationService integrations;

  @Test
  void statusUsesTheLogparserPipelineStatusEndpoint() {
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values (?, ?, null, true, ?, ?)
        """, "logparser", "http://logparser:8765", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    LogparserClient localClient = new LogparserClient(builder, integrations);
    server.expect(requestTo("http://logparser:8765/api/v1/pipeline/status"))
        .andRespond(withSuccess("{\"status\":\"RUNNING\",\"inputAdapterCount\":2}", MediaType.APPLICATION_JSON));
    assertThat(localClient.status()).isEqualTo(java.util.Map.of("status", "RUNNING", "inputAdapterCount", 2));
    server.verify();
  }

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
