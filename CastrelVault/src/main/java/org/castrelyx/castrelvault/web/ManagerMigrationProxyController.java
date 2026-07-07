package org.castrelyx.castrelvault.web;

import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.castrelyx.castrelvault.integration.ManagerMigrationAdminClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager-migration")
public class ManagerMigrationProxyController {
  private final AdminSessionService sessions;
  private final ManagerMigrationAdminClient client;

  public ManagerMigrationProxyController(AdminSessionService sessions, ManagerMigrationAdminClient client) {
    this.sessions = sessions;
    this.client = client;
  }

  @GetMapping("/status")
  public Map<String, Object> status(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.status();
  }

  @PostMapping("/dry-run")
  public Map<String, Object> dryRun(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.dryRun();
  }

  @PostMapping("/run")
  public Map<String, Object> run(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.run(sessionToken(request));
  }

  private static String sessionToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return authorization.substring("Bearer ".length());
    }
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return "";
    }
    for (Cookie cookie : cookies) {
      if (AdminSessionService.SESSION_COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return "";
  }
}
