package org.castrelyx.manager.web;

import java.util.Map;
import org.castrelyx.manager.telemetry.DashboardQueryService;
import org.castrelyx.manager.telemetry.TelemetrySyncWorker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
  private final DashboardQueryService dashboardQueryService;
  private final TelemetrySyncWorker telemetrySyncWorker;

  public DashboardController(DashboardQueryService dashboardQueryService, TelemetrySyncWorker telemetrySyncWorker) {
    this.dashboardQueryService = dashboardQueryService;
    this.telemetrySyncWorker = telemetrySyncWorker;
  }

  @GetMapping("/api/dashboards/overview")
  public Map<String, Object> overview() {
    return dashboardQueryService.overview();
  }

  @GetMapping("/api/dashboards/agent")
  public Map<String, Object> agent() {
    return dashboardQueryService.agentDashboard();
  }

  @GetMapping("/api/dashboards/agent/assets/{assetId}")
  public Map<String, Object> agentAsset(@PathVariable long assetId) {
    return dashboardQueryService.agentDashboard(assetId);
  }

  @GetMapping("/api/dashboards/snmp")
  public Map<String, Object> snmp() {
    return dashboardQueryService.snmpDashboard();
  }

  @GetMapping("/api/dashboards/snmp/targets/{targetId}")
  public Map<String, Object> snmpTarget(@PathVariable long targetId) {
    return dashboardQueryService.snmpDashboard(targetId);
  }

  @PostMapping("/api/telemetry/sync")
  public Map<String, Object> sync() {
    return telemetrySyncWorker.syncOnce();
  }
}
