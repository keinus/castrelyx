package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetType;
import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.telemetry.ClickHouseClient.DiskIo;
import org.castrelyx.manager.telemetry.ClickHouseClient.MetricSample;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AssetMetricsQueryServiceTest {
  private static final Instant OBSERVED_AT = Instant.now().minus(Duration.ofMinutes(20));

  @Test
  void overviewAndDetailIncludeDiskIoMetricsAndDeviceSignals() {
    AssetService assetService = mock(AssetService.class);
    when(assetService.listAssets()).thenReturn(List.of(new Asset(
        1,
        "nas",
        "nas",
        AssetType.LINUX_SERVER,
        "192.168.50.21",
        "Seoul HQ",
        null,
        "active",
        OBSERVED_AT,
        OBSERVED_AT)));
    AssetMetricsQueryService service = new AssetMetricsQueryService(assetService, new FakeClickHouseClient());

    Map<String, Object> overview = service.overview("1h");

    Map<String, Object> summary = map(overview.get("summary"));
    assertThat(summary)
        .containsEntry("maxDiskIoUtilizationPct", 87.5)
        .containsEntry("totalNetworkInBps", 3000.0)
        .containsEntry("totalNetworkOutBps", 4000.0);
    Map<String, Object> row = map(list(overview.get("assets")).get(0));
    assertThat(row).containsEntry("id", 1L).containsEntry("location", "Seoul HQ");
    assertThat(map(row.get("sources")))
        .containsEntry("diskIo", true)
        .containsEntry("traffic", true)
        .containsEntry("snmp", false);
    assertThat(map(row.get("metrics")))
        .containsEntry("cpuUsagePct", 91.0)
        .containsEntry("diskReadBps", 1024.0)
        .containsEntry("diskWriteBps", 2048.0)
        .containsEntry("diskReadIops", 10.0)
        .containsEntry("diskWriteIops", 20.0)
        .containsEntry("diskIoUtilizationPct", 87.5)
        .containsEntry("temperatureCelsius", 82.5);
    assertThat(map(row.get("security")))
        .containsEntry("openPorts", 1L)
        .containsEntry("failedServices", 1L)
        .containsEntry("firewallDisabled", 1L)
        .containsEntry("interfacesDown", 1L)
        .containsEntry("securityEvents", 2);
    Map<String, Object> signals = map(row.get("signals"));
    assertThat(signals)
        .containsEntry("interfacesDown", 1L)
        .containsEntry("lastEventAt", OBSERVED_AT.minusSeconds(30).toString());
    assertThat(map(signals.get("eventCounts")))
        .containsEntry("ERROR", 1L)
        .containsEntry("WARNING", 1L);
    assertThat(map(signals.get("collectorFreshness"))).containsEntry("stale", true);
    assertThat(list(signals.get("reasons")))
        .extracting(reason -> map(reason).get("code"))
        .contains(
            "cpu",
            "disk_io",
            "temperature",
            "stale",
            "failed_service",
            "firewall_disabled",
            "interface_down",
            "event_critical");
    assertThat(row).containsEntry("health", "critical");

    Map<String, Object> detail = service.detail("nas", "1h", "auto");

    assertThat(list(detail.get("interfaces"))).singleElement()
        .satisfies(interfaceRow -> assertThat(interfaceTraffic(interfaceRow).interfaceName()).isEqualTo("enp2s0"));

    Map<String, Object> series = map(detail.get("series"));
    Map<String, Object> diskIoPoint = map(list(series.get("diskIo")).get(0));
    assertThat(diskIoPoint)
        .containsEntry("readBps", 1024.0)
        .containsEntry("writeBps", 2048.0)
        .containsEntry("readIops", 10.0)
        .containsEntry("writeIops", 20.0)
        .containsEntry("utilizationPct", 87.5);

    Map<String, Object> diskRow = map(list(detail.get("disks")).get(0));
    assertThat(diskRow)
        .containsEntry("mountPoint", "/data")
        .containsEntry("device", "sdb")
        .containsEntry("usedPct", 72.4)
        .containsEntry("readBps", 1024.0)
        .containsEntry("writeBps", 2048.0)
        .containsEntry("ioUtilizationPct", 87.5);
    assertThat(map(list(detail.get("diskIo")).get(0))).containsEntry("device", "sdb");
    assertThat(list(detail.get("services"))).singleElement()
        .satisfies(service -> assertThat(map(service)).containsEntry("name", "nginx.service"));
    assertThat(list(detail.get("firewalls"))).singleElement()
        .satisfies(firewall -> assertThat(map(firewall)).containsEntry("enabled", false));
    assertThat(list(detail.get("interfaceStates"))).singleElement()
        .satisfies(interfaceState -> assertThat(map(interfaceState)).containsEntry("operStatus", "down"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private static TrafficQueryService.InterfaceTraffic interfaceTraffic(Object value) {
    return (TrafficQueryService.InterfaceTraffic) value;
  }

  private static class FakeClickHouseClient extends ClickHouseClient {
    FakeClickHouseClient() {
      super(new ManagerProperties(
          "12345678901234567890123456789012",
          "http://localhost:8780",
          new ManagerProperties.ClickHouse("http://localhost:18123", "castrelyx", "default", "", "castrelyx_agent_events")),
          RestClient.builder());
    }

    @Override
    public List<MetricSample> queryLatestMetricSamples(String range, String assetUid) {
      return latestSamples();
    }

    @Override
    public List<TrafficQueryService.InterfaceTraffic> queryInterfaceTraffic(String range, String assetUid) {
      return List.of(
          new TrafficQueryService.InterfaceTraffic(
              "nas",
              "enp2s0",
              3000.0,
              4000.0,
              0.0,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "nas",
              "lo",
              50_000.0,
              60_000.0,
              0.0,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "nas",
              "docker0",
              50_000.0,
              60_000.0,
              0.0,
              0,
              0,
              "up"));
    }

    @Override
    public List<DiskIo> queryDiskIo(String range, String assetUid) {
      return List.of(new DiskIo("nas", "sdb", 1024.0, 2048.0, 10.0, 20.0, 87.5));
    }

    @Override
    public List<MetricSample> queryMetricSeriesSamples(String range, String bucket, String assetUid) {
      return List.of(
          sample("host.disk.read_bps", 1024.0, "Bps", null),
          sample("host.disk.write_bps", 2048.0, "Bps", null),
          sample("host.disk.read_iops", 10.0, "ops/s", null),
          sample("host.disk.write_iops", 20.0, "ops/s", null),
          sample("host.disk.io_utilization_pct", 87.5, "percent", null));
    }

    @Override
    public Map<String, Object> queryAgentDashboard(String assetUid) {
      return Map.of(
          "agents", List.of(Map.of("assetUid", "nas", "lastSeenAt", OBSERVED_AT.toString())),
          "states", Map.of(
              "processes", List.of(),
              "sockets", List.of(Map.of(
                  "assetUid", "nas",
                  "protocol", "tcp",
                  "localAddress", "0.0.0.0",
                  "localPort", 443,
                  "direction", "listening",
                  "processName", "nginx")),
              "services", List.of(Map.of(
                  "assetUid", "nas",
                  "name", "nginx.service",
                  "status", "failed")),
              "firewalls", List.of(Map.of(
                  "assetUid", "nas",
                  "backend", "ufw",
                  "enabled", false,
                  "ruleCount", 0)),
              "interfaces", List.of(Map.of(
                  "assetUid", "nas",
                  "name", "eth1",
                  "operStatus", "down"))),
          "collectors", List.of(),
          "events", List.of(
              Map.of(
                  "assetUid", "nas",
                  "eventType", "filesystem",
                  "severity", "ERROR",
                  "message", "Root filesystem full",
                  "observedAt", OBSERVED_AT.minusSeconds(30).toString()),
              Map.of(
                  "assetUid", "nas",
                  "eventType", "kernel",
                  "severity", "WARNING",
                  "message", "Thermal zone warning",
                  "observedAt", OBSERVED_AT.minusSeconds(60).toString())));
    }

    private static List<MetricSample> latestSamples() {
      return List.of(
          sample("cpu.usage", 91.0, "percent", null),
          sample("memory.usage", 62.0, "percent", null),
          sample("host.disk.used_percent", 72.4, "percent", "/data"),
          sample("host.temperature.celsius", 82.5, "celsius", null));
    }

    private static MetricSample sample(String name, double value, String unit, String mountPoint) {
      return new MetricSample(
          "nas",
          "AGENT",
          name,
          value,
          unit,
          OBSERVED_AT,
          null,
          mountPoint,
          "sdb",
          "{\"filesystem\":\"/dev/sdb1\"}");
    }
  }
}
