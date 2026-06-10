package org.castrelyx.manager.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.castrelyx.manager.auth.AuthException;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  public static final String SESSION_COOKIE = "CASTRELYX_MANAGER_SESSION";

  private final LocalAuthProvider authProvider;

  public AuthController(LocalAuthProvider authProvider) {
    this.authProvider = authProvider;
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    AuthUser user = authProvider.authenticate(request.username(), request.password());
    Cookie cookie = new Cookie(SESSION_COOKIE, authProvider.createSession(user));
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(12 * 60 * 60);
    response.addCookie(cookie);
    return Map.of("authenticated", true, "user", user);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    authProvider.revokeSession(sessionCookie(request));
    Cookie cookie = new Cookie(SESSION_COOKIE, "");
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(0);
    response.addCookie(cookie);
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    try {
      return Map.of("authenticated", true, "user", authProvider.currentUser(sessionCookie(request)));
    } catch (AuthException exception) {
      return Map.of("authenticated", false);
    }
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (SESSION_COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {
  }
}
