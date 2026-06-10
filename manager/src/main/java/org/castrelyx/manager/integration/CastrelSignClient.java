package org.castrelyx.manager.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CastrelSignClient {
  private final RestClient restClient;
  private final IntegrationService integrationService;

  public CastrelSignClient(RestClient.Builder builder, IntegrationService integrationService) {
    this.restClient = builder.build();
    this.integrationService = integrationService;
  }

  public Map<String, Object> test() {
    listEnrollmentTokens();
    return Map.of("ok", true);
  }

  public List<?> listEnrollmentTokens() {
    return getList("/api/admin/enrollment-tokens");
  }

  public List<?> listAgents() {
    return getList("/api/admin/agents");
  }

  public List<?> listCertificates() {
    return getList("/api/admin/certificates");
  }

  public Map<?, ?> createEnrollmentToken(Map<String, Object> request) {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.post()
        .uri(URI.create(config.baseUrl() + "/api/admin/enrollment-tokens"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .body(request)
        .retrieve()
        .body(Map.class);
  }

  public void revokeEnrollmentToken(long id) {
    IntegrationConfig config = integrationService.get("castrelsign");
    restClient.post()
        .uri(URI.create(config.baseUrl() + "/api/admin/enrollment-tokens/" + id + "/revoke"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .toBodilessEntity();
  }

  private List<?> getList(String path) {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.get()
        .uri(URI.create(config.baseUrl() + path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .body(List.class);
  }
}
