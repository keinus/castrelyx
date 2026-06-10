package org.castrelyx.manager.web;

import java.util.List;
import java.util.Map;
import org.castrelyx.manager.integration.IntegrationConfig;
import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.castrelyx.manager.integration.LogparserClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/logparser")
public class LogparserIntegrationController {
  private final IntegrationService integrationService;
  private final LogparserClient client;

  public LogparserIntegrationController(IntegrationService integrationService, LogparserClient client) {
    this.integrationService = integrationService;
    this.client = client;
  }

  @GetMapping
  public IntegrationConfig get() {
    return integrationService.get("logparser");
  }

  @PutMapping
  public IntegrationConfig update(@RequestBody IntegrationUpdateRequest request) {
    return integrationService.upsert("logparser", request);
  }

  @PostMapping("/test")
  public Map<String, Object> test() {
    return client.test();
  }

  @GetMapping("/status")
  public Object status() {
    return client.status();
  }

  @GetMapping("/deep-links")
  public List<Map<String, String>> deepLinks() {
    return client.deepLinks();
  }
}
