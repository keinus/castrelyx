package org.castrelyx.manager.web;

import java.util.Map;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.castrelyx.manager.integration.IntegrationConfig;
import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/castrelsign")
public class CastrelSignIntegrationController {
  private final IntegrationService integrationService;
  private final CastrelSignClient client;

  public CastrelSignIntegrationController(IntegrationService integrationService, CastrelSignClient client) {
    this.integrationService = integrationService;
    this.client = client;
  }

  @GetMapping
  public IntegrationConfig get() {
    return integrationService.get("castrelsign");
  }

  @PutMapping
  public IntegrationConfig update(@RequestBody IntegrationUpdateRequest request) {
    return integrationService.upsert("castrelsign", request);
  }

  @PostMapping("/test")
  public Map<String, Object> test() {
    return client.test();
  }
}
