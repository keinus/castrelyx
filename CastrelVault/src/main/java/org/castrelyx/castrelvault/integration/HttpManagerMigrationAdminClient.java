package org.castrelyx.castrelvault.integration;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpManagerMigrationAdminClient implements ManagerMigrationAdminClient {
  private final CastrelVaultProperties properties;
  private final RestClient restClient;

  public HttpManagerMigrationAdminClient(CastrelVaultProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    this.restClient = builder.build();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> status() {
    if (!configured()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("configured", false);
      response.put("status", "UNCONFIGURED");
      response.put("managerBaseUrl", baseUrl());
      response.put("detail", "Manager migration token or base URL is not configured");
      return response;
    }
    try {
      return restClient.get()
          .uri(uri("/api/vault-migration/status"))
          .header(HttpHeaders.AUTHORIZATION, authorization())
          .retrieve()
          .body(Map.class);
    } catch (Exception e) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("configured", true);
      response.put("status", "UNAVAILABLE");
      response.put("managerBaseUrl", baseUrl());
      response.put("detail", "Manager Vault migration API is not reachable");
      return response;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> dryRun() {
    return restClient.post()
        .uri(uri("/api/vault-migration/dry-run"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .body(Map.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> run(String vaultAdminSessionToken) {
    return restClient.post()
        .uri(uri("/api/vault-migration/run"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .header("X-CastrelVault-Admin-Session", vaultAdminSessionToken == null ? "" : vaultAdminSessionToken)
        .retrieve()
        .body(Map.class);
  }

  private URI uri(String path) {
    if (!configured()) {
      throw new IllegalStateException("Manager Vault migration proxy is not configured");
    }
    return URI.create(baseUrl() + path);
  }

  private boolean configured() {
    return !isBlank(properties.getManagerBaseUrl()) && !isBlank(properties.getManagerMigrationToken());
  }

  private String baseUrl() {
    String base = properties.getManagerBaseUrl();
    if (base == null) {
      return "";
    }
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private String authorization() {
    if (isBlank(properties.getManagerMigrationToken())) {
      throw new IllegalStateException("CASTRELVAULT_MANAGER_MIGRATION_TOKEN is required");
    }
    return "Bearer " + properties.getManagerMigrationToken();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
