package org.castrelyx.castrelsign.api;

import java.io.IOException;
import java.util.List;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.AgentRelease;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.UpdateAttempt;
import org.castrelyx.castrelsign.update.AgentUpdateRepository.UpdatePolicy;
import org.castrelyx.castrelsign.update.AgentUpdateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentUpdateController {
  private final AdminTokenService adminTokenService;
  private final AgentUpdateService updateService;

  public AdminAgentUpdateController(AdminTokenService adminTokenService, AgentUpdateService updateService) {
    this.adminTokenService = adminTokenService;
    this.updateService = updateService;
  }

  @PostMapping(value = "/agent-releases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AgentRelease createRelease(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestParam("version") String version,
      @RequestParam("os") String os,
      @RequestParam("arch") String arch,
      @RequestParam(name = "channel", defaultValue = "stable") String channel,
      @RequestParam("artifact") MultipartFile artifact) throws IOException {
    adminTokenService.requireValid(authorization);
    return updateService.createRelease(version, os, arch, channel, artifact.getBytes());
  }

  @GetMapping("/agent-releases")
  public List<AgentRelease> releases(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return updateService.listReleases();
  }

  @PostMapping("/agent-releases/{id}/activate")
  public AgentRelease activateRelease(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable("id") long id) {
    adminTokenService.requireValid(authorization);
    return updateService.activateRelease(id);
  }

  @PostMapping("/agent-releases/{id}/revoke")
  public AgentRelease revokeRelease(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable("id") long id) {
    adminTokenService.requireValid(authorization);
    return updateService.revokeRelease(id);
  }

  @GetMapping("/agent-update-policies")
  public List<UpdatePolicy> policies(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return updateService.policies();
  }

  @PostMapping("/agent-update-policy")
  public UpdatePolicy updatePolicy(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody UpdatePolicyRequest request) {
    adminTokenService.requireValid(authorization);
    return updateService.upsertPolicy(
        request == null ? null : request.agentId(),
        request == null || request.enabled() == null || request.enabled(),
        request == null ? "stable" : request.channel(),
        request == null ? null : request.targetVersion());
  }

  @GetMapping("/agent-update-attempts")
  public List<UpdateAttempt> attempts(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return updateService.listAttempts();
  }

  @GetMapping(value = "/agent-update-public-key.pem", produces = MediaType.TEXT_PLAIN_VALUE)
  public String publicKey(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return updateService.publicKeyPem();
  }

  public record UpdatePolicyRequest(
      String agentId,
      Boolean enabled,
      String channel,
      String targetVersion) {
  }
}
