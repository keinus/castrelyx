package org.castrelyx.manager.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.telemetry.ClickHouseClient.MetricSample;
import org.castrelyx.manager.telemetry.TrafficQueryService.InterfaceTraffic;
import org.springframework.stereotype.Service;

@Service
public class AssetMetricsQueryService {
  private static final Duration STALE_AFTER = Duration.ofMinutes(10);

  private final AssetService assetService;
  private final ClickHouseClient clickHouseClient;

  public AssetMetricsQueryService(AssetService assetService, ClickHouseClient clickHouseClient) {
    this.assetService = assetService;
    this.clickHouseClient = clickHouseClient;
  }

  public Map<String, Object> overview(String range) {
    Map<String, AssetMetricBuilder> builders = buildersForRegisteredAssets();
    for (MetricSample sample : clickHouseClient.queryLatestMetricSamples(range, null)) {
      builder(builders, sample.assetUid()).addSample(sample);
    }
    for (InterfaceTraffic row : clickHouseClient.queryInterfaceTraffic(range, null)) {
      builder(builders, row.assetUid()).interfaces.add(row);
    }
    applyAgentContext(builders, clickHouseClient.queryAgentDashboard(null));
    List<Map<String, Object>> rows = builders.values().stream()
        .map(AssetMetricBuilder::toOverviewRow)
        .sorted(assetMetricComparator())
        .toList();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("range", normalizeRange(range));
    response.put("summary", summary(rows));
    response.put("assets", rows);
    return response;
  }

  public Map<String, Object> detail(String assetUid, String range, String bucket) {
    Map<String, AssetMetricBuilder> builders = buildersForRegisteredAssets();
    AssetMetricBuilder selected = builder(builders, assetUid);
    for (MetricSample sample : clickHouseClient.queryLatestMetricSamples(range, assetUid)) {
      selected.addSample(sample);
    }
    for (InterfaceTraffic row : clickHouseClient.queryInterfaceTraffic(range, assetUid)) {
      selected.interfaces.add(row);
    }
    applyAgentContext(builders, clickHouseClient.queryAgentDashboard(assetUid));

    List<MetricSample> seriesSamples = clickHouseClient.queryMetricSeriesSamples(range, bucket, assetUid);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("range", normalizeRange(range));
    response.put("bucket", bucket == null || bucket.isBlank() ? "auto" : bucket);
    response.put("asset", selected.toOverviewRow());
    response.put("series", series(seriesSamples));
    response.put("disks", selected.diskRows());
    response.put("interfaces", selected.interfaces.stream()
        .sorted(Comparator.comparingDouble((InterfaceTraffic row) -> row.inBps() + row.outBps()).reversed())
        .toList());
    response.put("processes", selected.topProcesses());
    response.put("security", selected.securityRow());
    response.put("collectors", selected.collectors);
    return response;
  }

  private Map<String, AssetMetricBuilder> buildersForRegisteredAssets() {
    Map<String, AssetMetricBuilder> builders = new LinkedHashMap<>();
    for (Asset asset : assetService.listAssets()) {
      builders.put(asset.assetUid(), new AssetMetricBuilder(asset.assetUid(), asset));
    }
    return builders;
  }

  @SuppressWarnings("unchecked")
  private static void applyAgentContext(Map<String, AssetMetricBuilder> builders, Map<String, Object> dashboard) {
    for (Map<String, Object> agent : listOfMaps(dashboard.get("agents"))) {
      String assetUid = firstText(agent.get("assetUid"), agent.get("sourceId"));
      if (assetUid == null) {
        continue;
      }
      AssetMetricBuilder builder = builder(builders, assetUid);
      builder.agentSeen = true;
      builder.markSeen(parseInstant(firstText(agent.get("lastSeenAt"), agent.get("last_seen_at"))));
    }
    Object statesObject = dashboard.get("states");
    Map<String, Object> states = statesObject instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    for (Map<String, Object> socket : listOfMaps(states.get("sockets"))) {
      builder(builders, firstText(socket.get("assetUid"), socket.get("asset_uid"))).sockets.add(socket);
    }
    for (Map<String, Object> service : listOfMaps(states.get("services"))) {
      builder(builders, firstText(service.get("assetUid"), service.get("asset_uid"))).services.add(service);
    }
    for (Map<String, Object> firewall : listOfMaps(states.get("firewalls"))) {
      builder(builders, firstText(firewall.get("assetUid"), firewall.get("asset_uid"))).firewalls.add(firewall);
    }
    for (Map<String, Object> process : listOfMaps(states.get("processes"))) {
      builder(builders, firstText(process.get("assetUid"), process.get("asset_uid"))).processes.add(process);
    }
    for (Map<String, Object> collector : listOfMaps(dashboard.get("collectors"))) {
      String name = firstText(collector.get("name"));
      String lastSeen = firstText(collector.get("lastSeenAt"), collector.get("last_seen_at"));
      for (AssetMetricBuilder builder : builders.values()) {
        if (name != null && lastSeen != null) {
          builder.collectors.add(Map.of("name", name, "lastSeenAt", lastSeen));
        }
      }
    }
    for (Map<String, Object> event : listOfMaps(dashboard.get("events"))) {
      builder(builders, firstText(event.get("assetUid"), event.get("asset_uid"))).events.add(event);
    }
  }

  private static AssetMetricBuilder builder(Map<String, AssetMetricBuilder> builders, String assetUid) {
    String key = assetUid == null || assetUid.isBlank() ? "unknown" : assetUid;
    return builders.computeIfAbsent(key, uid -> new AssetMetricBuilder(uid, null));
  }

  private static Comparator<Map<String, Object>> assetMetricComparator() {
    return Comparator
        .<Map<String, Object>>comparingInt(row -> healthRank(String.valueOf(row.get("health")))).reversed()
        .thenComparing(row -> Optional.ofNullable((String) row.get("lastSeenAt")).orElse(""), Comparator.reverseOrder())
        .thenComparing(row -> String.valueOf(row.get("name")));
  }

  private static Map<String, Object> summary(List<Map<String, Object>> rows) {
    long observed = rows.stream().filter(row -> Boolean.TRUE.equals(((Map<?, ?>) row.get("sources")).get("observed"))).count();
    long stale = rows.stream().filter(row -> "warning".equals(row.get("health")) && Boolean.TRUE.equals(row.get("stale"))).count();
    long critical = rows.stream().filter(row -> "critical".equals(row.get("health"))).count();
    long warning = rows.stream().filter(row -> "warning".equals(row.get("health"))).count();
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      @SuppressWarnings("unchecked")
      Map<String, Object> metric = (Map<String, Object>) row.get("metrics");
      addNumeric(metrics, "cpuTotal", metric.get("cpuUsagePct"));
      addNumeric(metrics, "memoryTotal", metric.get("memoryUsagePct"));
      addNumeric(metrics, "cpuCount", metric.get("cpuUsagePct") == null ? null : 1);
      addNumeric(metrics, "memoryCount", metric.get("memoryUsagePct") == null ? null : 1);
      if (metric.get("diskUsagePct") instanceof Number diskUsage) {
        metrics.put("maxDiskUsagePct", Math.max(number(metrics.get("maxDiskUsagePct")), diskUsage.doubleValue()));
      }
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalAssets", rows.size());
    summary.put("observedAssets", observed);
    summary.put("staleAssets", stale);
    summary.put("criticalAssets", critical);
    summary.put("warningAssets", warning);
    summary.put("avgCpuUsagePct", average(metrics.get("cpuTotal"), metrics.get("cpuCount")));
    summary.put("avgMemoryUsagePct", average(metrics.get("memoryTotal"), metrics.get("memoryCount")));
    summary.put("maxDiskUsagePct", metrics.getOrDefault("maxDiskUsagePct", null));
    return summary;
  }

  private static void addNumeric(Map<String, Object> map, String key, Object value) {
    if (value instanceof Number number) {
      map.put(key, number(map.get(key)) + number.doubleValue());
    }
  }

  private static Double average(Object total, Object count) {
    double countValue = number(count);
    return countValue <= 0 ? null : number(total) / countValue;
  }

  private static Map<String, Object> series(List<MetricSample> samples) {
    Map<String, Object> rows = new LinkedHashMap<>();
    rows.put("cpu", directSeries(samples, List.of("cpu.usage", "host.cpu.usage_percent", "system.cpu.total.norm.pct"), "value"));
    rows.put("memory", memorySeries(samples));
    rows.put("disk", diskSeries(samples));
    rows.put("load", loadSeries(samples));
    rows.put("network", networkSeries(samples));
    return rows;
  }

  private static List<Map<String, Object>> directSeries(List<MetricSample> samples, List<String> names, String field) {
    Map<Instant, Double> values = new LinkedHashMap<>();
    for (MetricSample sample : samples) {
      if (names.contains(sample.metricName())) {
        values.put(sample.observedAt(), normalizePercent(sample.metricName(), sample.value()));
      }
    }
    return values.entrySet().stream().map(entry -> point(entry.getKey(), field, entry.getValue())).toList();
  }

  private static List<Map<String, Object>> memorySeries(List<MetricSample> samples) {
    List<Map<String, Object>> direct = directSeries(samples, List.of("memory.usage", "system.memory.actual.used.pct"), "value");
    if (!direct.isEmpty()) {
      return direct;
    }
    Map<Instant, Double> total = valuesByTime(samples, "host.memory.total_bytes");
    Map<Instant, Double> available = valuesByTime(samples, "host.memory.available_bytes");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<Instant, Double> entry : total.entrySet()) {
      Double availableValue = available.get(entry.getKey());
      if (availableValue != null && entry.getValue() > 0) {
        rows.add(point(entry.getKey(), "value", (entry.getValue() - availableValue) * 100 / entry.getValue()));
      }
    }
    return rows;
  }

  private static List<Map<String, Object>> diskSeries(List<MetricSample> samples) {
    List<Map<String, Object>> direct = directSeries(samples, List.of("disk.usage"), "value");
    if (!direct.isEmpty()) {
      return direct;
    }
    Map<Instant, Double> values = new LinkedHashMap<>();
    for (MetricSample sample : samples) {
      if ("host.disk.used_percent".equals(sample.metricName()) || "system.filesystem.used.pct".equals(sample.metricName())) {
        values.merge(sample.observedAt(), normalizePercent(sample.metricName(), sample.value()), Math::max);
      }
    }
    return values.entrySet().stream().map(entry -> point(entry.getKey(), "value", entry.getValue())).toList();
  }

  private static List<Map<String, Object>> loadSeries(List<MetricSample> samples) {
    List<Map<String, Object>> direct = directSeries(samples, List.of("host.load.normalized_1"), "value");
    if (!direct.isEmpty()) {
      return direct;
    }
    Map<Instant, Double> load = valuesByTime(samples, "host.load.1");
    Map<Instant, Double> cpuCount = valuesByTime(samples, "host.cpu.count");
    if (cpuCount.isEmpty()) {
      cpuCount = valuesByTime(samples, "agent.host.cpu_count");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<Instant, Double> entry : load.entrySet()) {
      Double cores = cpuCount.get(entry.getKey());
      if (cores != null && cores > 0) {
        rows.add(point(entry.getKey(), "value", entry.getValue() / cores * 100));
      }
    }
    return rows;
  }

  private static List<Map<String, Object>> networkSeries(List<MetricSample> samples) {
    Map<String, MetricSample> previousByKey = new HashMap<>();
    Map<Instant, Map<String, Object>> points = new LinkedHashMap<>();
    for (MetricSample sample : samples.stream()
        .filter(AssetMetricsQueryService::isNetworkCounter)
        .sorted(Comparator.comparing(MetricSample::observedAt))
        .toList()) {
      String key = sample.metricName() + "::" + Optional.ofNullable(sample.interfaceName()).orElse("");
      MetricSample previous = previousByKey.put(key, sample);
      if (previous == null) {
        continue;
      }
      long elapsed = Duration.between(previous.observedAt(), sample.observedAt()).toSeconds();
      if (elapsed <= 0 || sample.value() < previous.value()) {
        continue;
      }
      double bps = (sample.value() - previous.value()) * 8 / elapsed;
      Map<String, Object> point = points.computeIfAbsent(sample.observedAt(), at -> {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("timestamp", at.toString());
        created.put("inBps", 0.0);
        created.put("outBps", 0.0);
        return created;
      });
      if (isIngress(sample.metricName())) {
        point.put("inBps", number(point.get("inBps")) + bps);
      } else {
        point.put("outBps", number(point.get("outBps")) + bps);
      }
    }
    return new ArrayList<>(points.values());
  }

  private static boolean isNetworkCounter(MetricSample sample) {
    return List.of("host.network.rx_bytes", "host.network.tx_bytes", "interface.in.bytes", "interface.out.bytes")
        .contains(sample.metricName());
  }

  private static boolean isIngress(String metricName) {
    return "host.network.rx_bytes".equals(metricName) || "interface.in.bytes".equals(metricName);
  }

  private static Map<Instant, Double> valuesByTime(List<MetricSample> samples, String metricName) {
    Map<Instant, Double> values = new LinkedHashMap<>();
    for (MetricSample sample : samples) {
      if (metricName.equals(sample.metricName())) {
        values.put(sample.observedAt(), sample.value());
      }
    }
    return values;
  }

  private static Map<String, Object> point(Instant timestamp, String key, Double value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("timestamp", timestamp.toString());
    row.put(key, value);
    return row;
  }

  private static String normalizeRange(String range) {
    return switch (range == null ? "" : range.trim().toLowerCase()) {
      case "15m", "30m", "6h", "24h" -> range.trim().toLowerCase();
      default -> "1h";
    };
  }

  private static List<Map<String, Object>> listOfMaps(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Map<String, Object> row = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> row.put(String.valueOf(key), mapValue));
        rows.add(row);
      }
    }
    return rows;
  }

  private static String firstText(Object... values) {
    for (Object value : values) {
      if (value != null && !String.valueOf(value).isBlank()) {
        return String.valueOf(value);
      }
    }
    return null;
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static double number(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  private static Double normalizePercent(String metricName, double value) {
    if ((metricName.endsWith(".pct") || metricName.contains("utilization")) && value <= 1.5) {
      return value * 100;
    }
    return value;
  }

  private static int healthRank(String health) {
    return switch (health) {
      case "critical" -> 3;
      case "warning" -> 2;
      case "healthy" -> 1;
      default -> 0;
    };
  }

  private static final class AssetMetricBuilder {
    private final String assetUid;
    private final Asset asset;
    private final List<MetricSample> samples = new ArrayList<>();
    private final List<InterfaceTraffic> interfaces = new ArrayList<>();
    private final List<Map<String, Object>> sockets = new ArrayList<>();
    private final List<Map<String, Object>> services = new ArrayList<>();
    private final List<Map<String, Object>> firewalls = new ArrayList<>();
    private final List<Map<String, Object>> processes = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Map<String, Object>> collectors = new ArrayList<>();
    private boolean agentSeen;
    private Instant lastSeenAt;

    private AssetMetricBuilder(String assetUid, Asset asset) {
      this.assetUid = assetUid;
      this.asset = asset;
      this.lastSeenAt = asset == null || asset.lastSeenAt() == null ? null : asset.lastSeenAt();
    }

    private void addSample(MetricSample sample) {
      samples.add(sample);
      markSeen(sample.observedAt());
    }

    private void markSeen(Instant timestamp) {
      if (timestamp != null && (lastSeenAt == null || timestamp.isAfter(lastSeenAt))) {
        lastSeenAt = timestamp;
      }
    }

    private Map<String, Object> toOverviewRow() {
      Map<String, Object> row = new LinkedHashMap<>();
      Map<String, Object> metrics = metricsRow();
      String health = health(metrics);
      row.put("assetUid", assetUid);
      row.put("name", asset == null ? assetUid : asset.name());
      row.put("assetType", asset == null ? "OBSERVED" : asset.assetType().name());
      row.put("managementIp", asset == null ? null : asset.managementIp());
      row.put("status", asset == null ? "observed" : asset.status());
      row.put("lastSeenAt", lastSeenAt == null ? null : lastSeenAt.toString());
      row.put("stale", isStale());
      row.put("health", health);
      row.put("sources", sourcesRow());
      row.put("metrics", metrics);
      row.put("security", securityRow());
      return row;
    }

    private Map<String, Object> metricsRow() {
      Map<String, Object> metrics = new LinkedHashMap<>();
      Double cpuUsage = latestPercent("cpu.usage", "host.cpu.usage_percent", "system.cpu.total.norm.pct");
      Double memoryUsage = latestPercent("memory.usage", "system.memory.actual.used.pct");
      Double memoryTotal = latest("host.memory.total_bytes");
      Double memoryAvailable = latest("host.memory.available_bytes");
      if (memoryUsage == null && memoryTotal != null && memoryAvailable != null && memoryTotal > 0) {
        memoryUsage = (memoryTotal - memoryAvailable) * 100 / memoryTotal;
      }
      Double diskUsage = latestPercent("disk.usage");
      if (diskUsage == null) {
        diskUsage = samples.stream()
            .filter(sample -> "host.disk.used_percent".equals(sample.metricName()) || "system.filesystem.used.pct".equals(sample.metricName()))
            .map(sample -> normalizePercent(sample.metricName(), sample.value()))
            .max(Double::compareTo)
            .orElse(null);
      }
      Double load1 = latest("host.load.1");
      Double load5 = latest("host.load.5");
      Double load15 = latest("host.load.15");
      Double cpuCount = latest("host.cpu.count");
      if (cpuCount == null) {
        cpuCount = latest("agent.host.cpu_count");
      }
      Double normalizedLoad = latestPercent("host.load.normalized_1");
      if (normalizedLoad == null && load1 != null && cpuCount != null && cpuCount > 0) {
        normalizedLoad = load1 / cpuCount * 100;
      }
      double inBps = interfaces.stream().mapToDouble(InterfaceTraffic::inBps).sum();
      double outBps = interfaces.stream().mapToDouble(InterfaceTraffic::outBps).sum();
      long interfaceErrors = interfaces.stream().mapToLong(row -> row.errors() + row.discards()).sum();

      metrics.put("cpuUsagePct", cpuUsage);
      metrics.put("memoryUsagePct", memoryUsage);
      metrics.put("memoryTotalBytes", memoryTotal);
      metrics.put("memoryAvailableBytes", memoryAvailable);
      metrics.put("diskUsagePct", diskUsage);
      metrics.put("load1", load1);
      metrics.put("load5", load5);
      metrics.put("load15", load15);
      metrics.put("normalizedLoadPct", normalizedLoad);
      metrics.put("cpuCount", cpuCount);
      metrics.put("networkInBps", inBps);
      metrics.put("networkOutBps", outBps);
      metrics.put("interfaceErrorCount", interfaceErrors);
      return metrics;
    }

    private Map<String, Object> sourcesRow() {
      Map<String, Object> sources = new LinkedHashMap<>();
      sources.put("registered", asset != null);
      sources.put("agent", agentSeen || samples.stream().anyMatch(sample -> "AGENT".equalsIgnoreCase(readLabel(sample, "source_type"))));
      sources.put("snmp", interfaces.stream().anyMatch(row -> row.assetUid().equals(assetUid)));
      sources.put("traffic", !interfaces.isEmpty());
      sources.put("security", !sockets.isEmpty() || !services.isEmpty() || !firewalls.isEmpty() || !events.isEmpty());
      sources.put("observed", Boolean.TRUE.equals(sources.get("agent")) || Boolean.TRUE.equals(sources.get("traffic"))
          || Boolean.TRUE.equals(sources.get("security")) || !samples.isEmpty());
      return sources;
    }

    private Map<String, Object> securityRow() {
      Map<String, Object> security = new LinkedHashMap<>();
      security.put("openPorts", sockets.stream().filter(AssetMetricsQueryService::isListeningSocket).count());
      security.put("failedServices", services.stream().filter(AssetMetricsQueryService::isProblemService).count());
      security.put("firewallDisabled", firewalls.stream().filter(AssetMetricsQueryService::isFirewallDisabled).count());
      security.put("securityEvents", events.size());
      security.put("events", events.stream().limit(5).toList());
      return security;
    }

    private List<Map<String, Object>> diskRows() {
      Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
      for (MetricSample sample : samples) {
        if (!sample.metricName().startsWith("host.disk.")) {
          continue;
        }
        String mount = sample.mountPoint() == null || sample.mountPoint().isBlank() ? "unknown" : sample.mountPoint();
        Map<String, Object> row = rows.computeIfAbsent(mount, key -> {
          Map<String, Object> created = new LinkedHashMap<>();
          created.put("mountPoint", key);
          created.put("filesystem", readLabel(sample, "filesystem"));
          return created;
        });
        switch (sample.metricName()) {
          case "host.disk.total_bytes" -> row.put("totalBytes", sample.value());
          case "host.disk.used_bytes" -> row.put("usedBytes", sample.value());
          case "host.disk.available_bytes" -> row.put("availableBytes", sample.value());
          case "host.disk.used_percent" -> row.put("usedPct", sample.value());
          default -> {
          }
        }
      }
      return new ArrayList<>(rows.values());
    }

    private List<Map<String, Object>> topProcesses() {
      return processes.stream()
          .sorted(Comparator.comparingDouble((Map<String, Object> row) -> number(row.get("memoryBytes"))).reversed())
          .limit(8)
          .toList();
    }

    private String health(Map<String, Object> metrics) {
      double cpu = number(metrics.get("cpuUsagePct"));
      double memory = number(metrics.get("memoryUsagePct"));
      double disk = number(metrics.get("diskUsagePct"));
      double normalizedLoad = number(metrics.get("normalizedLoadPct"));
      double interfaceErrors = number(metrics.get("interfaceErrorCount"));
      if (cpu >= 90 || memory >= 90 || disk >= 90 || normalizedLoad >= 150 || hasCriticalEvent()) {
        return "critical";
      }
      if (cpu >= 80 || memory >= 80 || disk >= 80 || normalizedLoad >= 100 || interfaceErrors > 0 || isStale()) {
        return "warning";
      }
      if (!samples.isEmpty() || agentSeen || !interfaces.isEmpty()) {
        return "healthy";
      }
      return "unknown";
    }

    private boolean hasCriticalEvent() {
      return events.stream().anyMatch(event -> "CRITICAL".equalsIgnoreCase(firstText(event.get("severity"))));
    }

    private boolean isStale() {
      return lastSeenAt != null && lastSeenAt.isBefore(Instant.now().minus(STALE_AFTER));
    }

    private Double latest(String... names) {
      for (String name : names) {
        Optional<MetricSample> sample = samples.stream()
            .filter(row -> name.equals(row.metricName()))
            .max(Comparator.comparing(MetricSample::observedAt));
        if (sample.isPresent()) {
          return sample.get().value();
        }
      }
      return null;
    }

    private Double latestPercent(String... names) {
      for (String name : names) {
        Optional<MetricSample> sample = samples.stream()
            .filter(row -> name.equals(row.metricName()))
            .max(Comparator.comparing(MetricSample::observedAt));
        if (sample.isPresent()) {
          return normalizePercent(sample.get().metricName(), sample.get().value());
        }
      }
      return null;
    }
  }

  private static boolean isListeningSocket(Map<String, Object> row) {
    String direction = firstText(row.get("direction"));
    String state = firstText(row.get("state"));
    return "listening".equalsIgnoreCase(direction) || "listen".equalsIgnoreCase(state);
  }

  private static boolean isProblemService(Map<String, Object> row) {
    String status = firstText(row.get("status"));
    return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
  }

  private static boolean isFirewallDisabled(Map<String, Object> row) {
    Object enabled = row.get("enabled");
    if (enabled instanceof Boolean bool) {
      return !bool;
    }
    return "false".equalsIgnoreCase(firstText(enabled));
  }

  private static String readLabel(MetricSample sample, String key) {
    if ("interface".equals(key)) {
      return sample.interfaceName();
    }
    if ("mount_point".equals(key)) {
      return sample.mountPoint();
    }
    if (sample.labelsJson() == null) {
      return null;
    }
    String needle = "\"" + key + "\":";
    int index = sample.labelsJson().indexOf(needle);
    if (index < 0) {
      return null;
    }
    int start = sample.labelsJson().indexOf('"', index + needle.length());
    int end = start < 0 ? -1 : sample.labelsJson().indexOf('"', start + 1);
    return start >= 0 && end > start ? sample.labelsJson().substring(start + 1, end) : null;
  }
}
