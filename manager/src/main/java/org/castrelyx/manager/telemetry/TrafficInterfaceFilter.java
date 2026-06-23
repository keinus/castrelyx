package org.castrelyx.manager.telemetry;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.castrelyx.manager.telemetry.ClickHouseClient.MetricSample;
import org.castrelyx.manager.telemetry.TrafficQueryService.InterfaceTraffic;

final class TrafficInterfaceFilter {
  private TrafficInterfaceFilter() {
  }

  static List<InterfaceTraffic> visibleTrafficRows(Collection<InterfaceTraffic> rows) {
    return rows.stream()
        .filter(row -> row != null && isVisibleTrafficInterface(row.interfaceName()))
        .toList();
  }

  static boolean isVisibleTrafficInterface(String interfaceName) {
    if (interfaceName == null || interfaceName.isBlank()) {
      return false;
    }
    return !isInternalTrafficInterface(interfaceName);
  }

  static boolean isVisibleNetworkSample(MetricSample sample) {
    if (sample == null) {
      return false;
    }
    String interfaceName = sample.interfaceName();
    return interfaceName == null || interfaceName.isBlank() || !isInternalTrafficInterface(interfaceName);
  }

  static String sqlVisibleInterfacePredicate(String expression) {
    return "AND lower(" + expression + ") != 'lo'\n"
        + "            AND NOT startsWith(lower(" + expression + "), 'veth')\n"
        + "            AND NOT startsWith(lower(" + expression + "), 'docker')\n"
        + "            AND NOT match(lower(" + expression + "), '^br-[0-9a-f]{12,}$')";
  }

  private static boolean isInternalTrafficInterface(String interfaceName) {
    String normalized = interfaceName.trim().toLowerCase(Locale.ROOT);
    return "lo".equals(normalized)
        || normalized.startsWith("veth")
        || normalized.startsWith("docker")
        || isDockerBridge(normalized);
  }

  private static boolean isDockerBridge(String normalized) {
    if (!normalized.startsWith("br-") || normalized.length() < 15) {
      return false;
    }
    for (int index = 3; index < normalized.length(); index++) {
      char current = normalized.charAt(index);
      boolean hex = (current >= '0' && current <= '9') || (current >= 'a' && current <= 'f');
      if (!hex) {
        return false;
      }
    }
    return true;
  }
}
