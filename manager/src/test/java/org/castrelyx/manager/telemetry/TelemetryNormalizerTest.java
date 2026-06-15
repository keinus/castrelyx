package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryNormalizerTest {
  private final TelemetryNormalizer normalizer = new TelemetryNormalizer();

  @Test
  void normalizesAgentIdentityMetricStateAndEventItems() {
    List<CanonicalTelemetryRecord> records = normalizer.normalizeRawLogparserEvent("""
        {
          "received_at":"2026-06-09T10:15:30Z",
          "source_id":"agent-01",
          "item_kind":"metric",
          "item_type":"cpu",
          "item_key":"cpu.total",
          "event_json":{
            "asset_uid":"agent-01",
            "metric_name":"cpu.usage",
            "metric_value":87.5,
            "unit":"percent",
            "labels":{"collector":"metric"}
          }
        }
        """);

    assertThat(records).singleElement().satisfies(record -> {
      assertThat(record.kind()).isEqualTo(CanonicalTelemetryRecord.Kind.METRIC);
      assertThat(record.assetUid()).isEqualTo("agent-01");
      assertThat(record.metricName()).isEqualTo("cpu.usage");
      assertThat(record.metricValue()).isEqualTo(87.5);
      assertThat(record.labelsJson()).contains("collector");
    });
  }

  @Test
  void normalizesAgentNetworkMetricPayloadWithInterfaceLabels() {
    List<CanonicalTelemetryRecord> records = normalizer.normalizeRawLogparserEvent("""
        {
          "received_at":"2026-06-11T13:34:00Z",
          "source_id":"nas",
          "item_kind":"metric",
          "item_type":"network",
          "item_key":"host.network.rx_bytes:enp2s0:ingress",
          "event_json":{
            "additionalAttributes":{
              "payload":{
                "metric_name":"host.network.rx_bytes",
                "value":120000,
                "unit":"bytes",
                "interface":"enp2s0",
                "direction":"ingress"
              }
            }
          }
        }
        """);

    assertThat(records).singleElement().satisfies(record -> {
      assertThat(record.kind()).isEqualTo(CanonicalTelemetryRecord.Kind.METRIC);
      assertThat(record.assetUid()).isEqualTo("nas");
      assertThat(record.metricName()).isEqualTo("host.network.rx_bytes");
      assertThat(record.metricValue()).isEqualTo(120000.0);
      assertThat(record.labelsJson()).contains("\"interface\":\"enp2s0\"");
      assertThat(record.labelsJson()).contains("\"direction\":\"ingress\"");
    });
  }

  @Test
  void normalizesSnmpInterfaceCountersAndPollFailure() {
    List<CanonicalTelemetryRecord> records = normalizer.normalizeRawLogparserEvent("""
        {
          "received_at":"2026-06-09T10:15:30Z",
          "source_id":"snmp-edge",
          "item_kind":"snmp",
          "event_json":{
            "target_host":"192.168.10.1",
            "target_name":"edge-router",
            "poll_status":"error",
            "metrics":[
              {"interface":"eth0","name":"ifInOctets","value":120000,"unit":"bytes"},
              {"interface":"eth0","name":"ifOutOctets","value":90000,"unit":"bytes"},
              {"interface":"eth0","name":"ifInErrors","value":2,"unit":"count"}
            ]
          }
        }
        """);

    assertThat(records).extracting(CanonicalTelemetryRecord::kind)
        .contains(CanonicalTelemetryRecord.Kind.METRIC, CanonicalTelemetryRecord.Kind.EVENT);
    assertThat(records).extracting(CanonicalTelemetryRecord::metricName)
        .contains("interface.in.bytes", "interface.out.bytes", "interface.in.errors");
    assertThat(records).anySatisfy(record -> {
      assertThat(record.kind()).isEqualTo(CanonicalTelemetryRecord.Kind.EVENT);
      assertThat(record.eventType()).isEqualTo("snmp.poll.failure");
    });
  }
}
