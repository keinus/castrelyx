package org.castrelyx.castrelvault.web;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.castrelyx.castrelvault.auth.AdminPrincipal;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminSessionService sessions;

  public AdminController(AdminSessionService sessions) {
    this.sessions = sessions;
  }

  @PostMapping("/login")
  public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest,
      HttpServletResponse response) {
    AdminSessionService.IssuedSession session = sessions.login(request.username(), request.password(), servletRequest);
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AdminSessionService.SESSION_COOKIE, session.token())
        .httpOnly(true)
        .secure(servletRequest.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
        .build()
        .toString());
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AdminSessionService.CSRF_COOKIE, session.csrfToken())
        .httpOnly(false)
        .secure(servletRequest.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
        .build()
        .toString());
    return Map.of(
        "username", session.username(),
        "csrfToken", session.csrfToken(),
        "requiresPasswordChange", session.requirePasswordChange(),
        "expiresAt", session.expiresAt().toString());
  }

  @PostMapping("/change-password")
  public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest request, HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireAdmin(servletRequest);
    sessions.changePassword(principal, request.currentPassword(), request.newPassword(), servletRequest);
    return Map.of("ok", true);
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest servletRequest, HttpServletResponse response) {
    AdminPrincipal principal = sessions.requireAdmin(servletRequest);
    sessions.logout(principal, servletRequest);
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AdminSessionService.SESSION_COOKIE, "")
        .httpOnly(true)
        .secure(servletRequest.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build()
        .toString());
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AdminSessionService.CSRF_COOKIE, "")
        .httpOnly(false)
        .secure(servletRequest.isSecure())
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build()
        .toString());
    return Map.of("ok", true);
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest servletRequest) {
    AdminPrincipal principal = sessions.requireAdmin(servletRequest);
    return Map.of(
        "username", principal.username(),
        "role", principal.role(),
        "requiresPasswordChange", principal.requirePasswordChange());
  }

  public record LoginRequest(String username, String password) {
  }

  public record ChangePasswordRequest(String currentPassword, String newPassword) {
  }
}
