package org.castrelyx.castrelvault.application;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import javax.naming.ldap.LdapName;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationAccessService {
  private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  private static final long DEFAULT_CACHE_TTL_SECONDS = 60;

  private final JdbcTemplate jdbcTemplate;
  private final CastrelVaultProperties properties;
  private final CastrelSignAccessClient castrelSignAccessClient;
  private final AuditService auditService;

  public ApplicationAccessService(JdbcTemplate jdbcTemplate, CastrelVaultProperties properties,
      CastrelSignAccessClient castrelSignAccessClient, AuditService auditService) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.castrelSignAccessClient = castrelSignAccessClient;
    this.auditService = auditService;
  }

  public ApplicationPrincipal requireVaultAccess(HttpServletRequest request, String permission) {
    X509Certificate certificate = certificate(request);
    try {
      certificate.checkValidity();
      verifyConfiguredCa(certificate);
    } catch (Exception e) {
      auditService.record("APPLICATION", null, null, null, "APPLICATION_CERT_ACCESS", "DENIED", "invalid certificate", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate is invalid");
    }
    String principalId = commonName(certificate);
    if (principalId == null || principalId.isBlank()) {
      auditService.record("APPLICATION", null, null, null, "APPLICATION_CERT_ACCESS", "DENIED", "missing principal id", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate common name is missing");
    }
    String serial = certificate.getSerialNumber().toString(16);
    if (cachedAllowed(principalId, serial, permission, certificate.getNotAfter().toInstant())) {
      auditService.record("APPLICATION", principalId, null, null, "APPLICATION_CERT_ACCESS", "ALLOWED", "cache", request);
      return new ApplicationPrincipal(principalId, serial);
    }
    CastrelSignAccessClient.AccessDecision decision;
    try {
      decision = castrelSignAccessClient.checkVaultAccess(principalId, permission, serial);
    } catch (ResponseStatusException e) {
      auditService.record("APPLICATION", principalId, null, null, "APPLICATION_CERT_ACCESS", "DENIED", e.getReason(), request);
      throw e;
    } catch (Exception e) {
      auditService.record("APPLICATION", principalId, null, null, "APPLICATION_CERT_ACCESS", "DENIED", "CastrelSign unavailable", request);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CastrelSign access decision is unavailable");
    }
    cache(principalId, serial, permission, decision, certificate.getNotAfter().toInstant());
    if (!decision.allowed()) {
      auditService.record("APPLICATION", principalId, null, null, "APPLICATION_CERT_ACCESS", "DENIED", decision.reason(), request);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
    auditService.record("APPLICATION", principalId, null, null, "APPLICATION_CERT_ACCESS", "ALLOWED", decision.reason(), request);
    return new ApplicationPrincipal(principalId, serial);
  }

  private X509Certificate certificate(HttpServletRequest request) {
    Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
    if (!(attribute instanceof X509Certificate[] certificates) || certificates.length == 0) {
      auditService.record("APPLICATION", null, null, null, "APPLICATION_CERT_ACCESS", "DENIED", "missing certificate", request);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate is required");
    }
    return certificates[0];
  }

  private void verifyConfiguredCa(X509Certificate certificate) throws Exception {
    String caPath = properties.getCastrelsignCaCertPath();
    if (caPath == null || caPath.isBlank()) {
      return;
    }
    try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(caPath))) {
      X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      certificate.verify(ca.getPublicKey());
    }
  }

  private boolean cachedAllowed(String principalId, String serial, String permission, Instant certificateExpiresAt) {
    List<String> rows = jdbcTemplate.query("""
        select decision
        from vault_application_access_cache
        where principal_id = ? and certificate_serial = ? and permission = ? and expires_at > ?
        """, (rs, rowNum) -> rs.getString("decision"), principalId, serial, permission, Instant.now().toString());
    return !rows.isEmpty()
        && "ALLOWED".equals(rows.getFirst())
        && certificateExpiresAt.isAfter(Instant.now());
  }

  private void cache(String principalId, String serial, String permission, CastrelSignAccessClient.AccessDecision decision,
      Instant certificateExpiresAt) {
    Instant now = Instant.now();
    Instant expiresAt = decision.cacheExpiresAt() == null ? now.plusSeconds(DEFAULT_CACHE_TTL_SECONDS) : decision.cacheExpiresAt();
    if (expiresAt.isAfter(certificateExpiresAt)) {
      expiresAt = certificateExpiresAt;
    }
    if (!expiresAt.isAfter(now)) {
      expiresAt = now.plusSeconds(1);
    }
    jdbcTemplate.update("""
        insert into vault_application_access_cache(principal_id, certificate_serial, permission, decision, reason, expires_at, checked_at)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict(principal_id, certificate_serial, permission) do update set
          decision = excluded.decision,
          reason = excluded.reason,
          expires_at = excluded.expires_at,
          checked_at = excluded.checked_at
        """, principalId, serial, permission, decision.allowed() ? "ALLOWED" : "DENIED",
        decision.reason(), expiresAt.toString(), now.toString());
  }

  private String commonName(X509Certificate certificate) {
    try {
      LdapName name = new LdapName(certificate.getSubjectX500Principal().getName());
      for (var rdn : name.getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return String.valueOf(rdn.getValue());
        }
      }
      return null;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "failed to parse client certificate subject", e);
    }
  }

  public record ApplicationPrincipal(String principalId, String certificateSerialNumber) {
  }
}
