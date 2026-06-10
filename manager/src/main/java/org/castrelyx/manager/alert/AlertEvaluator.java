package org.castrelyx.manager.alert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AlertEvaluator {
  private static final Duration HEARTBEAT_STALE_AFTER = Duration.ofMinutes(10);

  public List<AlertSignal> evaluate(AlertEvaluationInput input) {
    List<AlertSignal> signals = new ArrayList<>();
    if (input.lastHeartbeatAt() != null
        && Duration.between(input.lastHeartbeatAt(), input.observedAt()).compareTo(HEARTBEAT_STALE_AFTER) > 0) {
      signals.add(new AlertSignal(
          "agent_heartbeat_stale",
          Severity.CRITICAL,
          "heartbeat",
          "Agent heartbeat stale",
          "No heartbeat received for " + input.assetUid()));
    }
    threshold(input, signals, "cpu.usage", 90.0, "cpu_threshold", "CPU threshold exceeded");
    threshold(input, signals, "memory.usage", 90.0, "memory_threshold", "Memory threshold exceeded");
    threshold(input, signals, "disk.usage", 90.0, "disk_threshold", "Disk threshold exceeded");
    input.states().forEach((key, value) -> {
      if (key.endsWith(".status") && "down".equalsIgnoreCase(value)) {
        signals.add(new AlertSignal(
            "interface_down",
            Severity.CRITICAL,
            key,
            "Interface down",
            key + " is down"));
      }
      if (key.endsWith(".errors") || key.endsWith(".discards")) {
        try {
          double count = Double.parseDouble(value);
          if (count > 10) {
            signals.add(new AlertSignal(
                "interface_error_spike",
                Severity.WARNING,
                key,
                "Interface error or discard spike",
                key + " count is " + value));
          }
        } catch (NumberFormatException ignored) {
          // Ignore non-numeric states from collectors.
        }
      }
    });
    if (input.events().contains("snmp.poll.failure")) {
      signals.add(new AlertSignal(
          "snmp_poll_failure",
          Severity.WARNING,
          "snmp.poll.failure",
          "SNMP poll failure",
          "SNMP polling failed for " + input.assetUid()));
    }
    if (input.events().contains("logparser.output.failure")) {
      signals.add(new AlertSignal(
          "logparser_output_failure",
          Severity.CRITICAL,
          "logparser.output.failure",
          "Logparser output failure",
          "Logparser failed to write telemetry"));
    }
    return signals;
  }

  private static void threshold(AlertEvaluationInput input, List<AlertSignal> signals, String metricName,
      double threshold, String ruleType, String title) {
    Double value = input.metrics().get(metricName);
    if (value != null && value >= threshold) {
      signals.add(new AlertSignal(
          ruleType,
          Severity.CRITICAL,
          metricName,
          title,
          metricName + " is " + value));
    }
  }
}
