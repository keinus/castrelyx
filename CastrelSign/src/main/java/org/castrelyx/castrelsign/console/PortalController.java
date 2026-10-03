package org.castrelyx.castrelsign.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.castrelyx.castrelsign.kms.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PortalController {
  private final PortalService portal;
  private final KmsAuthentication authentication;
  private final KeyManagementService kms;
  private final JdbcTemplate jdbc;
  public PortalController(PortalService portal, KmsAuthentication authentication, KeyManagementService kms, JdbcTemplate jdbc) { this.portal = portal; this.authentication = authentication; this.kms = kms; this.jdbc = jdbc; }
  private PortalPolicy.User user(HttpServletRequest request) {
    if (request.getAttribute(ConsoleSecurityFilter.USER) instanceof PortalPolicy.User user) return user;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }
  @GetMapping("/api/portal/services") public List<?> services(HttpServletRequest request) { return portal.services(user(request)); }
  @GetMapping("/api/portal/certificates") public List<?> certificates(HttpServletRequest request) { return portal.certificates(user(request)); }
  @GetMapping("/api/portal/requests") public List<?> requests(HttpServletRequest request) { return portal.requests(user(request)); }
  @GetMapping("/api/portal/activity") public List<?> activity(HttpServletRequest request) { return portal.activity(user(request)); }
  public record CsrRequest(String principalId, String csrPem) {}
  @PostMapping("/api/portal/requests") @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> submit(HttpServletRequest request, @RequestBody CsrRequest body) { return portal.submit(user(request), body.principalId(), body.csrPem()); }
  @GetMapping(value="/api/portal/certificate.pem", produces="application/x-pem-file")
  public String certificate(HttpServletRequest request, @RequestParam String principalId, @RequestParam String serial) { return portal.certificate(user(request), principalId, serial); }
  @GetMapping(value="/api/portal/trust.pem", produces="application/x-pem-file")
  public String trust(HttpServletRequest request, @RequestParam(defaultValue="ca") String type) {
    user(request);
    if (type.equals("ca")) return kms.trustBundle();
    if (!type.equals("update")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return kms.keys().stream().filter(k -> k.alias().equals(KeyManagementService.UPDATE) && KeyManagementService.trusted(k.state())).map(KeyManagementService.KeyView::publicKeyPem).reduce("", String::concat);
  }
  @GetMapping("/api/admin/portal/requests")
  public List<?> reviews(HttpServletRequest request) {
    var actor = authentication.authenticate(request);
    if (!actor.roles().contains("OPERATOR") && !actor.roles().contains("AUDITOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return portal.requestsForReview();
  }
  public record Review(boolean approve, String reason) {}
  @PostMapping("/api/admin/portal/requests/{id}/review")
  public Map<String, Object> review(HttpServletRequest request, @PathVariable String id, @RequestBody Review body) {
    var actor = authentication.authenticate(request); actor.require("OPERATOR", null);
    return portal.review(actor.id(), id, body.approve(), body.reason());
  }
  @GetMapping(value="/api/admin/console/certificate.pem", produces="application/x-pem-file")
  public String adminCertificate(HttpServletRequest request, @RequestParam String kind, @RequestParam String serial) {
    var actor = authentication.authenticate(request);
    if (!actor.roles().contains("OPERATOR") && !actor.roles().contains("AUDITOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    String table = switch (kind) { case "application" -> "application_certificates"; case "agent" -> "issued_certificates"; default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST); };
    var rows = jdbc.queryForList("select pem from " + table + " where serial_number=?", serial);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return (String) rows.getFirst().get("pem");
  }
}
