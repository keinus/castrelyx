package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
