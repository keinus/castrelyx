package org.castrelyx.manager.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.castrelyx.manager.telemetry.DashboardQueryService;
import org.castrelyx.manager.telemetry.TelemetrySyncWorker;
import org.junit.jupiter.api.Test;

class DashboardControllerTest {
  @Test
  void agentDashboardQueriesWithoutRunningFullTelemetrySync() {
    DashboardQueryService dashboardQueryService = mock(DashboardQueryService.class);
    TelemetrySyncWorker telemetrySyncWorker = mock(TelemetrySyncWorker.class);
    when(dashboardQueryService.agentDashboard()).thenReturn(Map.of("heartbeat", Map.of("healthy", 1, "stale", 0)));

    DashboardController controller = new DashboardController(dashboardQueryService, telemetrySyncWorker);
    controller.agent();

    verify(telemetrySyncWorker, never()).syncOnce();
    verify(dashboardQueryService).agentDashboard();
  }
}
