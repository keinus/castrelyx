package org.castrelyx.castrelsign.kms;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(20)
public class AdminSecurityFilter extends OncePerRequestFilter {
  private final KmsAuthentication authentication;
  private final KeyManagementService kms;
  public AdminSecurityFilter(KmsAuthentication authentication, KeyManagementService kms) { this.authentication = authentication; this.kms = kms; }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/")
        && !(kms.settings().production() && request.getRequestURI().endsWith("/vault-access"));
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString(); response.setHeader("X-Request-ID", requestId);
    response.setHeader("Cache-Control", "no-store");
    String actor = "anonymous";
    try {
      var identity = authentication.authenticate(request); actor = identity.id();
      String path = request.getRequestURI();
      if (path.endsWith("/vault-access")) identity.require("VERIFIER", null);
      else if (!path.contains("/api/admin/kms")) {
        boolean read = Set.of("GET", "HEAD").contains(request.getMethod());
        if (read && !(identity.roles().contains("AUDITOR") || identity.roles().contains("OPERATOR")))
          throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "administrator permission denied");
        if (!read) identity.require("OPERATOR", null);
      }
      kms.record(actor, request.getMethod() + " " + path.substring(0, Math.min(path.length(), 250)), "ACCEPTED", requestId);
      chain.doFilter(request, response);
      kms.record(actor, "ADMIN_RESPONSE", Integer.toString(response.getStatus()), requestId);
    } catch (ResponseStatusException error) {
      if (!response.isCommitted()) {
        response.resetBuffer(); response.setStatus(error.getStatusCode().value()); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"administrator request denied or KMS unavailable\"}");
      }
      if (error.getStatusCode().value() != 503) kms.record(actor, "ADMIN_DENIED", Integer.toString(error.getStatusCode().value()), requestId);
    }
  }
}
