package org.castrelyx.castrelsign.api;

import java.util.List;

import org.castrelyx.castrelsign.persistence.EnrollmentTokenRepository.TokenRecord;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.castrelyx.castrelsign.security.EnrollmentTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/enrollment-tokens")
public class AdminEnrollmentTokenController {
  private static final int DEFAULT_TTL_SECONDS = 24 * 60 * 60;
  private static final int DEFAULT_MAX_USES = 1;

  private final AdminTokenService adminTokenService;
  private final EnrollmentTokenService enrollmentTokenService;

  public AdminEnrollmentTokenController(AdminTokenService adminTokenService, EnrollmentTokenService enrollmentTokenService) {
    this.adminTokenService = adminTokenService;
    this.enrollmentTokenService = enrollmentTokenService;
  }

  @PostMapping
  public EnrollmentTokenResponse create(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody CreateEnrollmentTokenRequest request) {
    adminTokenService.requireValid(authorization);
    var created = enrollmentTokenService.create(
        request == null ? null : request.name(),
        request == null ? null : request.agentId(),
        request == null || request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds(),
        request == null || request.maxUses() == null ? DEFAULT_MAX_USES : request.maxUses());
    return response(created.record(), created.token());
  }

  @GetMapping
  public List<EnrollmentTokenResponse> list(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return enrollmentTokenService.list().stream()
        .map(record -> response(record, null))
        .toList();
  }

  @PostMapping("/{id}/revoke")
  public ResponseEntity<Void> revoke(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable long id) {
    adminTokenService.requireValid(authorization);
    enrollmentTokenService.revoke(id);
    return ResponseEntity.noContent().build();
  }

  private static EnrollmentTokenResponse response(TokenRecord record, String token) {
    return new EnrollmentTokenResponse(
        record.id(),
        record.name(),
        token,
        record.agentId(),
        record.maxUses(),
        record.usedCount(),
        record.expiresAt(),
        record.revokedAt(),
        record.createdAt(),
        record.lastUsedAt(),
        record.lastUsedAgentId());
  }
}

