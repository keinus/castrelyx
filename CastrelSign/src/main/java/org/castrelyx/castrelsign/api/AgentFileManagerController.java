package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository.FileCommand;
import org.castrelyx.castrelsign.persistence.AgentFileManagerRepository.Transfer;
import org.castrelyx.castrelsign.security.ClientCertificateService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/agent/file-manager")
public class AgentFileManagerController {
  private static final long MAX_TRANSFER_BYTES = 256L * 1024L * 1024L;

  private final ClientCertificateService certificateService;
  private final AgentFileManagerRepository repository;
  private final ObjectMapper objectMapper;

  public AgentFileManagerController(ClientCertificateService certificateService,
      AgentFileManagerRepository repository,
      ObjectMapper objectMapper) {
    this.certificateService = certificateService;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/check")
  public PollResponse check(HttpServletRequest servletRequest, @RequestBody(required = false) PollRequest request)
      throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    int limit = request == null || request.maxCommands() == null ? 5 : request.maxCommands();
    List<AgentCommand> commands = repository.poll(identity.agentId(), limit).stream()
        .map(this::toAgentCommand)
        .toList();
    return new PollResponse(commands);
  }

  @PostMapping("/commands/{commandId}/result")
  public ResponseEntity<FileCommand> result(HttpServletRequest servletRequest,
      @PathVariable String commandId,
      @RequestBody(required = false) CommandResultRequest request) throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    String responseJson = objectMapper.writeValueAsString(
        request == null || request.response() == null ? objectMapper.createObjectNode() : request.response());
    FileCommand command = repository.complete(
        identity.agentId(),
        commandId,
        request == null ? "FAILED" : request.status(),
        responseJson,
        request == null ? "missing result body" : request.error());
    return ResponseEntity.accepted().body(command);
  }

  @GetMapping("/transfers/{transferId}/content")
  public ResponseEntity<byte[]> downloadTransfer(HttpServletRequest servletRequest, @PathVariable String transferId)
      throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    Transfer transfer = requireTransferForAgent(identity.agentId(), transferId, "UPLOAD_TO_AGENT");
    byte[] body = repository.readTransferContent(transfer.transferId());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(transfer.contentType()))
        .contentLength(body.length)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(transfer.filename()).build().toString())
        .body(body);
  }

  @PostMapping("/transfers/{transferId}/content")
  public ResponseEntity<Transfer> uploadTransfer(HttpServletRequest servletRequest,
      @PathVariable String transferId,
      @RequestHeader(name = "X-Castrelyx-Filename", required = false) String filename,
      @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestBody(required = false) byte[] content) throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    Transfer transfer = requireTransferForAgent(identity.agentId(), transferId, "DOWNLOAD_FROM_AGENT");
    byte[] safeContent = content == null ? new byte[0] : content;
    if (safeContent.length > MAX_TRANSFER_BYTES) {
      throw new IllegalArgumentException("file transfer exceeds max size");
    }
    return ResponseEntity.accepted().body(repository.storeTransferContent(
        transfer.transferId(),
        sanitizeFilename(filename, transfer.filename()),
        contentType == null || contentType.isBlank() ? transfer.contentType() : contentType,
        safeContent));
  }

  private AgentCommand toAgentCommand(FileCommand command) {
    try {
      JsonNode request = objectMapper.readTree(command.requestJson());
      return new AgentCommand(command.commandId(), command.operation(), request, command.createdAt());
    } catch (IOException e) {
      throw new IllegalArgumentException("stored file command request is not valid JSON", e);
    }
  }

  private Transfer requireTransferForAgent(String agentId, String transferId, String direction) {
    Transfer transfer = repository.getTransfer(transferId);
    if (!direction.equals(transfer.direction())) {
      throw new IllegalArgumentException("file transfer has invalid direction");
    }
    FileCommand command = repository.getCommand(transfer.commandId());
    if (!agentId.equals(command.agentId())) {
      throw new IllegalArgumentException("file transfer does not belong to this agent");
    }
    return transfer;
  }

  private static String sanitizeFilename(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null || fallback.isBlank() ? "download.bin" : fallback;
    }
    return value.replace("\r", "").replace("\n", "");
  }

  public record PollRequest(@JsonProperty("max_commands") Integer maxCommands) {
  }

  public record PollResponse(List<AgentCommand> commands) {
  }

  public record AgentCommand(
      String id,
      String operation,
      JsonNode request,
      @JsonProperty("created_at") String createdAt) {
  }

  public record CommandResultRequest(
      String status,
      JsonNode response,
      String error) {
  }
}
