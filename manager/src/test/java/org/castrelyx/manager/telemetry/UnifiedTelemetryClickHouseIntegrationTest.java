package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.manager.asset.*;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TELEMETRY_TEST_CLICKHOUSE_URL", matches=".+")
class UnifiedTelemetryClickHouseIntegrationTest {
  private final String endpoint = System.getenv("TELEMETRY_TEST_CLICKHOUSE_URL");
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void unifiesHistoricalSnmpAddressWithNasWithoutCountingTrafficTwice() throws Exception {
    String database = "telemetry_qa_" + UUID.randomUUID().toString().replace("-", "");
    query("CREATE DATABASE " + database);
    try {
      query(CanonicalTelemetrySchema.createMetricSamples(database));
      query(CanonicalTelemetrySchema.createStateSnapshots(database));
      query(CanonicalTelemetrySchema.createEvents(database));
      Instant now = Instant.now();
      var assets = mock(AssetService.class);
      when(assets.listAssets()).thenReturn(List.of(new Asset(1, "nas", "nas", AssetType.LINUX_SERVER,
          "192.168.50.22", null, null, "active", now, now)));
      when(assets.telemetryAliases()).thenReturn(Map.of("192.168.50.22", "nas"));
      var client = new ClickHouseClient(new ManagerProperties("test", "http://localhost",
          new ManagerProperties.ClickHouse(endpoint, database, "default", "", "unused")), RestClient.builder(), assets);
      // Deliberately different counter baselines expose accidental cross-provider subtraction.
      counters(database, "nas", "AGENT", "agent-nas", now, 0, 6000);
      counters(database, "192.168.50.22", "SNMP", "snmp-nas", now, 1000000, 12000);
      var traffic = client.queryInterfaceTraffic("1h", "nas");
      assertThat(traffic).singleElement().satisfies(row -> assertThat(row.inBps()).isCloseTo(800.0, within(1.0)));
      var overview = new AssetMetricsQueryService(assets, client).overview("1h");
      assertThat((List<?>) overview.get("assets")).hasSize(1);
      assertThat(((Map<?, ?>) overview.get("summary")).get("totalAssets")).isEqualTo(1);
      assertThat(((Number)((Map<?, ?>)overview.get("summary")).get("totalNetworkInBps")).doubleValue()).isCloseTo(800.0, within(1.0));
      assertThat(((List<?>)client.queryAgentDashboard(null).get("agents"))).hasSize(1);
      assertThat(client.queryMetricSeriesSamples("1h", "1m", "nas")).allMatch(sample -> sample.sourceType().equals("AGENT"));

      query("TRUNCATE TABLE " + database + ".manager_metric_samples");
      counters(database, "nas", "AGENT", "agent-nas", now.minusSeconds(1200), 0, 6000);
      counters(database, "192.168.50.22", "SNMP", "snmp-nas", now, 1000000, 12000);
      assertThat(client.queryInterfaceTraffic("1h", "nas")).singleElement()
          .satisfies(row -> assertThat(row.inBps()).isCloseTo(1600.0, within(1.0)));

      assertThat(client.queryMetricSeriesSamples("1h", "1h", "nas")).allMatch(sample -> sample.sourceType().equals("SNMP"));

      query("TRUNCATE TABLE " + database + ".manager_metric_samples");
      counters(database, "nas", "AGENT", "agent-nas", now, 6000, 0);
      counters(database, "192.168.50.22", "SNMP", "snmp-nas", now, 1000000, 12000);
      assertThat(client.queryInterfaceTraffic("1h", "nas")).isEmpty();

      query("TRUNCATE TABLE " + database + ".manager_metric_samples");
      counters(database, "nas", "AGENT", "agent-nas", now, 6000, -6000);
      counters(database, "192.168.50.22", "SNMP", "snmp-nas", now, 1000000, 12000);
      query("INSERT INTO " + database + ".manager_metric_samples FORMAT JSONEachRow\n" + json.writeValueAsString(Map.of(
          "observed_at", ClickHouseClient.formatDateTime64(now), "asset_uid", "nas", "source_type", "AGENT",
          "source_id", "agent-nas", "metric_name", "interface.in.errors", "metric_value", 1,
          "unit", "count", "labels_json", "{\"interface\":\"eth0\"}")));
      assertThat(client.queryInterfaceTraffic("1h", "nas")).singleElement()
          .satisfies(row -> assertThat(row.inBps()).isCloseTo(1600.0, within(1.0)));
    } finally { query("DROP DATABASE " + database); }
  }

  private void counters(String db, String uid, String type, String source, Instant now, long base, long delta) throws Exception {
    for (String name : List.of("host.network.rx_bytes", "host.network.tx_bytes")) {
      for (int i=0; i<2; i++) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("observed_at", ClickHouseClient.formatDateTime64(now.minusSeconds(i==0?60:0)));
        row.put("asset_uid", uid); row.put("source_type", type); row.put("source_id", source);
        row.put("metric_name", name); row.put("metric_value", base+(i==0?0:delta)); row.put("unit", "bytes");
        row.put("labels_json", "{\"interface\":\"eth0\"}");
        query("INSERT INTO " + db + ".manager_metric_samples FORMAT JSONEachRow\n" + json.writeValueAsString(row));
      }
    }
  }
  private static org.assertj.core.data.Offset<Double> within(double value) { return org.assertj.core.data.Offset.offset(value); }
  private String query(String sql) throws Exception {
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint))
        .POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
    return response.body();
  }
}
