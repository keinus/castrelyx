package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetType;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TrafficQueryServiceTest {
  @Test
  void returnsInterfaceTrafficRowsFromClickHouse() {
    FakeClickHouseClient clickHouseClient = new FakeClickHouseClient();
    TrafficQueryService service = new TrafficQueryService(clickHouseClient, mock(AssetService.class));

    List<TrafficQueryService.InterfaceTraffic> rows = service.interfaces("1h");

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.assetUid()).isEqualTo("edge-router");
      assertThat(row.interfaceName()).isEqualTo("eth0");
      assertThat(row.inBps()).isEqualTo(1_200_000.0);
      assertThat(row.outBps()).isEqualTo(900_000.0);
      assertThat(row.status()).isEqualTo("up");
    });
    assertThat(clickHouseClient.range).isEqualTo("1h");
    assertThat(clickHouseClient.assetUid).isNull();
  }

  @Test
  void passesAssetScopeWhenRequested() {
    FakeClickHouseClient clickHouseClient = new FakeClickHouseClient();
    AssetService assetService = mock(AssetService.class);
    when(assetService.getAsset(42L)).thenReturn(new Asset(
        42L,
        "edge-router",
        "edge-router",
        AssetType.ROUTER,
        "192.168.10.1",
        null,
        null,
        "active",
        Instant.now(),
        Instant.now()));
    TrafficQueryService service = new TrafficQueryService(clickHouseClient, assetService);

    service.interfacesForAsset(42L, "30m");

    assertThat(clickHouseClient.range).isEqualTo("30m");
    assertThat(clickHouseClient.assetUid).isEqualTo("edge-router");
  }

  private static class FakeClickHouseClient extends ClickHouseClient {
    String range;
    String assetUid;

    FakeClickHouseClient() {
      super(new ManagerProperties(
          "12345678901234567890123456789012",
          "http://localhost:8780",
          new ManagerProperties.ClickHouse("http://localhost:18123", "castrelyx", "default", "", "castrelyx_agent_events")),
          RestClient.builder());
    }

    @Override
    public List<TrafficQueryService.InterfaceTraffic> queryInterfaceTraffic(String range, String assetUid) {
      this.range = range;
      this.assetUid = assetUid;
      return List.of(
          new TrafficQueryService.InterfaceTraffic(
              "edge-router",
              "eth0",
              1_200_000.0,
              900_000.0,
              12.4,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "edge-router",
              "lo",
              10_000_000.0,
              10_000_000.0,
              0,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "edge-router",
              "veth9a7c",
              10_000_000.0,
              10_000_000.0,
              0,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "edge-router",
              "docker0",
              10_000_000.0,
              10_000_000.0,
              0,
              0,
              0,
              "up"),
          new TrafficQueryService.InterfaceTraffic(
              "edge-router",
              "br-2fde3c3c1f2d",
              10_000_000.0,
              10_000_000.0,
              0,
              0,
              0,
              "up"));
    }
  }
}
