package org.castrelyx.castrelsign.console;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.kms.KmsAuthentication;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(10)
public class ConsoleSecurityFilter extends OncePerRequestFilter {
  public static final String SESSION = "castrelsign.console", USER = "castrelsign.portal.user";
  public record Session(String realm, String digest, String csrf, Instant created) implements java.io.Serializable {}
  private final KmsAuthentication authentication;
  private final PortalPolicy policy;
  private final CastrelSignProperties properties;
  public ConsoleSecurityFilter(KmsAuthentication authentication, PortalPolicy policy, CastrelSignProperties properties) {
    this.authentication = authentication; this.policy = policy; this.properties = properties;
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
    boolean console = path.startsWith("/api/console/"), portal = path.startsWith("/api/portal/");
    boolean admin = path.startsWith("/api/admin/");
    if (!console && !portal && !admin) { chain.doFilter(request, response); return; }
    response.setHeader("Cache-Control", "no-store");
    try {
      boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
      // Existing bearer API clients retain their original authentication/MFA path.
      if (admin && request.getHeader("Authorization") != null) { chain.doFilter(request, response); return; }
      if (properties.getKms().production() && !request.isSecure()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HTTPS required");
      if (mutation) requireSameOrigin(request);
      if (path.equals("/api/console/login")) { chain.doFilter(request, response); return; }
      HttpSession http = request.getSession(false);
      if (http == null || !(http.getAttribute(SESSION) instanceof Session session)
          || session.created().plusSeconds(28800).isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      if (mutation && !equal(session.csrf(), request.getHeader("X-CSRF-Token"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CSRF verification failed");
      if (session.realm().equals("admin")) {
        if (portal) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var identity = authentication.authenticateDigest(session.digest(), request.getHeader("X-KMS-TOTP"), mutation && !path.equals("/api/console/logout"));
        request.setAttribute(KmsAuthentication.IDENTITY, identity);
      } else {
        if (admin || console && !Set.of("/api/console/session", "/api/console/logout").contains(path)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        request.setAttribute(USER, policy.authenticate(session.digest()));
      }
      chain.doFilter(request, response);
    } catch (ResponseStatusException e) {
      if (!response.isCommitted()) {
        response.setStatus(e.getStatusCode().value()); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"session, permission or MFA validation failed\"}");
      }
    }
  }
  static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
  static void requireSameOrigin(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (origin == null) return;
    try {
      URI value = URI.create(origin), target = URI.create(request.getRequestURL().toString());
      if (!ObjectsEqual(value.getScheme(), target.getScheme()) || !ObjectsEqual(value.getAuthority(), target.getAuthority())) throw new IllegalArgumentException();
    } catch (Exception e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-origin request denied"); }
  }
  private static boolean ObjectsEqual(String a, String b) { return a != null && a.equalsIgnoreCase(b); }
}
