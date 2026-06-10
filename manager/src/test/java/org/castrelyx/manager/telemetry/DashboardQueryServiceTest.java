package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetType;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DashboardQueryServiceTest {
  @SuppressWarnings("unchecked")
  @Test
  void overviewCombinesAssetsTrafficAgentAndSnmpHealth() {
    AssetService assetService = mock(AssetService.class);
    when(assetService.listAssets()).thenReturn(List.of(
        new Asset(1, "agent-01", "app-01", AssetType.LINUX_SERVER, "10.1.0.10", null, "active", Instant.now(), Instant.now()),
        new Asset(2, "edge-router", "edge-router", AssetType.ROUTER, "192.168.10.1", null, "active", Instant.now(), Instant.now())));
    DashboardQueryService service = new DashboardQueryService(assetService, new FakeClickHouseClient());

    Map<String, Object> overview = service.overview();

    assertThat(overview).containsEntry("activeAssets", 2);
    assertThat((List<?>) overview.get("trafficTopInterfaces")).hasSize(1);
    assertThat((Map<String, Object>) overview.get("agentHealth")).containsEntry("healthy", 1).containsEntry("stale", 1);
    assertThat((Map<String, Object>) overview.get("snmpPollHealth")).containsEntry("success", 2).containsEntry("failure", 1);
  }

  @Test
  void assetAndTargetDashboardsPassScopeToClickHouse() {
    AssetService assetService = mock(AssetService.class);
    FakeClickHouseClient clickHouseClient = new FakeClickHouseClient();
    DashboardQueryService service = new DashboardQueryService(assetService, clickHouseClient);

    service.agentDashboard(7L);
    service.snmpDashboard(11L);

    assertThat(clickHouseClient.agentAssetId).isEqualTo(7L);
    assertThat(clickHouseClient.snmpTargetId).isEqualTo(11L);
  }

  private static class FakeClickHouseClient extends ClickHouseClient {
    Long agentAssetId;
    Long snmpTargetId;

    FakeClickHouseClient() {
      super(new ManagerProperties(
          "12345678901234567890123456789012",
          "http://localhost:8780",
          new ManagerProperties.ClickHouse("http://localhost:18123", "castrelyx", "default", "", "castrelyx_agent_events")),
          RestClient.builder());
    }

    @Override
    public List<TrafficQueryService.InterfaceTraffic> queryInterfaceTraffic(String range, String assetUid) {
      return List.of(new TrafficQueryService.InterfaceTraffic(
          "edge-router", "eth0", 1_200_000, 900_000, 12.4, 0, 0, "up"));
    }

    @Override
    public Map<String, Object> queryAgentDashboard(Long assetId) {
      agentAssetId = assetId;
      return Map.of(
          "heartbeat", Map.of("healthy", 1, "stale", 1),
          "collectors", List.of("host", "network"),
          "resources", Map.of("cpu", 55.0),
          "events", List.of());
    }

    @Override
    public Map<String, Object> querySnmpDashboard(Long targetId) {
      snmpTargetId = targetId;
      return Map.of(
          "polls", Map.of("success", 2, "failure", 1),
          "targets", List.of("edge-router"),
          "interfaces", queryInterfaceTraffic("1h", null));
    }
  }
}
