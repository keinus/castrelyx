package org.castrelyx.castrelsign.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRemoteTaskRepository {
  private final JdbcTemplate jdbcTemplate;

  public AgentRemoteTaskRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public RemoteTask create(String agentId, String taskType, String payloadJson, Instant expiresAt) {
    return create(agentId, taskType, payloadJson, expiresAt, UUID.randomUUID().toString());
  }

  public RemoteTask create(String agentId, String taskType, String payloadJson, Instant expiresAt, String taskId) {
    if (!UUID.fromString(taskId).toString().equals(taskId)) throw new IllegalArgumentException("invalid task ID");
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into agent_remote_tasks(task_id, agent_id, task_type, payload_json, status, created_at, updated_at, expires_at)
        values (?, ?, ?, ?, 'PENDING', ?, ?, ?)
        on conflict(task_id) do nothing
        """, taskId, require(agentId, "agent_id"), require(taskType, "task_type"), payloadJson == null ? "{}" : payloadJson,
        now, now, expiresAt.toString());
      RemoteTask existing = get(taskId);
      if (!existing.agentId().equals(agentId) || !existing.taskType().equals(taskType)
          || !existing.payloadJson().equals(payloadJson == null ? "{}" : payloadJson)) {
        throw new IllegalArgumentException("task ID was reused with different content");
      }
    return existing;
  }

  public RemoteTask get(String taskId) {
    var tasks = jdbcTemplate.query("""
        select task_id, agent_id, task_type, payload_json, status, result_json, error_message,
               created_at, updated_at, expires_at, claimed_at, completed_at
        from agent_remote_tasks
        where task_id = ?
        """, (rs, rowNum) -> new RemoteTask(
        rs.getString("task_id"),
        rs.getString("agent_id"),
        rs.getString("task_type"),
        rs.getString("payload_json"),
        rs.getString("status"),
        rs.getString("result_json"),
        rs.getString("error_message"),
        rs.getString("created_at"),
        rs.getString("updated_at"),
        rs.getString("expires_at"),
        rs.getString("claimed_at"),
        rs.getString("completed_at")), taskId);
    if (tasks.isEmpty()) {
      throw new IllegalArgumentException("remote task not found");
    }
    return tasks.getFirst();
  }

  public List<RemoteTask> poll(String agentId, int limit) {
    String now = Instant.now().toString();
    // Router tasks have bounded execution and a durable agent-side journal.
    // Reclaim only that task type; legacy SSH tasks have different semantics.
    jdbcTemplate.update("""
        update agent_remote_tasks set status='PENDING', claimed_at=null, updated_at=?
        where agent_id=? and task_type='security_router' and status='RUNNING' and claimed_at<? and expires_at>?
        """, now, agentId, Instant.now().minusSeconds(180).toString(), now);
    jdbcTemplate.update("""
        update agent_remote_tasks set status='EXPIRED', error_message='Task expired before execution', updated_at=?
        where agent_id=? and task_type='security_router' and status='PENDING' and expires_at<=?
        """, now, agentId, now);
    jdbcTemplate.update("""
        update agent_remote_tasks set status='UNKNOWN', error_message='Execution result unavailable; inspect router jobs', updated_at=?
        where agent_id=? and task_type='security_router' and status='RUNNING' and expires_at<=? and claimed_at<?
        """, now, agentId, now, Instant.now().minusSeconds(180).toString());
    List<String> taskIds = jdbcTemplate.query("""
        select task_id
        from agent_remote_tasks
        where agent_id = ? and status = 'PENDING' and expires_at > ?
        order by created_at
        limit ?
        """, (rs, rowNum) -> rs.getString("task_id"), require(agentId, "agent_id"), now, Math.max(1, Math.min(limit, 20)));
    return taskIds.stream()
        .filter(taskId -> claim(taskId, now))
        .map(this::get)
        .toList();
  }

  public RemoteTask complete(String agentId, String taskId, String status, String resultJson, String errorMessage) {
    if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) throw new IllegalArgumentException("invalid result status");
    if (resultJson != null && resultJson.length() > 4*1024*1024) throw new IllegalArgumentException("result too large");
    String normalizedStatus = "FAILED".equalsIgnoreCase(status) ? "FAILED" : "COMPLETED";
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agent_remote_tasks
        set status = ?, result_json = ?, error_message = ?, completed_at = ?, updated_at = ?
        where task_id = ? and agent_id = ? and status in ('PENDING', 'RUNNING', 'UNKNOWN')
        """, normalizedStatus, resultJson, errorMessage, now, now, taskId, require(agentId, "agent_id"));
    if (updated == 0) {
      RemoteTask existing = get(taskId);
      if (existing.agentId().equals(agentId) && existing.status().equals(normalizedStatus)
          && java.util.Objects.equals(existing.resultJson(), resultJson)
          && java.util.Objects.equals(existing.errorMessage(), errorMessage)) return existing;
      throw new IllegalArgumentException("remote task is not open for this agent");
    }
    return get(taskId);
  }

  private boolean claim(String taskId, String now) {
    return jdbcTemplate.update("""
        update agent_remote_tasks
        set status = 'RUNNING', claimed_at = ?, updated_at = ?
        where task_id = ? and status = 'PENDING'
        """, now, now, taskId) == 1;
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  public record RemoteTask(
      String taskId,
      String agentId,
      String taskType,
      String payloadJson,
      String status,
      String resultJson,
      String errorMessage,
      String createdAt,
      String updatedAt,
      String expiresAt,
      String claimedAt,
      String completedAt) {
  }
}
