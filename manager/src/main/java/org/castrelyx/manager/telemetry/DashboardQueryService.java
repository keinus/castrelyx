package org.castrelyx.manager.telemetry;

import java.util.List;
import java.util.Map;
import org.castrelyx.manager.asset.AssetService;
import org.springframework.stereotype.Service;

@Service
public class DashboardQueryService {
  private final AssetService assetService;
  private final ClickHouseClient clickHouseClient;

  public DashboardQueryService(AssetService assetService, ClickHouseClient clickHouseClient) {
    this.assetService = assetService;
    this.clickHouseClient = clickHouseClient;
  }

  public Map<String, Object> overview() {
    Map<String, Object> agent = agentDashboard();
    Map<String, Object> snmp = snmpDashboard();
    return Map.of(
        "activeAssets", assetService.listAssets().size(),
        "criticalAlerts", 0,
        "trafficTopInterfaces", clickHouseClient.queryInterfaceTraffic("1h", null),
        "agentHealth", agent.getOrDefault("heartbeat", Map.of("healthy", 0, "stale", 0)),
        "snmpPollHealth", snmp.getOrDefault("polls", Map.of("success", 0, "failure", 0)));
  }

  public Map<String, Object> agentDashboard() {
    return agentDashboard(null);
  }

  public Map<String, Object> agentDashboard(Long assetId) {
    return clickHouseClient.queryAgentDashboard(assetId);
  }

  public Map<String, Object> snmpDashboard() {
    return snmpDashboard(null);
  }

  public Map<String, Object> snmpDashboard(Long targetId) {
    return clickHouseClient.querySnmpDashboard(targetId);
  }
}
