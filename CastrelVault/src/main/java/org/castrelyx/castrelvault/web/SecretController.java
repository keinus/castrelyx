package org.castrelyx.castrelvault.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.auth.AdminPrincipal;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.castrelyx.castrelvault.secret.SecretService;
import org.castrelyx.castrelvault.secret.SecretService.CreateSecretRequest;
import org.castrelyx.castrelvault.secret.SecretService.RotateSecretRequest;
import org.castrelyx.castrelvault.secret.SecretService.SecretResponse;
import org.castrelyx.castrelvault.secret.SecretService.SecretVersionResponse;
import org.castrelyx.castrelvault.secret.SecretService.UpdateSecretRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/secrets")
public class SecretController {
  private final AdminSessionService sessions;
  private final SecretService secrets;
  private final AuditService auditService;

  public SecretController(AdminSessionService sessions, SecretService secrets, AuditService auditService) {
    this.sessions = sessions;
    this.secrets = secrets;
    this.auditService = auditService;
  }

  @GetMapping
  public List<SecretResponse> list(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return secrets.list();
  }

  @PostMapping
  public SecretResponse create(@RequestBody CreateSecretRequest request, HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    return secrets.create(request, principal);
  }

  @GetMapping("/{id}")
  public SecretResponse get(@PathVariable String id, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return secrets.get(id);
  }

  @GetMapping("/{id}/versions")
  public List<SecretVersionResponse> versions(@PathVariable String id, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return secrets.versions(id);
  }

  @PutMapping("/{id}")
  public SecretResponse update(@PathVariable String id, @RequestBody UpdateSecretRequest request,
      HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    return secrets.updateMetadata(id, request, principal);
  }

  @PostMapping("/{id}/rotate")
  public SecretResponse rotate(@PathVariable String id, @RequestBody RotateSecretRequest request,
      HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    return secrets.rotate(id, request, principal);
  }

  @PostMapping("/{id}/disable")
  public SecretResponse disable(@PathVariable String id, HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    return secrets.setEnabled(id, false, principal);
  }

  @PostMapping("/{id}/enable")
  public SecretResponse enable(@PathVariable String id, HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    return secrets.setEnabled(id, true, principal);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    secrets.delete(id, principal);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/reveal")
  public ResponseEntity<Map<String, Object>> reveal(@PathVariable String id, @RequestBody RevealRequest request,
      HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireReadyAdmin(servletRequest);
    String path = safePath(id);
    Integer version = safeVersion(id);
    try {
      if (request.reason() == null || request.reason().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reveal reason is required");
      }
      sessions.reauthenticate(principal, request.currentPassword(), servletRequest);
      var revealed = secrets.reveal(id);
      auditService.record("ADMIN", principal.username(), revealed.path(), revealed.version(),
          "REVEAL_SECRET", "ALLOWED", request.reason(), servletRequest);
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(revealed.asResponse());
    } catch (ResponseStatusException e) {
      auditService.record("ADMIN", principal.username(), path, version,
          "REVEAL_SECRET", "DENIED", e.getReason(), servletRequest);
      throw e;
    }
  }

  private String safePath(String id) {
    try {
      return secrets.get(id).path();
    } catch (Exception e) {
      return null;
    }
  }

  private Integer safeVersion(String id) {
    try {
      return secrets.get(id).currentVersion();
    } catch (Exception e) {
      return null;
    }
  }

  public record RevealRequest(String currentPassword, String reason) {
  }
}
