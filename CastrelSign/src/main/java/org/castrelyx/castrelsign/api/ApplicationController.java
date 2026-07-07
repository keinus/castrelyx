package org.castrelyx.castrelsign.api;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.castrelyx.castrelsign.crypto.CsrService;
import org.castrelyx.castrelsign.crypto.PemUtil;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository;
import org.castrelyx.castrelsign.security.ApplicationEnrollmentTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
  private final ApplicationEnrollmentTokenService tokenService;
  private final ApplicationPrincipalRepository repository;
  private final CsrService csrService;
  private final CertificateAuthority authority;
  private final CastrelSignProperties properties;

  public ApplicationController(ApplicationEnrollmentTokenService tokenService, ApplicationPrincipalRepository repository,
      CsrService csrService, CertificateAuthority authority, CastrelSignProperties properties) {
    this.tokenService = tokenService;
    this.repository = repository;
    this.csrService = csrService;
    this.authority = authority;
    this.properties = properties;
  }

  @PostMapping("/enroll")
  public ApplicationEnrollmentResponse enroll(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody ApplicationEnrollmentRequest request) {
    if (request == null || request.principalId() == null || request.principalId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principal_id is required");
    }
    var principal = repository.principal(request.principalId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application principal was not found"));
    if (!"ACTIVE".equals(principal.status())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "application principal is not active");
    }
    var csr = csrService.parseAndValidate(request.csrPem(), request.principalId());
    tokenService.consumeValid(authorization, request.principalId());
    X509Certificate certificate = authority.signAgentCertificate(csr, Duration.ofDays(properties.getCertValidDays()));
    repository.saveCertificate(request.principalId(), certificate);
    return new ApplicationEnrollmentResponse(
        request.principalId(),
        authority.rootCertificatePem(),
        PemUtil.certificateToPem(certificate),
        certificate.getNotAfter().toInstant().toString());
  }

  @GetMapping("/{principalId}/vault-access")
  public Map<String, Object> vaultAccess(@PathVariable String principalId,
      @RequestParam String permission,
      @RequestParam(name = "serial_number") String serialNumber) {
    var principal = repository.principal(principalId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application principal was not found"));
    if (!"ACTIVE".equals(principal.status())) {
      return decision(false, "application principal is not active");
    }
    if (!principal.permissions().contains(permission)) {
      return decision(false, "application principal is missing Vault permission");
    }
    if (!repository.hasActiveCertificate(principalId, serialNumber)) {
      return decision(false, "application certificate is not active");
    }
    return decision(true, "allowed");
  }

  private static Map<String, Object> decision(boolean allowed, String reason) {
    return Map.of(
        "allowed", allowed,
        "reason", reason,
        "cacheExpiresAt", Instant.now().plusSeconds(60).toString());
  }

  public record ApplicationEnrollmentRequest(
      @com.fasterxml.jackson.annotation.JsonProperty("principal_id") String principalId,
      @com.fasterxml.jackson.annotation.JsonProperty("csr_pem") String csrPem) {
  }

  public record ApplicationEnrollmentResponse(
      @com.fasterxml.jackson.annotation.JsonProperty("principal_id") String principalId,
      @com.fasterxml.jackson.annotation.JsonProperty("ca_cert_pem") String caCertPem,
      @com.fasterxml.jackson.annotation.JsonProperty("client_cert_pem") String clientCertPem,
      @com.fasterxml.jackson.annotation.JsonProperty("expires_at") String expiresAt) {
  }
}
