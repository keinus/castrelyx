package org.castrelyx.castrelsign.api;

import java.util.List;
import org.castrelyx.castrelsign.persistence.AgentRemoteTaskRepository;
import org.castrelyx.castrelsign.persistence.AgentRemoteTaskRepository.RemoteTask;
import org.castrelyx.castrelsign.security.ClientCertificateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentRemoteTaskController {
  private final ClientCertificateService certificateService;
  private final AgentRemoteTaskRepository repository;

  public AgentRemoteTaskController(ClientCertificateService certificateService, AgentRemoteTaskRepository repository) {
    this.certificateService = certificateService;
    this.repository = repository;
  }

  @PostMapping("/poll")
  public List<RemoteTask> poll(HttpServletRequest servletRequest, @RequestBody(required = false) PollRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    int limit = request == null || request.maxTasks() == null ? 5 : request.maxTasks();
    return repository.poll(identity.agentId(), limit);
  }

  @PostMapping("/{taskId}/result")
  public ResponseEntity<RemoteTask> result(HttpServletRequest servletRequest,
      @PathVariable String taskId,
      @RequestBody TaskResultRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    RemoteTask task = repository.complete(
        identity.agentId(),
        taskId,
        request == null ? "FAILED" : request.status(),
        request == null ? "{}" : request.resultJson(),
        request == null ? "missing result body" : request.errorMessage());
    return ResponseEntity.accepted().body(task);
  }

  public record PollRequest(Integer maxTasks) {
  }

  public record TaskResultRequest(
      String status,
      String resultJson,
      String errorMessage) {
  }
}
