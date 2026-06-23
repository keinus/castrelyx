package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetType;
import org.castrelyx.manager.asset.AssetUpdateRequest;
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

  @Test
  void updateDelegatesEditableFields() {
    AssetService assetService = mock(AssetService.class);
    TelemetrySyncWorker telemetrySyncWorker = mock(TelemetrySyncWorker.class);
    AssetUpdateRequest request = new AssetUpdateRequest("nas-main", "Seoul HQ", "Primary NAS");
    Asset updated = new Asset(7L, "nas", "nas-main", AssetType.LINUX_SERVER, "192.168.50.21", "Seoul HQ", "Primary NAS", "active", null, null);
    when(assetService.updateEditableFields(7L, request)).thenReturn(updated);

    AssetController controller = new AssetController(assetService, telemetrySyncWorker);
    Asset response = controller.update(7L, request);

    verify(assetService).updateEditableFields(7L, request);
    verify(assetService, never()).upsertObservedAsset(any(), any(), any(), any());
    assertThat(response).isSameAs(updated);
  }

  @Test
  void deleteDelegatesAssetDeletion() {
    AssetService assetService = mock(AssetService.class);
    TelemetrySyncWorker telemetrySyncWorker = mock(TelemetrySyncWorker.class);

    AssetController controller = new AssetController(assetService, telemetrySyncWorker);
    controller.delete(7L);

    verify(assetService).deleteAsset(7L);
  }
}
