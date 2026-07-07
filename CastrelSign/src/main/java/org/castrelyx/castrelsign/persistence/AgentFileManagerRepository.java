package org.castrelyx.castrelsign.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentFileManagerRepository {
  private final JdbcTemplate jdbcTemplate;
  private final CastrelSignProperties properties;

  public AgentFileManagerRepository(JdbcTemplate jdbcTemplate, CastrelSignProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  public FileCommand createCommand(String agentId, String operation, String requestJson, Instant expiresAt) {
    String commandId = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into agent_file_commands(command_id, agent_id, operation, request_json, status, created_at, updated_at, expires_at)
        values (?, ?, ?, ?, 'QUEUED', ?, ?, ?)
        """, commandId, require(agentId, "agent_id"), require(operation, "operation"),
        requestJson == null || requestJson.isBlank() ? "{}" : requestJson, now, now, expiresAt.toString());
    return getCommand(commandId);
  }

  public FileCommand updateCommandRequest(String commandId, String requestJson) {
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agent_file_commands
        set request_json = ?, updated_at = ?
        where command_id = ? and status = 'QUEUED'
        """, requestJson == null || requestJson.isBlank() ? "{}" : requestJson, now, require(commandId, "command_id"));
    if (updated == 0) {
      throw new IllegalArgumentException("file command request cannot be changed");
    }
    return getCommand(commandId);
  }

  public FileCommand getCommand(String commandId) {
    var commands = jdbcTemplate.query("""
        select command_id, agent_id, operation, request_json, status, response_json, error_message,
               created_at, updated_at, expires_at, claimed_at, completed_at
        from agent_file_commands
        where command_id = ?
        """, (rs, rowNum) -> new FileCommand(
        rs.getString("command_id"),
        rs.getString("agent_id"),
        rs.getString("operation"),
        rs.getString("request_json"),
        rs.getString("status"),
        rs.getString("response_json"),
        rs.getString("error_message"),
        rs.getString("created_at"),
        rs.getString("updated_at"),
        rs.getString("expires_at"),
        rs.getString("claimed_at"),
        rs.getString("completed_at")), require(commandId, "command_id"));
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("file command not found");
    }
    return commands.getFirst();
  }

  public List<FileCommand> poll(String agentId, int limit) {
    String now = Instant.now().toString();
    List<String> commandIds = jdbcTemplate.query("""
        select command_id
        from agent_file_commands
        where agent_id = ? and status = 'QUEUED' and expires_at > ?
        order by created_at
        limit ?
        """, (rs, rowNum) -> rs.getString("command_id"), require(agentId, "agent_id"), now, Math.max(1, Math.min(limit, 20)));
    return commandIds.stream()
        .filter(commandId -> claim(commandId, now))
        .map(this::getCommand)
        .toList();
  }

  public FileCommand complete(String agentId, String commandId, String status, String responseJson, String errorMessage) {
    String normalizedStatus = "SUCCEEDED".equalsIgnoreCase(status) ? "SUCCEEDED" : "FAILED";
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update agent_file_commands
        set status = ?, response_json = ?, error_message = ?, completed_at = ?, updated_at = ?
        where command_id = ? and agent_id = ? and status in ('QUEUED', 'RUNNING')
        """, normalizedStatus, responseJson == null ? "{}" : responseJson, errorMessage, now, now,
        require(commandId, "command_id"), require(agentId, "agent_id"));
    if (updated == 0) {
      throw new IllegalArgumentException("file command is not open for this agent");
    }
    return getCommand(commandId);
  }

  public Transfer createTransfer(String commandId, String direction, String filename, String contentType, byte[] content) throws IOException {
    String transferId = UUID.randomUUID().toString();
    Path path = transferPath(transferId);
    Files.createDirectories(path.getParent());
    long size = 0;
    String completedAt = null;
    if (content != null) {
      Files.write(path, content);
      size = content.length;
      completedAt = Instant.now().toString();
    }
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into agent_file_transfers(transfer_id, command_id, direction, filename, content_type, size_bytes, storage_path, created_at, completed_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, transferId, require(commandId, "command_id"), require(direction, "direction"),
        filename == null || filename.isBlank() ? "transfer.bin" : filename,
        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
        size, path.toString(), now, completedAt);
    return getTransfer(transferId);
  }

  public Transfer getTransfer(String transferId) {
    var transfers = jdbcTemplate.query("""
        select transfer_id, command_id, direction, filename, content_type, size_bytes, storage_path, created_at, completed_at
        from agent_file_transfers
        where transfer_id = ?
        """, (rs, rowNum) -> new Transfer(
        rs.getString("transfer_id"),
        rs.getString("command_id"),
        rs.getString("direction"),
        rs.getString("filename"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("storage_path"),
        rs.getString("created_at"),
        rs.getString("completed_at")), require(transferId, "transfer_id"));
    if (transfers.isEmpty()) {
      throw new IllegalArgumentException("file transfer not found");
    }
    return transfers.getFirst();
  }

  public Transfer transferForCommand(String commandId, String direction) {
    var ids = jdbcTemplate.query("""
        select transfer_id
        from agent_file_transfers
        where command_id = ? and direction = ?
        order by created_at desc
        limit 1
        """, (rs, rowNum) -> rs.getString("transfer_id"), require(commandId, "command_id"), require(direction, "direction"));
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("file transfer not found for command");
    }
    return getTransfer(ids.getFirst());
  }

  public byte[] readTransferContent(String transferId) throws IOException {
    Transfer transfer = getTransfer(transferId);
    return Files.readAllBytes(Path.of(transfer.storagePath()));
  }

  public Transfer storeTransferContent(String transferId, String filename, String contentType, byte[] content) throws IOException {
    Transfer transfer = getTransfer(transferId);
    Path path = Path.of(transfer.storagePath());
    Files.createDirectories(path.getParent());
    Files.write(path, content == null ? new byte[0] : content);
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        update agent_file_transfers
        set filename = ?, content_type = ?, size_bytes = ?, completed_at = ?
        where transfer_id = ?
        """,
        filename == null || filename.isBlank() ? transfer.filename() : filename,
        contentType == null || contentType.isBlank() ? transfer.contentType() : contentType,
        content == null ? 0 : content.length,
        now,
        transferId);
    return getTransfer(transferId);
  }

  public Instant defaultExpiry() {
    return Instant.now().plus(Duration.ofMinutes(10));
  }

  private boolean claim(String commandId, String now) {
    return jdbcTemplate.update("""
        update agent_file_commands
        set status = 'RUNNING', claimed_at = ?, updated_at = ?
        where command_id = ? and status = 'QUEUED'
        """, now, now, commandId) == 1;
  }

  private Path transferPath(String transferId) {
    return properties.getDataDir().resolve("file-manager").resolve("transfers").resolve(transferId + ".bin");
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  public record FileCommand(
      String commandId,
      String agentId,
      String operation,
      String requestJson,
      String status,
      String responseJson,
      String errorMessage,
      String createdAt,
      String updatedAt,
      String expiresAt,
      String claimedAt,
      String completedAt) {
  }

  public record Transfer(
      String transferId,
      String commandId,
      String direction,
      String filename,
      String contentType,
      long sizeBytes,
      String storagePath,
      String createdAt,
      String completedAt) {
  }
}
