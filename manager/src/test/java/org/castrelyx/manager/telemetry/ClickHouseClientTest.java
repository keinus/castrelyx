package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClickHouseClientTest {
  @Test
  void formatsDateTime64ValuesWithoutIsoZuluSuffixForJsonEachRow() {
    assertThat(ClickHouseClient.formatDateTime64(Instant.parse("2026-06-09T10:01:02.345Z")))
        .isEqualTo("2026-06-09 10:01:02.345");
  }

  @Test
  void limitsTrafficRangeToKnownClickHouseIntervals() {
    assertThat(ClickHouseClient.rangeInterval("15m")).isEqualTo("INTERVAL 15 MINUTE");
    assertThat(ClickHouseClient.rangeInterval("6h")).isEqualTo("INTERVAL 6 HOUR");
    assertThat(ClickHouseClient.rangeInterval("1h; DROP TABLE metrics")).isEqualTo("INTERVAL 1 HOUR");
  }

  @Test
  void limitsMetricBucketsToKnownClickHouseIntervals() {
    assertThat(ClickHouseClient.bucketInterval("15m", "auto")).isEqualTo("INTERVAL 1 MINUTE");
    assertThat(ClickHouseClient.bucketInterval("6h", "auto")).isEqualTo("INTERVAL 15 MINUTE");
    assertThat(ClickHouseClient.bucketInterval("24h", "auto")).isEqualTo("INTERVAL 1 HOUR");
    assertThat(ClickHouseClient.bucketInterval("1h", "1h; DROP TABLE metrics")).isEqualTo("INTERVAL 5 MINUTE");
  }

  @Test
  void limitsAgentLogQueryParametersToKnownValues() {
    assertThat(ClickHouseClient.agentLogLimit(-1)).isEqualTo(200);
    assertThat(ClickHouseClient.agentLogLimit(1200)).isEqualTo(1000);
    assertThat(ClickHouseClient.agentLogSeverityFilter("warning")).contains("WARNING");
    assertThat(ClickHouseClient.agentLogSeverityFilter("WARNING'; DROP TABLE manager_events; --")).isEmpty();
  }

  @Test
  void assetMetricFiltersIncludeTemperatureTelemetry() {
    assertThat(ClickHouseClient.assetMetricCanonicalFilter())
        .contains("metric_name IN")
        .contains("'host.temperature.celsius'");
    assertThat(ClickHouseClient.assetMetricRawFilter())
        .contains("item_key IN")
        .contains("'host.temperature.celsius'");
  }

  @Test
  void hidesInternalTrafficInterfaces() {
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("eth0")).isTrue();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("wan0")).isTrue();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("lo")).isFalse();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("veth8f6d")).isFalse();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("docker0")).isFalse();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("DockerBridge")).isFalse();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("br-2fde3c3c1f2d")).isFalse();
    assertThat(TrafficInterfaceFilter.isVisibleTrafficInterface("br-uplink")).isTrue();
  }

  @Test
  void transformedTelemetrySchemaUsesDailyPartitionsThirtyDayTtlAndIndexes() {
    assertThat(CanonicalTelemetrySchema.createMetricSamples("castrelyx"))
        .contains("PARTITION BY toDate(observed_at)")
        .contains("TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE")
        .contains("INDEX idx_metric_observed_at");
    assertThat(CanonicalTelemetrySchema.createStateSnapshots("castrelyx"))
        .contains("PARTITION BY toDate(observed_at)")
        .contains("TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE")
        .contains("INDEX idx_state_type");
    assertThat(CanonicalTelemetrySchema.createEvents("castrelyx"))
        .contains("PARTITION BY toDate(observed_at)")
        .contains("TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE")
        .contains("INDEX idx_event_type");
  }

  @Test
  void queriesAllDashboardStateTypesWithOneSharedThirtyDayScan() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    List<String> queries = new ArrayList<>();
    String stateRows = """
        {"asset_uid":"asset-1","source_id":"agent-1","state_type":"socket","state_key":"tcp:22","state_json":"{\\"protocol\\":\\"tcp\\"}","observed_at":"2026-07-10 01:00:00"}
        {"asset_uid":"asset-1","source_id":"agent-1","state_type":"package","state_key":"curl","state_json":"{\\"name\\":\\"curl\\"}","observed_at":"2026-07-10 00:59:00"}
        """;
    server.expect(ExpectedCount.times(5), request -> queries.add(clickHouseQuery(request.getURI().getRawQuery())))
        .andRespond(request -> {
          String query = clickHouseQuery(request.getURI().getRawQuery());
          String body = query.contains("complete_full_snapshots") ? stateRows : "";
          return withSuccess(body, MediaType.TEXT_PLAIN).createResponse(request);
        });
    ClickHouseClient client = new ClickHouseClient(
        new ManagerProperties(
            "test-key",
            "https://manager.test",
            new ManagerProperties.ClickHouse(
                "http://localhost:18123", "castrelyx", "default", "", "castrelyx_agent_events")),
        builder);

    Map<String, Object> dashboard = client.queryAgentDashboard("asset-1");

    server.verify();
    List<String> stateQueries = queries.stream()
        .filter(query -> query.contains("complete_full_snapshots"))
        .toList();
    assertThat(stateQueries).hasSize(1);
    assertThat(stateQueries.getFirst())
        .contains("state_type IN ('socket', 'service', 'firewall', 'interface', 'process', 'package')")
        .contains("observed_at >= now() - INTERVAL 30 DAY")
        .contains("HAVING uniqExact(state_key) >= max(JSONExtractUInt(state_json, 'snapshot_item_count'))")
        .contains("WHERE NOT JSONExtractBool(state_json, 'deleted')")
        .contains("PARTITION BY state_type")
        .contains("state_rank <= if(state_type = 'package', 200, 500)");
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> states =
        (Map<String, List<Map<String, Object>>>) dashboard.get("states");
    assertThat(states.get("sockets"))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("stateType", "socket")
            .containsEntry("protocol", "tcp"));
    assertThat(states.get("packages"))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("stateType", "package")
            .containsEntry("name", "curl"));
    assertThat(states.get("services")).isEmpty();
  }

  @Test
  void extractsTopLevelAgentPayloadForDashboardStateRows() {
    Map<String, Object> row = ClickHouseClient.rawStateRow(Map.of(
        "source_id", "nas",
        "asset_uid", "nas",
        "state_type", "socket",
        "state_key", "tcp:0.0.0.0:22",
        "observed_at", "2026-06-11 13:34:00",
        "event_json", """
            {
              "payload": {
                "protocol": "tcp",
                "local_address": "0.0.0.0",
                "local_port": 22,
                "direction": "listening",
                "process_name": "sshd"
              }
            }
            """));

    assertThat(row)
        .containsEntry("assetUid", "nas")
        .containsEntry("stateType", "socket")
        .containsEntry("protocol", "tcp")
        .containsEntry("localAddress", "0.0.0.0")
        .containsEntry("localPort", 22)
        .containsEntry("direction", "listening")
        .containsEntry("processName", "sshd");
    assertThat(row.get("observedAt")).isEqualTo("2026-06-11T13:34:00Z");
  }

  @Test
  void extractsInterfaceStatePayloadForDashboardStateRows() {
    Map<String, Object> row = ClickHouseClient.rawStateRow(Map.of(
        "source_id", "security",
        "asset_uid", "security",
        "state_type", "interface",
        "state_key", "eth1",
        "observed_at", "2026-07-06 12:00:00",
        "event_json", """
            {
              "payload": {
                "name": "eth1",
                "oper_status": "down",
                "mac_address": "02:00:00:00:00:01"
              }
            }
            """));

    assertThat(row)
        .containsEntry("assetUid", "security")
        .containsEntry("stateType", "interface")
        .containsEntry("name", "eth1")
        .containsEntry("operStatus", "down")
        .containsEntry("macAddress", "02:00:00:00:00:01");
    assertThat(row.get("observedAt")).isEqualTo("2026-07-06T12:00:00Z");
  }

  private static String clickHouseQuery(String rawQuery) {
    for (String parameter : rawQuery.split("&")) {
      if (parameter.startsWith("query=")) {
        return URLDecoder.decode(parameter.substring("query=".length()), StandardCharsets.UTF_8);
      }
    }
    return "";
  }
}
