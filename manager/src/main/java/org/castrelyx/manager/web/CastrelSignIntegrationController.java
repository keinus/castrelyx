package org.castrelyx.manager.web;

import java.util.List;
import java.util.Map;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.castrelyx.manager.integration.IntegrationConfig;
import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

  @GetMapping("/tokens")
  public List<?> tokens() {
    return client.listEnrollmentTokens();
  }

  @PostMapping("/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<?, ?> createToken(@RequestBody(required = false) Map<String, Object> request) {
    return client.createEnrollmentToken(request == null ? Map.of() : request);
  }

  @PostMapping("/tokens/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeToken(@PathVariable long id) {
    client.revokeEnrollmentToken(id);
  }

  @GetMapping("/agents")
  public List<?> agents() {
    return client.listAgents();
  }

  @GetMapping("/certificates")
  public List<?> certificates() {
    return client.listCertificates();
  }
}
