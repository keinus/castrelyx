package org.castrelyx.manager.integration;

public record IntegrationUpdateRequest(String baseUrl, String adminToken, String apiToken, boolean enabled) {
  public String secret() {
    return adminToken != null ? adminToken : apiToken;
  }
}
