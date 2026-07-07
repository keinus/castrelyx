package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository.FileCommand;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository.Transfer;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/file-manager")
public class AdminAgentFileManagerController {
  private static final long MAX_TRANSFER_BYTES = 256L * 1024L * 1024L;

  private final AdminTokenService adminTokenService;
  private final AgentFileManagerRepository repository;
  private final ObjectMapper objectMapper;

  public AdminAgentFileManagerController(AdminTokenService adminTokenService,
      AgentFileManagerRepository repository,
      ObjectMapper objectMapper) {
    this.adminTokenService = adminTokenService;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/agents/{agentId}/commands")
  @ResponseStatus(HttpStatus.CREATED)
  public FileCommand create(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId,
      @RequestBody FileCommandRequest request) throws JsonProcessingException {
    adminTokenService.requireValid(authorization);
    if (request == null || request.operation() == null || request.operation().isBlank()) {
      throw new IllegalArgumentException("operation is required");
    }
    JsonNode payload = request.request() == null ? objectMapper.createObjectNode() : request.request();
    return repository.createCommand(
        agentId,
        request.operation().trim().toUpperCase(),
        objectMapper.writeValueAsString(payload),
        expiresAt(request == null ? null : request.ttlSeconds()));
  }

  @PostMapping(value = "/agents/{agentId}/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public FileCommand createUpload(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId,
      @RequestParam String path,
      @RequestParam(defaultValue = "true") boolean overwrite,
      @RequestParam(name = "ttl_seconds", required = false) Long ttlSeconds,
      @RequestPart MultipartFile file) throws IOException {
    adminTokenService.requireValid(authorization);
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file is required");
    }
    if (file.getSize() > MAX_TRANSFER_BYTES) {
      throw new IllegalArgumentException("file transfer exceeds max size");
    }
    FileCommand command = repository.createCommand(agentId, "UPLOAD", "{}", expiresAt(ttlSeconds));
    Transfer transfer = repository.createTransfer(
        command.commandId(),
        "UPLOAD_TO_AGENT",
        sanitizeFilename(file.getOriginalFilename(), filenameFromPath(path, "upload.bin")),
        file.getContentType(),
        file.getBytes());
    return repository.updateCommandRequest(command.commandId(),
        objectMapper.writeValueAsString(commandPayload(
            "path", path,
            "transfer_id", transfer.transferId(),
            "overwrite", overwrite)));
  }

  @PostMapping("/agents/{agentId}/downloads")
  @ResponseStatus(HttpStatus.CREATED)
  public FileCommand createDownload(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId,
      @RequestBody DownloadRequest request) throws IOException {
    adminTokenService.requireValid(authorization);
    if (request == null || request.path() == null || request.path().isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    FileCommand command = repository.createCommand(agentId, "DOWNLOAD", "{}", expiresAt(request.ttlSeconds()));
    Transfer transfer = repository.createTransfer(
        command.commandId(),
        "DOWNLOAD_FROM_AGENT",
        filenameFromPath(request.path(), "download.bin"),
        "application/octet-stream",
        null);
    return repository.updateCommandRequest(command.commandId(),
        objectMapper.writeValueAsString(commandPayload(
            "path", request.path(),
            "transfer_id", transfer.transferId())));
  }

  @GetMapping("/commands/{commandId}")
  public FileCommand get(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String commandId) {
    adminTokenService.requireValid(authorization);
    return repository.getCommand(commandId);
  }

  @GetMapping("/commands/{commandId}/download")
  public ResponseEntity<byte[]> download(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String commandId) throws IOException {
    adminTokenService.requireValid(authorization);
    FileCommand command = repository.getCommand(commandId);
    if (!"SUCCEEDED".equals(command.status())) {
      throw new IllegalArgumentException("download command has not completed");
    }
    Transfer transfer = repository.transferForCommand(commandId, "DOWNLOAD_FROM_AGENT");
    if (transfer.completedAt() == null || transfer.completedAt().isBlank()) {
      throw new IllegalArgumentException("download content is not ready");
    }
    byte[] body = repository.readTransferContent(transfer.transferId());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(transfer.contentType()))
        .contentLength(body.length)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(transfer.filename()).build().toString())
        .body(body);
  }

  private Instant expiresAt(Long ttlSeconds) {
    long seconds = ttlSeconds == null ? 120 : Math.max(10, Math.min(ttlSeconds, 3600));
    return Instant.now().plus(Duration.ofSeconds(seconds));
  }

  private Map<String, Object> commandPayload(Object... values) {
    Map<String, Object> payload = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      payload.put(String.valueOf(values[i]), values[i + 1]);
    }
    return payload;
  }

  private static String filenameFromPath(String path, String fallback) {
    if (path == null || path.isBlank()) {
      return fallback;
    }
    String normalized = path.replace('\\', '/');
    int index = normalized.lastIndexOf('/');
    String name = index >= 0 ? normalized.substring(index + 1) : normalized;
    return sanitizeFilename(name, fallback);
  }

  private static String sanitizeFilename(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null || fallback.isBlank() ? "transfer.bin" : fallback;
    }
    String name = value.replace("\r", "").replace("\n", "");
    return name.isBlank() ? fallback : name;
  }

  public record FileCommandRequest(
      String operation,
      JsonNode request,
      @JsonProperty("ttl_seconds") Long ttlSeconds) {
  }

  public record DownloadRequest(
      String path,
      @JsonProperty("ttl_seconds") Long ttlSeconds) {
  }
}
