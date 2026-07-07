package org.castrelyx.castrelvault.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpCastrelSignApplicationAdminClient implements CastrelSignApplicationAdminClient {
  private final CastrelVaultProperties properties;
  private final RestClient restClient;

  public HttpCastrelSignApplicationAdminClient(CastrelVaultProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    this.restClient = builder.build();
  }

  @Override
  public Status status() {
    if (!configured()) {
      return new Status(false, baseUrl(), "UNCONFIGURED", "CastrelSign admin token or base URL is not configured");
    }
    try {
      applications();
      return new Status(true, baseUrl(), "AVAILABLE", "CastrelSign application admin API is reachable");
    } catch (Exception e) {
      return new Status(true, baseUrl(), "UNAVAILABLE", "CastrelSign application admin API is not reachable");
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> applications() {
    return restClient.get()
        .uri(uri("/api/admin/applications"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .body(List.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> createApplication(Map<String, Object> request) {
    return restClient.post()
        .uri(uri("/api/admin/applications"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .body(request == null ? Map.of() : request)
        .retrieve()
        .body(Map.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> grantPermission(String principalId, Map<String, Object> request) {
    return restClient.post()
        .uri(uri("/api/admin/applications/" + segment(principalId) + "/permissions"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .body(request == null ? Map.of() : request)
        .retrieve()
        .body(Map.class);
  }

  @Override
  public void block(String principalId) {
    restClient.post()
        .uri(uri("/api/admin/applications/" + segment(principalId) + "/block"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void reactivate(String principalId) {
    restClient.post()
        .uri(uri("/api/admin/applications/" + segment(principalId) + "/reactivate"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> certificates() {
    return restClient.get()
        .uri(uri("/api/admin/applications/certificates"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .body(List.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> tokens() {
    return restClient.get()
        .uri(uri("/api/admin/application-enrollment-tokens"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .body(List.class);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> createToken(Map<String, Object> request) {
    return restClient.post()
        .uri(uri("/api/admin/application-enrollment-tokens"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .body(request == null ? Map.of() : request)
        .retrieve()
        .body(Map.class);
  }

  @Override
  public void revokeToken(long id) {
    restClient.post()
        .uri(uri("/api/admin/application-enrollment-tokens/" + id + "/revoke"))
        .header(HttpHeaders.AUTHORIZATION, authorization())
        .retrieve()
        .toBodilessEntity();
  }

  private URI uri(String path) {
    if (!configured()) {
      throw new IllegalStateException("CastrelSign application admin proxy is not configured");
    }
    return URI.create(baseUrl() + path);
  }

  private boolean configured() {
    return !isBlank(properties.getCastrelsignBaseUrl()) && !isBlank(properties.getCastrelsignAdminToken());
  }

  private String baseUrl() {
    String base = properties.getCastrelsignBaseUrl();
    if (base == null) {
      return "";
    }
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private String authorization() {
    if (isBlank(properties.getCastrelsignAdminToken())) {
      throw new IllegalStateException("CASTRELVAULT_CASTRELSIGN_ADMIN_TOKEN is required");
    }
    return "Bearer " + properties.getCastrelsignAdminToken();
  }

  private static String segment(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
