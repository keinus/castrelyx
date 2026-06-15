package org.castrelyx.manager.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.castrelyx.manager.telemetry.AssetMetricsQueryService;
import org.junit.jupiter.api.Test;

class AssetMetricsControllerTest {
  @Test
  void overviewAndDetailDelegateToMetricQueryService() {
    AssetMetricsQueryService service = mock(AssetMetricsQueryService.class);
    when(service.overview("6h")).thenReturn(Map.of("assets", List.of()));
    when(service.detail("nas", "6h", "15m")).thenReturn(Map.of("asset", Map.of("assetUid", "nas")));

    AssetMetricsController controller = new AssetMetricsController(service);
    controller.overview("6h");
    controller.detail("nas", "6h", "15m");

    verify(service).overview("6h");
    verify(service).detail("nas", "6h", "15m");
  }
}
