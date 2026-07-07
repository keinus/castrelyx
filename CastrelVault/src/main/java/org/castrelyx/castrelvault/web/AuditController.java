package org.castrelyx.castrelvault.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.audit.AuditService.AuditPage;
import org.castrelyx.castrelvault.audit.AuditService.AuditSearch;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
  private final AdminSessionService sessions;
  private final AuditService auditService;

  public AuditController(AdminSessionService sessions, AuditService auditService) {
    this.sessions = sessions;
    this.auditService = auditService;
  }

  @GetMapping
  public List<Map<String, Object>> list(@RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return auditService.list(limit);
  }

  @GetMapping("/search")
  public AuditPage search(
      @RequestParam(required = false) String actorType,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) String secretPath,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return auditService.search(new AuditSearch(actorType, actorId, action, result, secretPath, from, to, limit, offset));
  }
}
