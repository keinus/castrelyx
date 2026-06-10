package org.castrelyx.manager.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.castrelyx.manager.web.AuthController;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RbacInterceptor implements HandlerInterceptor {
  private final LocalAuthProvider authProvider;

  public RbacInterceptor(LocalAuthProvider authProvider) {
    this.authProvider = authProvider;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/") || isPublicPath(path)) {
      return true;
    }
    AuthUser user = authProvider.currentUser(sessionCookie(request));
    request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, user);
    if (user.role() == Role.ADMIN) {
      return true;
    }
    if (requiresAdmin(path, request.getMethod())) {
      throw new ForbiddenException("admin role is required");
    }
    if (user.role() == Role.VIEWER && !HttpMethod.GET.matches(request.getMethod())) {
      throw new ForbiddenException("viewer role is read-only");
    }
    return true;
  }

  private static boolean isPublicPath(String path) {
    return path.equals("/api/setup/status")
        || path.equals("/api/setup/admin")
        || path.equals("/api/auth/login")
        || path.equals("/api/auth/logout")
        || path.equals("/api/auth/session");
  }

  private static boolean requiresAdmin(String path, String method) {
    if (HttpMethod.GET.matches(method)) {
      return false;
    }
    return path.startsWith("/api/integrations/")
        || path.startsWith("/api/settings")
        || path.startsWith("/api/users");
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (AuthController.SESSION_COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
