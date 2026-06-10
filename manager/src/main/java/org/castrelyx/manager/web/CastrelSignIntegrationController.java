package org.castrelyx.manager.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.castrelyx.manager.integration.IntegrationConfig;
import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    return normalizeList(client.listEnrollmentTokens());
  }

  @PostMapping("/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createToken(@RequestBody(required = false) Map<String, Object> request) {
    return normalize(client.createEnrollmentToken(toEnrollmentTokenRequest(request == null ? Map.of() : request)));
  }

  @PostMapping("/tokens/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeToken(@PathVariable long id) {
    client.revokeEnrollmentToken(id);
  }

  @GetMapping("/agents")
  public List<?> agents() {
    return normalizeList(client.listAgents());
  }

  @GetMapping("/certificates")
  public List<?> certificates() {
    return normalizeList(client.listCertificates());
  }

  @GetMapping("/audit-events")
  public List<?> auditEvents() {
    return normalizeList(client.listAuditEvents());
  }

  @PostMapping("/agents/{agentId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void blockAgent(@PathVariable String agentId) {
    client.blockAgent(agentId);
  }

  @PostMapping("/agents/{agentId}/reactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivateAgent(@PathVariable String agentId) {
    client.reactivateAgent(agentId);
  }

  @PostMapping("/enrollment-packages")
  public ResponseEntity<byte[]> createEnrollmentPackage(@RequestBody EnrollmentPackageRequest request) throws IOException {
    if (request == null || request.agentId() == null || request.agentId().isBlank()) {
      throw new IllegalArgumentException("agentId is required");
    }
    IntegrationConfig config = integrationService.get("castrelsign");
    if (config.baseUrl() == null || config.baseUrl().isBlank()) {
      throw new IllegalArgumentException("CastrelSign baseUrl is required");
    }
    String agentId = request.agentId().trim();
    int ttlSeconds = request.ttlSeconds() == null ? 3600 : request.ttlSeconds();
    int maxUses = request.maxUses() == null ? 1 : request.maxUses();
    String tenantId = request.tenantId() == null || request.tenantId().isBlank() ? "default" : request.tenantId().trim();
    String tlsServerName = request.tlsServerName() == null || request.tlsServerName().isBlank()
        ? hostName(config.baseUrl())
        : request.tlsServerName().trim();
    rejectBlockedAgentPackage(agentId);
    Map<?, ?> createdToken = client.createEnrollmentToken(Map.of(
        "name", agentId + " initial enrollment",
        "agent_id", agentId,
        "ttl_seconds", ttlSeconds,
        "max_uses", maxUses));
    String enrollmentToken = stringValue(createdToken, "token");
    if (enrollmentToken == null || enrollmentToken.isBlank()) {
      throw new IllegalStateException("CastrelSign did not return enrollment token plaintext");
    }
    byte[] zip = packageZip(
        agentId,
        tenantId,
        config.baseUrl(),
        enrollmentToken,
        tlsServerName,
        client.rootCaPem());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"castrelsign-" + safeFilename(agentId) + "-enrollment.zip\"")
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(zip);
  }

  private void rejectBlockedAgentPackage(String agentId) {
    List<?> agents = client.listAgents();
    if (agents == null) {
      return;
    }
    for (Object value : agents) {
      if (!(value instanceof Map<?, ?> agent)) {
        continue;
      }
      String currentAgentId = stringValue(agent, "agent_id", "agentId");
      String status = stringValue(agent, "status");
      if (agentId.equals(currentAgentId) && "BLOCKED".equals(status)) {
        throw new IllegalArgumentException("blocked agents must be reactivated before issuing a new enrollment package");
      }
    }
  }

  private static List<?> normalizeList(List<?> values) {
    return values.stream().map(CastrelSignIntegrationController::normalize).toList();
  }

  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        normalized.put(camelKey(String.valueOf(entry.getKey())), normalize(entry.getValue()));
      }
      return normalized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(CastrelSignIntegrationController::normalize).toList();
    }
    return value;
  }

  private static Map<String, Object> toEnrollmentTokenRequest(Map<String, Object> request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    putIfPresent(payload, "name", first(request, "name", "description"));
    putIfPresent(payload, "agent_id", first(request, "agentId", "agent_id"));
    putIfPresent(payload, "ttl_seconds", first(request, "ttlSeconds", "ttl_seconds"));
    putIfPresent(payload, "max_uses", first(request, "maxUses", "max_uses"));
    return payload;
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static Object first(Map<String, Object> source, String firstKey, String secondKey) {
    Object first = source.get(firstKey);
    return first == null ? source.get(secondKey) : first;
  }

  private static String camelKey(String key) {
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '_') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(c));
        upperNext = false;
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }

  private static byte[] packageZip(String agentId, String tenantId, String managerUrl, String token,
      String tlsServerName, String caPem) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      addEntry(zip, "agent.yaml", agentYaml(agentId, tenantId, managerUrl, token, tlsServerName));
      addEntry(zip, "certs/ca.pem", caPem == null ? "" : caPem);
      addEntry(zip, "install.ps1", powershellInstall());
      addEntry(zip, "install.sh", shellInstall());
      addEntry(zip, "install.md", installGuide(agentId));
    }
    return out.toByteArray();
  }

  private static void addEntry(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String agentYaml(String agentId, String tenantId, String managerUrl, String token, String tlsServerName) {
    return """
        manager_url: %s
        enrollment_token: %s
        agent_id: %s
        tenant_id: %s
        cert_dir: ./certs
        ca_cert_path: ./certs/ca.pem
        tls_server_name: %s
        batch_interval: 30s
        spool_dir: ./spool
        collectors:
          - identity
          - metric
          - network
          - service
          - port
        """.formatted(managerUrl, token, agentId, tenantId, tlsServerName);
  }

  private static String powershellInstall() {
    return """
        $ErrorActionPreference = 'Stop'
        New-Item -ItemType Directory -Force -Path '.\\certs' | Out-Null
        Write-Host 'Copy this folder to the agent host, then run castrelyx-agent with .\\agent.yaml.'
        Write-Host 'The agent will create client.key and client.pem locally during first enrollment.'
        """;
  }

  private static String shellInstall() {
    return """
        #!/usr/bin/env sh
        set -eu
        mkdir -p ./certs
        echo "Copy this folder to the agent host, then run castrelyx-agent with ./agent.yaml."
        echo "The agent will create client.key and client.pem locally during first enrollment."
        """;
  }

  private static String installGuide(String agentId) {
    return """
        # CastrelSign enrollment package

        Agent ID: %s

        This package contains agent.yaml, certs/ca.pem, and helper scripts.
        It intentionally does not contain client.key or client.pem. The agent creates the private key locally and stores the issued client certificate after first enrollment.
        """.formatted(agentId);
  }

  private static String hostName(String baseUrl) {
    return URI.create(baseUrl).getHost();
  }

  private static String stringValue(Map<?, ?> source, String key) {
    Object value = source.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String stringValue(Map<?, ?> source, String firstKey, String secondKey) {
    String first = stringValue(source, firstKey);
    return first == null ? stringValue(source, secondKey) : first;
  }

  private static String safeFilename(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  public record EnrollmentPackageRequest(
      String agentId,
      String tenantId,
      Integer ttlSeconds,
      Integer maxUses,
      String tlsServerName) {
  }
}
