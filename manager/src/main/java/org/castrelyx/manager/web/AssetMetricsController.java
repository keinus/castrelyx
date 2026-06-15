package org.castrelyx.manager.web;

import java.util.Map;
import org.castrelyx.manager.telemetry.AssetMetricsQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/assets")
public class AssetMetricsController {
  private final AssetMetricsQueryService assetMetricsQueryService;

  public AssetMetricsController(AssetMetricsQueryService assetMetricsQueryService) {
    this.assetMetricsQueryService = assetMetricsQueryService;
  }

  @GetMapping
  public Map<String, Object> overview(@RequestParam(defaultValue = "1h") String range) {
    return assetMetricsQueryService.overview(range);
  }

  @GetMapping("/{assetUid}")
  public Map<String, Object> detail(
      @PathVariable String assetUid,
      @RequestParam(defaultValue = "1h") String range,
      @RequestParam(defaultValue = "auto") String bucket) {
    return assetMetricsQueryService.detail(assetUid, range, bucket);
  }
}
