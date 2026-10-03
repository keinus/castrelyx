package org.castrelyx.castrelsign.kms;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/kms")
public class KmsController {
  private final KeyManagementService kms;
  private final KmsAuthentication authentication;
  private final CastrelSignProperties properties;
  private final JdbcTemplate jdbc;
  public KmsController(KeyManagementService kms, KmsAuthentication authentication, CastrelSignProperties properties, JdbcTemplate jdbc) {
    this.kms = kms; this.authentication = authentication; this.properties = properties; this.jdbc = jdbc;
  }
  private KmsAuthentication.Identity require(HttpServletRequest request, String role, String alias) {
    var identity = authentication.authenticate(request); identity.require(role, alias); return identity;
  }
  private KeyManagementService.Ref ref(String id, int version) { return new KeyManagementService.Ref(id, version); }
  @GetMapping("/keys")
  public List<KeyManagementService.KeyView> keys(HttpServletRequest request) {
    var identity = require(request, "AUDITOR", null);
    return kms.keys().stream().filter(k -> identity.keys().contains(k.alias())).toList();
  }
  @GetMapping("/keys/{id}/versions/{version}")
  public Map<String, Object> key(HttpServletRequest request, @PathVariable String id, @PathVariable int version) {
    var key = kms.get(ref(id, version)); require(request, "AUDITOR", key.alias());
    return Map.of("key", key, "grants", kms.grants(key.ref()), "dependencies", kms.dependencies(key.ref()), "current", kms.current(key.alias()).ref().equals(key.ref()));
  }
  @GetMapping(value = "/trust-bundle.pem", produces = "application/x-pem-file")
  public String trust(HttpServletRequest request) { require(request, "AUDITOR", KeyManagementService.CA); return kms.trustBundle(); }
  @GetMapping("/update-public-keys")
  public List<KeyManagementService.KeyView> updateKeys(HttpServletRequest request) {
    require(request, "AUDITOR", KeyManagementService.UPDATE);
    return kms.keys().stream().filter(k -> KeyManagementService.UPDATE.equals(k.alias())).toList();
  }
  @GetMapping(value = "/update-trust-bundle.pem", produces = "application/x-pem-file")
  public String updateTrust(HttpServletRequest request) {
    require(request, "AUDITOR", KeyManagementService.UPDATE);
    return kms.keys().stream().filter(k -> KeyManagementService.UPDATE.equals(k.alias()) && KeyManagementService.trusted(k.state()))
        .map(KeyManagementService.KeyView::publicKeyPem).reduce("", String::concat);
  }
  public record Rotation(String requestId) {}
  @PostMapping("/keys/{alias}/rotate")
  public KeyManagementService.KeyView rotate(HttpServletRequest request, @PathVariable String alias, @RequestBody Rotation body) throws Exception {
    var actor = require(request, "KEY_ADMIN", alias);
    var key = kms.prepareRotation(actor.id(), alias, body.requestId(), Duration.ofDays(properties.getRootValidDays()));
    refreshTrust(); return key;
  }
  public record StateChange(String reason, boolean compromised) {}
  @PostMapping("/keys/{id}/versions/{version}/suspend")
  public KeyManagementService.KeyView suspend(HttpServletRequest request, @PathVariable String id, @PathVariable int version, @RequestBody StateChange body) throws Exception {
    var key = kms.get(ref(id, version)); var actor = require(request, "KEY_ADMIN", key.alias());
    var result = kms.suspend(actor.id(), key.ref(), body.compromised(), body.reason());
    // Enforcement is immediate in the signing/authentication path even if TLS refresh fails.
    ProtectedFiles.write(properties.getDataDir().resolve("certs/ca.pem"), kms.trustBundle().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return result;
  }
  public record ApprovalRequest(String action, String reason, Map<String, String> payload) {}
  @PostMapping("/keys/{id}/versions/{version}/requests")
  public Map<String, String> propose(HttpServletRequest request, @PathVariable String id, @PathVariable int version, @RequestBody ApprovalRequest body) {
    var key = kms.get(ref(id, version)); var actor = require(request, "KEY_ADMIN", key.alias());
    return Map.of("requestId", kms.request(actor.id(), key.ref(), body.action(), body.reason(), body.payload()));
  }
  @GetMapping("/requests")
  public List<Map<String, Object>> approvals(HttpServletRequest request) {
    var identity = require(request, "AUDITOR", null);
    return kms.requests().stream().filter(r -> identity.keys().contains(kms.get(ref((String) r.get("key_id"), ((Number) r.get("version")).intValue())).alias())).toList();
  }
  @PostMapping("/requests/{id}/approve")
  public KeyManagementService.KeyView approve(HttpServletRequest request, @PathVariable String id) throws Exception {
    var approval = kms.approval(id);
    var key = kms.get(ref((String) approval.get("key_id"), ((Number) approval.get("version")).intValue()));
    var actor = require(request, "SECURITY_OFFICER", key.alias());
    authentication.requireCurrentRequester((String) approval.get("requested_by"), key.alias());
    var result = kms.approve(actor.id(), id);
    if (KeyManagementService.CA.equals(key.alias())) refreshTrust();
    return result;
  }
  @PostMapping("/keys/{id}/versions/{version}/cancel-deletion")
  public KeyManagementService.KeyView cancelDeletion(HttpServletRequest request, @PathVariable String id, @PathVariable int version) {
    var key = kms.get(ref(id, version)); var actor = require(request, "KEY_ADMIN", key.alias());
    return kms.cancelDeletion(actor.id(), key.ref());
  }
  @GetMapping("/audit")
  public List<Map<String, Object>> audit(HttpServletRequest request, @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "100") int limit) { require(request, "AUDITOR", null); return kms.auditEvents(after, limit); }
  @GetMapping("/health")
  public Map<String, Object> health(HttpServletRequest request) { require(request, "AUDITOR", null); return kms.health(); }
  @GetMapping("/tls")
  public List<Map<String, Object>> tls(HttpServletRequest request) { require(request, "AUDITOR", KeyManagementService.CA); return kms.tlsInventory(); }
  @GetMapping("/certificates/status")
  public Map<String, Object> status(HttpServletRequest request, @RequestParam String serial) {
    require(request, "VERIFIER", null);
    if (!serial.matches("[0-9a-f]{1,64}")) throw new IllegalArgumentException("hexadecimal certificate serial required");
    var rows = jdbc.queryForList("""
        select c.status,c.not_after,c.pem,a.status as principal_status from issued_certificates c join agents a on c.agent_id=a.agent_id where c.serial_number=?
        union all
        select c.status,c.not_after,c.pem,a.status as principal_status from application_certificates c join application_principals a on c.principal_id=a.principal_id where c.serial_number=?
        """, serial, serial);
    boolean active = false;
    if (rows.size() == 1) {
      var row = rows.getFirst();
      try { active = "ACTIVE".equals(row.get("status")) && "ACTIVE".equals(row.get("principal_status"))
          && Instant.parse((String) row.get("not_after")).isAfter(Instant.now()) && kms.trusts(KeyManagementService.certificate((String) row.get("pem"))); }
      catch (java.security.cert.CertificateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "stored certificate is invalid"); }
    }
    return Map.of("serial", serial, "active", active, "status", rows.isEmpty() ? "UNKNOWN" : active ? "ACTIVE" : "INACTIVE",
        "checkedAt", Instant.now().toString(), "cacheExpiresAt", Instant.now().plusSeconds(60).toString(), "maxCacheSeconds", 60);
  }
  private void refreshTrust() throws Exception {
    CertificateAuthority.refreshManaged(properties.getDataDir().resolve("certs"), properties.getTlsServerName(),
        Duration.ofDays(properties.getCertValidDays()), Duration.ofDays(properties.getRootValidDays()), properties.getKeyStorePassword().toCharArray(), kms);
  }
}
