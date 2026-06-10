package org.castrelyx.manager.telemetry;

import java.util.List;
import org.castrelyx.manager.asset.AssetService;
import org.springframework.stereotype.Service;

@Service
public class TrafficQueryService {
  private final ClickHouseClient clickHouseClient;
  private final AssetService assetService;

  public TrafficQueryService(ClickHouseClient clickHouseClient, AssetService assetService) {
    this.clickHouseClient = clickHouseClient;
    this.assetService = assetService;
  }

  public List<InterfaceTraffic> interfaces(String range) {
    return clickHouseClient.queryInterfaceTraffic(range, null);
  }

  public List<InterfaceTraffic> interfacesForAsset(long assetId, String range) {
    return clickHouseClient.queryInterfaceTraffic(range, assetService.getAsset(assetId).assetUid());
  }

  public record InterfaceTraffic(
      String assetUid,
      String interfaceName,
      double inBps,
      double outBps,
      double utilizationPct,
      long errors,
      long discards,
      String status) {
  }
}
