package org.castrelyx.manager.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
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

  public List<?> listAuditEvents() {
    return getList("/api/admin/audit-events");
  }

  public String rootCaPem() {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.get()
        .uri(URI.create(config.baseUrl() + "/api/admin/ca.pem"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .body(String.class);
  }

  public String agentUpdatePublicKeyPem() {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.get()
        .uri(URI.create(config.baseUrl() + "/api/admin/agent-update-public-key.pem"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .body(String.class);
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

  public void blockAgent(String agentId) {
    postNoBody("/api/admin/agents/" + agentId + "/block");
  }

  public void reactivateAgent(String agentId) {
    postNoBody("/api/admin/agents/" + agentId + "/reactivate");
  }

  public List<?> listAgentReleases() {
    return getList("/api/admin/agent-releases");
  }

  public List<?> listAgentUpdatePolicies() {
    return getList("/api/admin/agent-update-policies");
  }

  public List<?> listAgentUpdateAttempts() {
    return getList("/api/admin/agent-update-attempts");
  }

  public Map<?, ?> createAgentRelease(String version, String os, String arch, String channel, byte[] artifact, String filename) {
    IntegrationConfig config = integrationService.get("castrelsign");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("version", version);
    body.add("os", os);
    body.add("arch", arch);
    body.add("channel", channel == null || channel.isBlank() ? "stable" : channel);
    body.add("artifact", new ByteArrayResource(artifact) {
      @Override
      public String getFilename() {
        return filename == null || filename.isBlank() ? "castrelyx-agent" : filename;
      }
    });
    return restClient.post()
        .uri(URI.create(config.baseUrl() + "/api/admin/agent-releases"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  public Map<?, ?> publishAgentRelease(String version, String os, String arch, String channel, byte[] artifact, String filename) {
    String normalizedChannel = channel == null || channel.isBlank() ? "stable" : channel.trim();
    Map<?, ?> created = createAgentRelease(version, os, arch, normalizedChannel, artifact, filename);
    long releaseId = longValue(created.get("id"));
    Map<?, ?> activated = activateAgentRelease(releaseId);
    updateAgentPolicy(Map.of(
        "enabled", true,
        "channel", normalizedChannel,
        "targetVersion", stringValue(created.get("version"), version)));
    return activated;
  }

  public Map<?, ?> activateAgentRelease(long id) {
    return postMap("/api/admin/agent-releases/" + id + "/activate");
  }

  public Map<?, ?> revokeAgentRelease(long id) {
    return postMap("/api/admin/agent-releases/" + id + "/revoke");
  }

  public Map<?, ?> updateAgentPolicy(Map<String, Object> request) {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.post()
        .uri(URI.create(config.baseUrl() + "/api/admin/agent-update-policy"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .body(request)
        .retrieve()
        .body(Map.class);
  }

  private List<?> getList(String path) {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.get()
        .uri(URI.create(config.baseUrl() + path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .body(List.class);
  }

  private void postNoBody(String path) {
    IntegrationConfig config = integrationService.get("castrelsign");
    restClient.post()
        .uri(URI.create(config.baseUrl() + path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .toBodilessEntity();
  }

  private Map<?, ?> postMap(String path) {
    IntegrationConfig config = integrationService.get("castrelsign");
    return restClient.post()
        .uri(URI.create(config.baseUrl() + path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + integrationService.decryptedSecret("castrelsign"))
        .retrieve()
        .body(Map.class);
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Long.parseLong(text);
    }
    throw new IllegalStateException("CastrelSign did not return an agent release id");
  }

  private static String stringValue(Object value, String fallback) {
    if (value == null || String.valueOf(value).isBlank()) {
      return fallback;
    }
    return String.valueOf(value);
  }
}
