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
  private final org.castrelyx.manager.hunt.v2.HuntAccess huntAccess;

  public RbacInterceptor(LocalAuthProvider authProvider, org.castrelyx.manager.hunt.v2.HuntAccess huntAccess) {
    this.authProvider = authProvider;
    this.huntAccess = huntAccess;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/") || isPublicPath(path)) {
      return true;
    }
    AuthUser user = authProvider.currentUser(sessionCookie(request));
    request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, user);
    if (path.startsWith("/api/security/hunt/v2/")) {
      if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) huntAccess.verify(request);
      if (HttpMethod.POST.matches(request.getMethod()) && (path.equals("/api/security/hunt/v2/queries")
          || path.matches("/api/security/hunt/v2/queries/[^/]+/cancel")
          || path.matches("/api/security/hunt/v2/investigations/[^/]+/exports")
          || path.matches("/api/security/hunt/v2/scenarios/[^/]+/runs"))) return true;
    }
    if (user.role() == Role.ADMIN) {
      return true;
    }
    if (path.matches("/api/assets/[^/]+/router-control/queries") && HttpMethod.POST.matches(request.getMethod())) {
      // This endpoint only admits an explicit read-operation allowlist.
      return true;
    }
    if (isAssetFilePath(path) && user.role() == Role.VIEWER) {
      throw new ForbiddenException("viewer role cannot access asset files");
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
        || path.equals("/api/auth/session")
        || path.startsWith("/api/vault-migration/");
  }

  private static boolean requiresAdmin(String path, String method) {
    if (HttpMethod.GET.matches(method)) {
      return false;
    }
    return path.startsWith("/api/integrations/")
        || path.startsWith("/api/settings")
        || path.startsWith("/api/users");
  }

  private static boolean isAssetFilePath(String path) {
    return path.startsWith("/api/assets/") && path.contains("/files");
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
