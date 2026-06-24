package org.castrelyx.castrelsign.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.AgentRelease;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.NewRelease;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.UpdateAttempt;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.UpdatePolicy;
import org.springframework.stereotype.Service;

@Service
public class AgentUpdateService {
  private final CastrelSignProperties properties;
  private final AgentUpdateRepository repository;
  private final AgentRepository agentRepository;
  private final UpdateSigner signer;
  private final ObjectMapper objectMapper;

  public AgentUpdateService(CastrelSignProperties properties, AgentUpdateRepository repository,
      AgentRepository agentRepository, UpdateSigner signer, ObjectMapper objectMapper) {
    this.properties = properties;
    this.repository = repository;
    this.agentRepository = agentRepository;
    this.signer = signer;
    this.objectMapper = objectMapper;
  }

  public AgentRelease createRelease(String version, String os, String arch, String channel, byte[] artifact) {
    String normalizedVersion = requireText(version, "version");
    String normalizedOs = requireText(os, "os");
    String normalizedArch = requireText(arch, "arch");
    String normalizedChannel = channel == null || channel.isBlank() ? "stable" : channel.trim();
    if (artifact == null || artifact.length == 0) {
      throw new IllegalArgumentException("artifact is required");
    }
    try {
      Files.createDirectories(releaseDir());
      String sha256 = sha256(artifact);
      String createdAt = Instant.now().toString();
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("version", normalizedVersion);
      manifest.put("os", normalizedOs);
      manifest.put("arch", normalizedArch);
      manifest.put("channel", normalizedChannel);
      manifest.put("sha256", sha256);
      manifest.put("size_bytes", artifact.length);
      manifest.put("created_at", createdAt);
      String manifestJson = objectMapper.writeValueAsString(manifest);
      String signature = signer.sign(manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      Path artifactPath = releaseDir().resolve(safe(normalizedVersion) + "-" + normalizedOs + "-" + normalizedArch
          + "-" + normalizedChannel + "-" + UUID.randomUUID() + ".bin");
      Files.write(artifactPath, artifact);
      return repository.insertRelease(new NewRelease(
          normalizedVersion,
          normalizedOs,
          normalizedArch,
          normalizedChannel,
          sha256,
          artifact.length,
          signature,
          manifestJson,
          artifactPath.toString(),
          createdAt));
    } catch (Exception e) {
      if (e instanceof IllegalArgumentException argument) {
        throw argument;
      }
      throw new IllegalStateException("failed to create agent release", e);
    }
  }

  public List<AgentRelease> listReleases() {
    return repository.listReleases();
  }

  public List<UpdateAttempt> listAttempts() {
    return repository.listAttempts();
  }

  public AgentRelease activateRelease(long id) {
    repository.findRelease(id).orElseThrow(() -> new IllegalArgumentException("release not found"));
    repository.activateRelease(id);
    return repository.findRelease(id).orElseThrow();
  }

  public AgentRelease revokeRelease(long id) {
    repository.findRelease(id).orElseThrow(() -> new IllegalArgumentException("release not found"));
    repository.revokeRelease(id);
    return repository.findRelease(id).orElseThrow();
  }

  public UpdatePolicy upsertPolicy(String agentId, boolean enabled, String channel, String targetVersion) {
    return repository.upsertPolicy(agentId, enabled, channel == null || channel.isBlank() ? "stable" : channel.trim(), targetVersion);
  }

  public List<UpdatePolicy> policies() {
    return repository.listPolicies();
  }

  public UpdatePolicy effectivePolicy(String agentId, String requestedChannel) {
    return repository.effectivePolicy(agentId, requestedChannel);
  }

  public UpdateCheckResponse check(String agentId, UpdateCheckRequest request) {
    String normalizedAgentId = requireText(agentId, "agent_id");
    if (agentRepository.isBlocked(normalizedAgentId)) {
      throw new IllegalArgumentException("agent is blocked");
    }
    String os = requireText(request == null ? null : request.os(), "os");
    String arch = requireText(request == null ? null : request.arch(), "arch");
    String currentVersion = request == null ? null : request.version();
    String requestedChannel = request == null ? null : request.channel();
    UpdatePolicy policy = repository.effectivePolicy(normalizedAgentId, requestedChannel);
    if (!policy.enabled()) {
      return UpdateCheckResponse.none(policy);
    }
    return repository.findActiveRelease(os, arch, policy.channel(), policy.targetVersion())
        .filter(release -> !release.version().equals(currentVersion))
        .map(release -> available(normalizedAgentId, currentVersion, release, policy))
        .orElseGet(() -> UpdateCheckResponse.none(policy));
  }

  public AgentRelease artifact(String agentId, long releaseId) {
    String normalizedAgentId = requireText(agentId, "agent_id");
    if (agentRepository.isBlocked(normalizedAgentId)) {
      throw new IllegalArgumentException("agent is blocked");
    }
    AgentRelease release = repository.findRelease(releaseId)
        .orElseThrow(() -> new IllegalArgumentException("release not found"));
    if (!"ACTIVE".equals(release.status())) {
      throw new IllegalArgumentException("release is not active");
    }
    UpdatePolicy policy = repository.effectivePolicy(normalizedAgentId, release.channel());
    AgentRelease allowed = repository.findActiveRelease(release.os(), release.arch(), policy.channel(), policy.targetVersion())
        .orElseThrow(() -> new IllegalArgumentException("release is not allowed by policy"));
    if (allowed.id() != release.id()) {
      throw new IllegalArgumentException("release is not allowed by policy");
    }
    return release;
  }

  public void recordStatus(String agentId, UpdateStatusRequest request) {
    if (request == null || request.deploymentId() == null || request.deploymentId().isBlank()) {
      throw new IllegalArgumentException("deployment_id is required");
    }
    long releaseId = request.releaseId();
    repository.findRelease(releaseId).orElseThrow(() -> new IllegalArgumentException("release not found"));
    repository.upsertAttempt(
        request.deploymentId().trim(),
        requireText(agentId, "agent_id"),
        releaseId,
        request.fromVersion(),
        requireText(request.status(), "status"),
        request.message());
    agentRepository.audit("AGENT_UPDATE_" + request.status().trim(), agentId, request.message() == null ? "" : request.message());
  }

  public String publicKeyPem() {
    return signer.publicKeyPem();
  }

  private UpdateCheckResponse available(String agentId, String fromVersion, AgentRelease release, UpdatePolicy policy) {
    String deploymentId = UUID.randomUUID().toString();
    repository.upsertAttempt(deploymentId, agentId, release.id(), fromVersion, "OFFERED", null);
    return new UpdateCheckResponse(
        true,
        deploymentId,
        policy,
        release,
        "/api/agent/updates/artifacts/" + release.id(),
        release.manifestJson(),
        release.signature());
  }

  private Path releaseDir() {
    return properties.getDataDir().resolve("agent-releases");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private static String sha256(byte[] artifact) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
  }

  private static String safe(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  public record UpdateCheckRequest(
      String version,
      String os,
      String arch,
      String channel,
      @JsonProperty("install_mode")
      String installMode) {
  }

  public record UpdateStatusRequest(
      @JsonProperty("deployment_id")
      String deploymentId,
      @JsonProperty("release_id")
      long releaseId,
      @JsonProperty("from_version")
      String fromVersion,
      String status,
      String message) {
  }

  public record UpdateCheckResponse(
      @JsonProperty("update_available")
      boolean updateAvailable,
      @JsonProperty("deployment_id")
      String deploymentId,
      UpdatePolicy policy,
      AgentRelease release,
      @JsonProperty("artifact_url")
      String artifactUrl,
      String manifest,
      String signature) {
    public static UpdateCheckResponse none(UpdatePolicy policy) {
      return new UpdateCheckResponse(false, null, policy, null, null, null, null);
    }
  }
}
