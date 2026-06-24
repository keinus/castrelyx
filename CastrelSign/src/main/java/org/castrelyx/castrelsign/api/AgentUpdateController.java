package org.castrelyx.castrelsign.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.castrelyx.castrelsign.security.ClientCertificateService;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.AgentRelease;
import org.castrelyx.castrelsign.update.AgentUpdateService;
import org.castrelyx.castrelsign.update.AgentUpdateService.UpdateCheckRequest;
import org.castrelyx.castrelsign.update.AgentUpdateService.UpdateCheckResponse;
import org.castrelyx.castrelsign.update.AgentUpdateService.UpdateStatusRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/agent/updates")
public class AgentUpdateController {
  private final ClientCertificateService certificateService;
  private final AgentUpdateService updateService;

  public AgentUpdateController(ClientCertificateService certificateService, AgentUpdateService updateService) {
    this.certificateService = certificateService;
    this.updateService = updateService;
  }

  @PostMapping("/check")
  public UpdateCheckResponse check(HttpServletRequest servletRequest, @RequestBody UpdateCheckRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    return updateService.check(identity.agentId(), request);
  }

  @GetMapping("/artifacts/{releaseId}")
  public ResponseEntity<byte[]> artifact(HttpServletRequest servletRequest, @PathVariable("releaseId") long releaseId) throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    AgentRelease release = updateService.artifact(identity.agentId(), releaseId);
    byte[] content = Files.readAllBytes(Path.of(release.artifactPath()));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"castrelyx-agent-" + release.version() + "-" + release.os() + "-" + release.arch() + "\"")
        .body(content);
  }

  @PostMapping("/status")
  public ResponseEntity<Void> status(HttpServletRequest servletRequest, @RequestBody UpdateStatusRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    updateService.recordStatus(identity.agentId(), request);
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/{deploymentId}/status")
  public ResponseEntity<Void> statusForDeployment(HttpServletRequest servletRequest,
      @PathVariable("deploymentId") String deploymentId,
      @RequestBody UpdateStatusRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    updateService.recordStatus(identity.agentId(), new UpdateStatusRequest(
        deploymentId,
        request == null ? 0 : request.releaseId(),
        request == null ? null : request.fromVersion(),
        request == null ? null : request.status(),
        request == null ? null : request.message()));
    return ResponseEntity.accepted().build();
  }
}
