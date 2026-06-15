package org.castrelyx.manager.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.telemetry.TelemetrySyncWorker;
import org.junit.jupiter.api.Test;

class AssetControllerTest {
  @Test
  void listSyncsObservedAgentsWithoutRunningFullTelemetrySync() {
    AssetService assetService = mock(AssetService.class);
    TelemetrySyncWorker telemetrySyncWorker = mock(TelemetrySyncWorker.class);
    when(assetService.listAssets()).thenReturn(List.of());

    AssetController controller = new AssetController(assetService, telemetrySyncWorker);
    controller.list();

    verify(telemetrySyncWorker).syncObservedAgents();
    verify(telemetrySyncWorker, never()).syncOnce();
    verify(assetService).listAssets();
  }
}
