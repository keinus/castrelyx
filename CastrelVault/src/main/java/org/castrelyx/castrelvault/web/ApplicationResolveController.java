package org.castrelyx.castrelvault.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.application.ApplicationAccessService;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.secret.SecretService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/secrets")
public class ApplicationResolveController {
  private static final String PERMISSION = "vault:resolve";

  private final ApplicationAccessService accessService;
  private final SecretService secrets;
  private final AuditService auditService;

  public ApplicationResolveController(ApplicationAccessService accessService, SecretService secrets, AuditService auditService) {
    this.accessService = accessService;
    this.secrets = secrets;
    this.auditService = auditService;
  }

  @PostMapping("/resolve")
  public ResponseEntity<Map<String, Object>> resolve(@RequestBody ResolveRequest request, HttpServletRequest servletRequest) {
    var principal = accessService.requireVaultAccess(servletRequest, PERMISSION);
    List<String> paths = paths(request);
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (String path : paths) {
      try {
        var secret = secrets.resolveByPath(path);
        resolved.put(secret.path(), secret.payload());
        auditService.record("APPLICATION", principal.principalId(), secret.path(), secret.version(),
            "RESOLVE_SECRET", "ALLOWED", null, servletRequest);
      } catch (ResponseStatusException e) {
        auditService.record("APPLICATION", principal.principalId(), path, null,
            "RESOLVE_SECRET", "DENIED", e.getReason(), servletRequest);
        throw e;
      }
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("secrets", resolved));
  }

  private static List<String> paths(ResolveRequest request) {
    List<String> values = new ArrayList<>();
    if (request != null) {
      if (request.path() != null && !request.path().isBlank()) {
        values.add(normalizeReference(request.path()));
      }
      if (request.reference() != null && !request.reference().isBlank()) {
        values.add(normalizeReference(request.reference()));
      }
      if (request.paths() != null) {
        request.paths().stream().filter(value -> value != null && !value.isBlank())
            .map(ApplicationResolveController::normalizeReference).forEach(values::add);
      }
      if (request.references() != null) {
        request.references().stream().filter(value -> value != null && !value.isBlank())
            .map(ApplicationResolveController::normalizeReference).forEach(values::add);
      }
    }
    if (values.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one secret path or reference is required");
    }
    return values;
  }

  private static String normalizeReference(String value) {
    String normalized = value.trim();
    if (normalized.startsWith("vault://")) {
      normalized = normalized.substring("vault://".length());
      if (!normalized.startsWith("/")) {
        normalized = "/" + normalized;
      }
    } else if (normalized.startsWith("vault:")) {
      normalized = normalized.substring("vault:".length());
    }
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    return normalized;
  }

  public record ResolveRequest(String path, String reference, List<String> paths, List<String> references) {
  }
}
