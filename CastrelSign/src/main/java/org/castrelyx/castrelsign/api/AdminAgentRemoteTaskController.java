package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.castrelyx.castrelsign.persistence.AgentRemoteTaskRepository;
import org.castrelyx.castrelsign.persistence.AgentRemoteTaskRepository.RemoteTask;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentRemoteTaskController {
  private final AdminTokenService adminTokenService;
  private final AgentRemoteTaskRepository repository;
  private final ObjectMapper objectMapper;

  public AdminAgentRemoteTaskController(AdminTokenService adminTokenService, AgentRemoteTaskRepository repository,
      ObjectMapper objectMapper) {
    this.adminTokenService = adminTokenService;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/agents/{agentId}/tasks")
  @ResponseStatus(HttpStatus.CREATED)
  public RemoteTask create(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId,
      @RequestBody RemoteTaskRequest request) throws JsonProcessingException {
    adminTokenService.requireValid(authorization);
    if (request == null || request.type() == null || request.type().isBlank()) {
      throw new IllegalArgumentException("type is required");
    }
    long ttlSeconds = request.ttlSeconds() == null ? 120 : Math.max(10, Math.min(request.ttlSeconds(), 3600));
    String payload = objectMapper.writeValueAsString(request.payload());
    if (payload.length()>1024*1024) throw new IllegalArgumentException("task payload too large");
    return repository.create(agentId, request.type(), payload,
        Instant.now().plus(Duration.ofSeconds(ttlSeconds)), request.requestId() == null ? java.util.UUID.randomUUID().toString() : request.requestId());
  }

  @GetMapping("/agent-tasks/{taskId}")
  public RemoteTask get(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String taskId) {
    adminTokenService.requireValid(authorization);
    return repository.get(taskId);
  }

  public record RemoteTaskRequest(
      String type,
      Map<String, Object> payload,
      Long ttlSeconds, String requestId) {
    public RemoteTaskRequest(String type, Map<String, Object> payload, Long ttlSeconds) {
      this(type, payload, ttlSeconds, null);
    }
    public RemoteTaskRequest {
      if (payload == null) {
        payload = Map.of();
      }
    }
  }
}
