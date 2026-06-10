package org.castrelyx.manager.web;

import java.util.List;
import org.castrelyx.manager.telemetry.TrafficQueryService;
import org.castrelyx.manager.telemetry.TrafficQueryService.InterfaceTraffic;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrafficController {
  private final TrafficQueryService trafficQueryService;

  public TrafficController(TrafficQueryService trafficQueryService) {
    this.trafficQueryService = trafficQueryService;
  }

  @GetMapping("/api/traffic/interfaces")
  public List<InterfaceTraffic> interfaces(@RequestParam(defaultValue = "1h") String range) {
    return trafficQueryService.interfaces(range);
  }

  @GetMapping("/api/traffic/assets/{assetId}/interfaces")
  public List<InterfaceTraffic> assetInterfaces(@PathVariable long assetId, @RequestParam(defaultValue = "1h") String range) {
    return trafficQueryService.interfacesForAsset(assetId, range);
  }
}
