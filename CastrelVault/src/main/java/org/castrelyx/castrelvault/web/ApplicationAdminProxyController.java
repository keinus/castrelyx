package org.castrelyx.castrelvault.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.castrelyx.castrelvault.integration.CastrelSignApplicationAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
public class ApplicationAdminProxyController {
  private final AdminSessionService sessions;
  private final CastrelSignApplicationAdminClient client;

  public ApplicationAdminProxyController(AdminSessionService sessions, CastrelSignApplicationAdminClient client) {
    this.sessions = sessions;
    this.client = client;
  }

  @GetMapping("/status")
  public CastrelSignApplicationAdminClient.Status status(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.status();
  }

  @GetMapping
  public List<Map<String, Object>> applications(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.applications();
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.createApplication(body);
  }

  @PostMapping("/{principalId}/permissions")
  public Map<String, Object> grantPermission(@PathVariable String principalId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.grantPermission(principalId, body);
  }

  @PostMapping("/{principalId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void block(@PathVariable String principalId, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    client.block(principalId);
  }

  @PostMapping("/{principalId}/reactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reactivate(@PathVariable String principalId, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    client.reactivate(principalId);
  }

  @GetMapping("/certificates")
  public List<Map<String, Object>> certificates(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.certificates();
  }

  @GetMapping("/tokens")
  public List<Map<String, Object>> tokens(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.tokens();
  }

  @PostMapping("/tokens")
  public Map<String, Object> createToken(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    return client.createToken(body);
  }

  @PostMapping("/tokens/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeToken(@PathVariable long id, HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    client.revokeToken(id);
  }
}
