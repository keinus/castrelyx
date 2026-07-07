package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.CurrentUser;
import org.castrelyx.manager.remote.RemoteAccessService;
import org.castrelyx.manager.remote.RemoteAccessService.RemoteAccessRequest;
import org.castrelyx.manager.remote.RemoteAccessSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/remote-access")
public class RemoteAccessController {
  private final RemoteAccessService remoteAccessService;

  public RemoteAccessController(RemoteAccessService remoteAccessService) {
    this.remoteAccessService = remoteAccessService;
  }

  @PostMapping("/ssh-sessions")
  public RemoteAccessSession create(HttpServletRequest servletRequest, @RequestBody(required = false) RemoteAccessRequest request) {
    return remoteAccessService.createSession(currentUser(servletRequest), request);
  }

  @GetMapping("/ssh-sessions/{sessionId}")
  public RemoteAccessSession get(HttpServletRequest servletRequest, @PathVariable String sessionId) {
    return remoteAccessService.requireSessionAccess(sessionId, currentUser(servletRequest));
  }

  @DeleteMapping("/ssh-sessions/{sessionId}")
  public ResponseEntity<Void> close(HttpServletRequest servletRequest, @PathVariable String sessionId) {
    remoteAccessService.requireSessionAccess(sessionId, currentUser(servletRequest));
    remoteAccessService.closeSession(sessionId, "closed by user");
    return ResponseEntity.noContent().build();
  }

  private static AuthUser currentUser(HttpServletRequest request) {
    Object user = request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
    return user instanceof AuthUser authUser ? authUser : null;
  }
}
