package org.castrelyx.castrelsign.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository.ApplicationEnrollmentTokenRecord;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository.ApplicationPrincipalRecord;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.castrelyx.castrelsign.security.ApplicationEnrollmentTokenService;
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
public class AdminApplicationController {
  private static final int DEFAULT_TTL_SECONDS = 24 * 60 * 60;

  private final AdminTokenService adminTokenService;
  private final ApplicationPrincipalRepository repository;
  private final ApplicationEnrollmentTokenService tokenService;

  public AdminApplicationController(AdminTokenService adminTokenService, ApplicationPrincipalRepository repository,
      ApplicationEnrollmentTokenService tokenService) {
    this.adminTokenService = adminTokenService;
    this.repository = repository;
    this.tokenService = tokenService;
  }

  @GetMapping("/applications")
  public List<ApplicationPrincipalRecord> applications(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return repository.listPrincipals();
  }

  @GetMapping("/applications/certificates")
  public List<ApplicationPrincipalRepository.ApplicationCertificateRecord> certificates(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return repository.listCertificates();
  }

  @PostMapping("/applications")
  public ApplicationPrincipalRecord createApplication(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody CreateApplicationRequest request) {
    adminTokenService.requireValid(authorization);
    return repository.upsertPrincipal(request.principalId(), request.displayName());
  }

  @PostMapping("/applications/{principalId}/permissions")
  public ApplicationPrincipalRecord grantPermission(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String principalId,
      @RequestBody GrantPermissionRequest request) {
    adminTokenService.requireValid(authorization);
    return repository.grantPermission(principalId, request.permission());
  }

  @PostMapping("/applications/{principalId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void block(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String principalId) {
    adminTokenService.requireValid(authorization);
    repository.setStatus(principalId, "BLOCKED");
  }

  @PostMapping("/applications/{principalId}/reactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivate(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String principalId) {
    adminTokenService.requireValid(authorization);
    repository.setStatus(principalId, "ACTIVE");
  }

  @GetMapping("/application-enrollment-tokens")
  public List<ApplicationEnrollmentTokenResponse> tokens(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return tokenService.list().stream()
        .map(record -> tokenResponse(record, null))
        .toList();
  }

  @PostMapping("/application-enrollment-tokens")
  public ApplicationEnrollmentTokenResponse createToken(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody CreateApplicationEnrollmentTokenRequest request) {
    adminTokenService.requireValid(authorization);
    var created = tokenService.create(
        request == null ? null : request.name(),
        request == null ? null : request.principalId(),
        request == null || request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds());
    return tokenResponse(created.record(), created.token());
  }

  @PostMapping("/application-enrollment-tokens/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeToken(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable long id) {
    adminTokenService.requireValid(authorization);
    tokenService.revoke(id);
  }

  public record CreateApplicationRequest(
      @JsonProperty("principal_id") String principalId,
      @JsonProperty("display_name") String displayName) {
  }

  public record GrantPermissionRequest(String permission) {
  }

  public record CreateApplicationEnrollmentTokenRequest(
      String name,
      @JsonProperty("principal_id") String principalId,
      @JsonProperty("ttl_seconds") Integer ttlSeconds) {
  }

  public record ApplicationEnrollmentTokenResponse(
      long id,
      String name,
      @JsonProperty("principal_id") String principalId,
      @JsonProperty("expires_at") String expiresAt,
      @JsonProperty("used_at") String usedAt,
      @JsonProperty("revoked_at") String revokedAt,
      String token) {
  }

  private static ApplicationEnrollmentTokenResponse tokenResponse(ApplicationEnrollmentTokenRecord record, String token) {
    return new ApplicationEnrollmentTokenResponse(
        record.id(),
        record.name(),
        record.principalId(),
        record.expiresAt().toString(),
        record.usedAt() == null ? null : record.usedAt().toString(),
        record.revokedAt() == null ? null : record.revokedAt().toString(),
        token);
  }
}
