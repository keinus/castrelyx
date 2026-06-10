package org.castrelyx.manager.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertEvaluatorTest {
  private final AlertEvaluator evaluator = new AlertEvaluator();

  @Test
  void emitsThresholdAndStaleHeartbeatSignals() {
    AlertEvaluationInput input = new AlertEvaluationInput(
        "asset-01",
        Instant.parse("2026-06-09T10:20:00Z"),
        Instant.parse("2026-06-09T10:00:00Z"),
        Map.of("cpu.usage", 92.0, "memory.usage", 81.0, "disk.usage", 95.1),
        Map.of("eth0.status", "down", "eth0.errors", "20"),
        List.of("snmp.poll.failure"));

    List<AlertSignal> signals = evaluator.evaluate(input);

    assertThat(signals).extracting(AlertSignal::ruleType)
        .contains("agent_heartbeat_stale", "cpu_threshold", "disk_threshold", "interface_down", "snmp_poll_failure");
    assertThat(signals).anySatisfy(signal -> {
      assertThat(signal.ruleType()).isEqualTo("cpu_threshold");
      assertThat(signal.severity()).isEqualTo(Severity.CRITICAL);
      assertThat(signal.stateKey()).isEqualTo("cpu.usage");
    });
  }
}
