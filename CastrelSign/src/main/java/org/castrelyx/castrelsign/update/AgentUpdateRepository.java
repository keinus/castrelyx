package org.castrelyx.castrelsign.update;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AgentUpdateRepository {
  private final JdbcTemplate jdbcTemplate;

  public AgentUpdateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public AgentRelease insertRelease(NewRelease release) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into agent_releases(version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path, created_at)
          values (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, release.version());
      statement.setString(2, release.os());
      statement.setString(3, release.arch());
      statement.setString(4, release.channel());
      statement.setString(5, release.sha256());
      statement.setLong(6, release.sizeBytes());
      statement.setString(7, release.signature());
      statement.setString(8, release.manifestJson());
      statement.setString(9, release.artifactPath());
      statement.setString(10, release.createdAt());
      return statement;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key != null) {
      return findRelease(key.longValue()).orElseThrow();
    }
    return findReleaseByArtifactPath(release.artifactPath()).orElseThrow();
  }

  public List<AgentRelease> listReleases() {
    return jdbcTemplate.query("""
        select id, version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path,
               created_at, activated_at, revoked_at
        from agent_releases
        order by created_at desc, id desc
        """, AgentUpdateRepository::release);
  }

  public Optional<AgentRelease> findRelease(long id) {
    return jdbcTemplate.query("""
        select id, version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path,
               created_at, activated_at, revoked_at
        from agent_releases
        where id = ?
        """, AgentUpdateRepository::release, id).stream().findFirst();
  }

  public Optional<AgentRelease> findReleaseByArtifactPath(String artifactPath) {
    return jdbcTemplate.query("""
        select id, version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path,
               created_at, activated_at, revoked_at
        from agent_releases
        where artifact_path = ?
        """, AgentUpdateRepository::release, artifactPath).stream().findFirst();
  }

  public Optional<AgentRelease> findActiveRelease(String os, String arch, String channel, String targetVersion) {
    List<AgentRelease> rows;
    if (targetVersion == null || targetVersion.isBlank()) {
      rows = jdbcTemplate.query("""
          select id, version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path,
                 created_at, activated_at, revoked_at
          from agent_releases
          where os = ? and arch = ? and channel = ? and status = 'ACTIVE'
          order by activated_at desc, created_at desc, id desc
          limit 1
          """, AgentUpdateRepository::release, os, arch, channel);
    } else {
      rows = jdbcTemplate.query("""
          select id, version, os, arch, channel, status, sha256, size_bytes, signature, manifest_json, artifact_path,
                 created_at, activated_at, revoked_at
          from agent_releases
          where os = ? and arch = ? and channel = ? and version = ? and status = 'ACTIVE'
          order by activated_at desc, created_at desc, id desc
          limit 1
          """, AgentUpdateRepository::release, os, arch, channel, targetVersion);
    }
    return rows.stream().findFirst();
  }

  public void activateRelease(long id) {
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        update agent_releases
        set status = 'ACTIVE', activated_at = ?, revoked_at = null
        where id = ?
        """, now, id);
  }

  public void revokeRelease(long id) {
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        update agent_releases
        set status = 'REVOKED', revoked_at = ?
        where id = ?
        """, now, id);
  }

  public UpdatePolicy upsertPolicy(String agentId, boolean enabled, String channel, String targetVersion) {
    String normalizedAgentId = agentId == null || agentId.isBlank() ? null : agentId.trim();
    String policyKey = normalizedAgentId == null ? "global" : "agent:" + normalizedAgentId;
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agent_update_policies
        set agent_id = ?, enabled = ?, channel = ?, target_version = ?, updated_at = ?
        where policy_key = ?
        """, normalizedAgentId, enabled ? 1 : 0, channel, blankToNull(targetVersion), now, policyKey);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into agent_update_policies(policy_key, agent_id, enabled, channel, target_version, updated_at)
          values (?, ?, ?, ?, ?, ?)
          """, policyKey, normalizedAgentId, enabled ? 1 : 0, channel, blankToNull(targetVersion), now);
    }
    return findPolicy(normalizedAgentId).orElseThrow();
  }

  public Optional<UpdatePolicy> findPolicy(String agentId) {
    String normalizedAgentId = agentId == null || agentId.isBlank() ? null : agentId.trim();
    String policyKey = normalizedAgentId == null ? "global" : "agent:" + normalizedAgentId;
    return jdbcTemplate.query("""
        select policy_key, agent_id, enabled, channel, target_version, updated_at
        from agent_update_policies
        where policy_key = ?
        """, AgentUpdateRepository::policy, policyKey).stream().findFirst();
  }

  public List<UpdatePolicy> listPolicies() {
    return jdbcTemplate.query("""
        select policy_key, agent_id, enabled, channel, target_version, updated_at
        from agent_update_policies
        order by case when policy_key = 'global' then 0 else 1 end, policy_key
        """, AgentUpdateRepository::policy);
  }

  public UpdatePolicy effectivePolicy(String agentId, String requestedChannel) {
    return findPolicy(agentId)
        .or(() -> findPolicy(null))
        .orElseGet(() -> new UpdatePolicy("default", null, true, defaultChannel(requestedChannel), null, Instant.EPOCH.toString()));
  }

  public void upsertAttempt(String deploymentId, String agentId, long releaseId, String fromVersion, String status, String message) {
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agent_update_attempts
        set status = ?, message = ?, updated_at = ?
        where deployment_id = ?
        """, status, message, now, deploymentId);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into agent_update_attempts(deployment_id, agent_id, release_id, from_version, status, message, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, ?)
          """, deploymentId, agentId, releaseId, fromVersion, status, message, now, now);
    }
  }

  public List<UpdateAttempt> listAttempts() {
    return jdbcTemplate.query("""
        select id, deployment_id, agent_id, release_id, from_version, status, message, created_at, updated_at
        from agent_update_attempts
        order by updated_at desc, id desc
        """, AgentUpdateRepository::attempt);
  }

  private static AgentRelease release(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new AgentRelease(
        rs.getLong("id"),
        rs.getString("version"),
        rs.getString("os"),
        rs.getString("arch"),
        rs.getString("channel"),
        rs.getString("status"),
        rs.getString("sha256"),
        rs.getLong("size_bytes"),
        rs.getString("signature"),
        rs.getString("manifest_json"),
        rs.getString("artifact_path"),
        rs.getString("created_at"),
        rs.getString("activated_at"),
        rs.getString("revoked_at"));
  }

  private static UpdatePolicy policy(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new UpdatePolicy(
        rs.getString("policy_key"),
        rs.getString("agent_id"),
        rs.getInt("enabled") != 0,
        rs.getString("channel"),
        rs.getString("target_version"),
        rs.getString("updated_at"));
  }

  private static UpdateAttempt attempt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new UpdateAttempt(
        rs.getLong("id"),
        rs.getString("deployment_id"),
        rs.getString("agent_id"),
        rs.getLong("release_id"),
        rs.getString("from_version"),
        rs.getString("status"),
        rs.getString("message"),
        rs.getString("created_at"),
        rs.getString("updated_at"));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String defaultChannel(String channel) {
    return channel == null || channel.isBlank() ? "stable" : channel.trim();
  }

  public record NewRelease(
      String version,
      String os,
      String arch,
      String channel,
      String sha256,
      long sizeBytes,
      String signature,
      String manifestJson,
      String artifactPath,
      String createdAt) {
  }

  public record AgentRelease(
      long id,
      String version,
      String os,
      String arch,
      String channel,
      String status,
      String sha256,
      long sizeBytes,
      String signature,
      String manifestJson,
      String artifactPath,
      String createdAt,
      String activatedAt,
      String revokedAt) {
  }

  public record UpdatePolicy(
      String policyKey,
      String agentId,
      boolean enabled,
      String channel,
      String targetVersion,
      String updatedAt) {
  }

  public record UpdateAttempt(
      long id,
      String deploymentId,
      String agentId,
      long releaseId,
      String fromVersion,
      String status,
      String message,
      String createdAt,
      String updatedAt) {
  }
}
