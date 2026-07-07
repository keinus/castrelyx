package org.castrelyx.castrelvault.application;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class HttpCastrelSignAccessClient implements CastrelSignAccessClient {
  private final RestClient restClient;
  private final CastrelVaultProperties properties;

  public HttpCastrelSignAccessClient(RestClient.Builder builder, CastrelVaultProperties properties) {
    this.restClient = builder.build();
    this.properties = properties;
  }

  @Override
  @SuppressWarnings("unchecked")
  public AccessDecision checkVaultAccess(String principalId, String permission, String certificateSerialNumber) {
    String baseUrl = properties.getCastrelsignBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CastrelSign base URL is not configured");
    }
    URI uri = UriComponentsBuilder.fromUriString(baseUrl)
        .path("/api/applications/{principalId}/vault-access")
        .queryParam("permission", permission)
        .queryParam("serial_number", certificateSerialNumber)
        .buildAndExpand(principalId)
        .toUri();
    Map<String, Object> response = restClient.get().uri(uri).retrieve().body(Map.class);
    if (response == null) {
      return AccessDecision.denied("CastrelSign returned no decision");
    }
    boolean allowed = Boolean.TRUE.equals(response.get("allowed"));
    String reason = response.get("reason") == null ? null : String.valueOf(response.get("reason"));
    Instant expiresAt = parseInstant(response.get("cacheExpiresAt"));
    return allowed ? new AccessDecision(true, reason, expiresAt) : AccessDecision.denied(reason);
  }

  private static Instant parseInstant(Object value) {
    if (value == null || String.valueOf(value).isBlank()) {
      return Instant.now().plusSeconds(60);
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (Exception e) {
      return Instant.now().plusSeconds(60);
    }
  }
}
