package org.castrelyx.manager.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.castrelyx.manager.snmp.SnmpTargetRequest;
import org.castrelyx.manager.snmp.SnmpTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@ActiveProfiles("test")
@SpringBootTest
class LogparserSnmpIntegrationTest {
  @Autowired
  JdbcTemplate jdbcTemplate;

  FakeLogparserClient logparserClient;
  SnmpTargetService snmpTargetService;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from snmp_targets");
    jdbcTemplate.update("delete from snmp_credentials");
    logparserClient = new FakeLogparserClient();
    snmpTargetService = new SnmpTargetService(jdbcTemplate, logparserClient);
  }

  @Test
  @SuppressWarnings("unchecked")
  void snmpTargetCreateBuildsLogparserSnmpInputAdapterPayload() {
    snmpTargetService.create(new SnmpTargetRequest(
        "edge-router",
        "192.168.10.1",
        1161,
        null,
        true,
        30000L,
        List.of("1.3.6.1.2.1.2.2.1.10", "1.3.6.1.2.1.2.2.1.16")));

    assertThat(logparserClient.payload).containsEntry("type", "SnmpInputAdapter");
    assertThat(logparserClient.payload).containsEntry("enabled", true);
    Map<String, Object> configParams = (Map<String, Object>) logparserClient.payload.get("configParams");
    assertThat(configParams).containsKeys("targets", "oids", "intervalMs", "timeoutMs", "retries", "queueSize", "workerThreads");
    assertThat(configParams.get("intervalMs")).isEqualTo(30000L);
    assertThat((List<String>) configParams.get("oids")).contains("1.3.6.1.2.1.2.2.1.10");
    List<Map<String, Object>> targets = (List<Map<String, Object>>) configParams.get("targets");
    assertThat(targets).singleElement().satisfies(target -> {
      assertThat(target).containsEntry("host", "192.168.10.1");
      assertThat(target).containsEntry("port", 1161);
      assertThat(target).containsEntry("name", "edge-router");
    });
  }

  private static class FakeLogparserClient extends LogparserClient {
    Map<String, Object> payload;

    FakeLogparserClient() {
      super(RestClient.builder(), null);
    }

    @Override
    public Object upsertSnmpInputAdapter(Map<String, Object> payload) {
      this.payload = payload;
      return Map.of("ok", true);
    }
  }
}
