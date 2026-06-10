package org.castrelyx.manager.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LogparserClient {
  private final RestClient restClient;
  private final IntegrationService integrationService;

  public LogparserClient(RestClient.Builder builder, IntegrationService integrationService) {
    this.restClient = builder.build();
    this.integrationService = integrationService;
  }

  public Map<String, Object> test() {
    status();
    return Map.of("ok", true);
  }

  public Object status() {
    IntegrationConfig config = integrationService.get("logparser");
    if (config.baseUrl() == null || config.baseUrl().isBlank()) {
      return Map.of("configured", false);
    }
    return restClient.get()
        .uri(URI.create(config.baseUrl() + "/api/v1/pipeline"))
        .retrieve()
        .body(Object.class);
  }

  public List<Map<String, String>> deepLinks() {
    IntegrationConfig config = integrationService.get("logparser");
    String baseUrl = config.baseUrl() == null ? "" : config.baseUrl();
    return List.of(
        Map.of("label", "Pipeline", "url", baseUrl + "/"),
        Map.of("label", "Input adapters", "url", baseUrl + "/#input-adapters"),
        Map.of("label", "Live tail", "url", baseUrl + "/#live-tail"));
  }

  public Object upsertSnmpInputAdapter(Map<String, Object> payload) {
    IntegrationConfig config = integrationService.get("logparser");
    if (config.baseUrl() == null || config.baseUrl().isBlank() || !config.enabled()) {
      return Map.of("skipped", true, "reason", "logparser integration disabled");
    }
    return restClient.post()
        .uri(URI.create(config.baseUrl() + "/api/v1/input-adapters"))
        .body(payload)
        .retrieve()
        .body(Object.class);
  }
}
