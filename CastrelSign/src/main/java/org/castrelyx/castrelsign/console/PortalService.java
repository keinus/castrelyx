package org.castrelyx.castrelsign.console;

import java.time.*;
import java.util.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.*;
import org.castrelyx.castrelsign.kms.KeyManagementService;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalService {
  private final JdbcTemplate jdbc;
  private final ApplicationPrincipalRepository applications;
  private final PortalPolicy policy;
  private final CsrService csr;
  private final CertificateAuthority authority;
  private final CastrelSignProperties properties;
  private final KeyManagementService kms;
  public PortalService(JdbcTemplate jdbc, ApplicationPrincipalRepository applications, PortalPolicy policy, CsrService csr,
      CertificateAuthority authority, CastrelSignProperties properties, KeyManagementService kms) {
    this.jdbc = jdbc; this.applications = applications; this.policy = policy; this.csr = csr; this.authority = authority; this.properties = properties; this.kms = kms;
    jdbc.execute("""
        create table if not exists portal_certificate_requests (
          id text primary key, requested_by text not null, principal_id text not null, csr_pem text not null,
          state text not null, created_at text not null, reviewed_by text, reviewed_at text, reason text,
          certificate_serial text)
        """);
    jdbc.execute("create index if not exists portal_request_owner on portal_certificate_requests(requested_by, principal_id, created_at)");
    jdbc.execute("""
        create table if not exists portal_activity (
          id integer primary key autoincrement, user_id text not null, principal_id text not null,
          operation text not null, request_id text, created_at text not null)
        """);
  }
  public List<?> services(PortalPolicy.User user) {
    return applications.listPrincipals().stream().filter(p -> user.services().contains(p.principalId())).toList();
  }
  public List<Map<String, Object>> certificates(PortalPolicy.User user) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var certificate : applications.listCertificates()) {
      if (!user.services().contains(certificate.principalId())) continue;
      var row = new LinkedHashMap<String, Object>();
      row.put("principalId", certificate.principalId()); row.put("serialNumber", certificate.serialNumber()); row.put("subjectDn", certificate.subjectDn());
      row.put("notBefore", certificate.notBefore()); row.put("notAfter", certificate.notAfter()); row.put("issuedAt", certificate.issuedAt());
      String state = certificate.status();
      if ("ACTIVE".equals(state)) {
        if (Instant.parse(certificate.notAfter()).isBefore(Instant.now())) state = "EXPIRED";
        else if (!"ACTIVE".equals(applications.principal(certificate.principalId()).orElseThrow().status())
            || applications.activeCertificate(certificate.principalId(), certificate.serialNumber()).filter(authority::trustsClientCertificate).isEmpty()) state = "INACTIVE";
      }
      row.put("status", state); result.add(row);
    }
    return result;
  }
  public List<Map<String, Object>> requests(PortalPolicy.User user) {
    return jdbc.queryForList("select id, requested_by, principal_id, state, created_at, reviewed_by, reviewed_at, reason, certificate_serial from portal_certificate_requests where requested_by=? order by created_at desc limit 200", user.id())
        .stream().filter(r -> user.services().contains(r.get("principal_id"))).toList();
  }
  public List<Map<String, Object>> requestsForReview() {
    return jdbc.queryForList("select id, requested_by, principal_id, state, created_at, reviewed_by, reviewed_at, reason, certificate_serial from portal_certificate_requests order by created_at desc limit 200");
  }
  @Transactional
  public synchronized Map<String, Object> submit(PortalPolicy.User user, String principal, String pem) {
    if (principal == null || !user.services().contains(principal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "service access denied");
    active(principal); validate(pem, principal);
    long pending = jdbc.queryForObject("select count(*) from portal_certificate_requests where principal_id=? and state='PENDING'", Long.class, principal);
    if (pending > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "service already has a pending request");
    long recent = jdbc.queryForObject("select count(*) from portal_certificate_requests where requested_by=? and created_at>?", Long.class, user.id(), Instant.now().minusSeconds(86400).toString());
    if (recent >= 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "daily request quota exceeded");
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into portal_certificate_requests(id,requested_by,principal_id,csr_pem,state,created_at) values(?,?,?,?,'PENDING',?)", id, user.id(), principal, pem, Instant.now().toString());
    activity(user.id(), principal, "CSR_REQUESTED", id);
    kms.record(user.id(), "PORTAL_CSR_REQUEST", "ACCEPTED", id);
    return Map.of("id", id, "state", "PENDING");
  }
  @Transactional
  public synchronized Map<String, Object> review(String actor, String id, boolean approve, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("review reason is required (maximum 1000 characters)");
    var found = jdbc.queryForList("select * from portal_certificate_requests where id=?", id);
    if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var request = found.getFirst();
    if (!"PENDING".equals(request.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "request already reviewed");
    String owner = (String) request.get("requested_by"), principal = (String) request.get("principal_id");
    if (approve) { policy.requireOwner(owner, principal); active(principal); }
    if (jdbc.update("update portal_certificate_requests set state='PROCESSING' where id=? and state='PENDING'", id) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    String serial = null;
    if (approve) {
      var parsed = validate((String) request.get("csr_pem"), principal);
      var certificate = authority.signAgentCertificate(parsed, Duration.ofDays(properties.getCertValidDays()));
      applications.saveCertificate(principal, certificate);
      serial = certificate.getSerialNumber().toString(16);
    }
    String state = approve ? "APPROVED" : "REJECTED";
    jdbc.update("update portal_certificate_requests set state=?, reviewed_by=?, reviewed_at=?, reason=?, certificate_serial=?, csr_pem='' where id=?", state, actor, Instant.now().toString(), reason.trim(), serial, id);
    activity(owner, principal, "CSR_" + state, id); kms.record(actor, "PORTAL_CSR_" + state, "SUCCESS", id);
    return Map.of("id", id, "state", state);
  }
  public String certificate(PortalPolicy.User user, String principal, String serial) {
    if (!user.services().contains(principal)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var rows = jdbc.queryForList("select pem from application_certificates where principal_id=? and serial_number=?", principal, serial);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    activity(user.id(), principal, "CERTIFICATE_DOWNLOADED", serial);
    return (String) rows.getFirst().get("pem");
  }
  public List<Map<String, Object>> activity(PortalPolicy.User user) {
    return jdbc.queryForList("select id, principal_id, operation, request_id, created_at from portal_activity where user_id=? order by id desc limit 200", user.id())
        .stream().filter(r -> user.services().contains(r.get("principal_id"))).toList();
  }
  private void active(String principal) {
    var app = applications.principal(principal).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found"));
    if (!"ACTIVE".equals(app.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "service is blocked");
  }
  private CsrService.ParsedCsr validate(String pem, String principal) {
    if (pem == null || pem.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536 || pem.contains("PRIVATE KEY")) throw new IllegalArgumentException("CSR only; maximum 64 KiB");
    return csr.parseAndValidate(pem, principal);
  }
  private void activity(String user, String principal, String operation, String id) {
    jdbc.update("insert into portal_activity(user_id,principal_id,operation,request_id,created_at) values(?,?,?,?,?)", user, principal, operation, id, Instant.now().toString());
  }
}
