package org.castrelyx.castrelsign.persistence;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.castrelsign.crypto.PemUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationPrincipalRepository {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ApplicationPrincipalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public ApplicationPrincipalRecord upsertPrincipal(String principalId, String displayName) {
    String normalized = requirePrincipalId(principalId);
    String now = Instant.now().toString();
    int updated = jdbcTemplate.update("""
        update application_principals
        set display_name = ?, updated_at = ?
        where principal_id = ?
        """, displayName == null || displayName.isBlank() ? normalized : displayName.trim(), now, normalized);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into application_principals(principal_id, display_name, status, permissions_json, created_at, updated_at)
          values (?, ?, 'ACTIVE', '[]', ?, ?)
          """, normalized, displayName == null || displayName.isBlank() ? normalized : displayName.trim(), now, now);
    }
    return principal(normalized).orElseThrow();
  }

  public ApplicationPrincipalRecord grantPermission(String principalId, String permission) {
    ApplicationPrincipalRecord current = principal(requirePrincipalId(principalId)).orElseThrow();
    if (permission == null || permission.isBlank()) {
      throw new IllegalArgumentException("permission is required");
    }
    List<String> permissions = new java.util.ArrayList<>(current.permissions());
    if (!permissions.contains(permission.trim())) {
      permissions.add(permission.trim());
    }
    jdbcTemplate.update("""
        update application_principals
        set permissions_json = ?, updated_at = ?
        where principal_id = ?
        """, permissionsJson(permissions), Instant.now().toString(), current.principalId());
    return principal(current.principalId()).orElseThrow();
  }

  public void setStatus(String principalId, String status) {
    jdbcTemplate.update("""
        update application_principals
        set status = ?, updated_at = ?
        where principal_id = ?
        """, status, Instant.now().toString(), requirePrincipalId(principalId));
  }

  public Optional<ApplicationPrincipalRecord> principal(String principalId) {
    List<ApplicationPrincipalRecord> rows = jdbcTemplate.query("""
        select principal_id, display_name, status, permissions_json, created_at, updated_at
        from application_principals
        where principal_id = ?
        """, (rs, rowNum) -> new ApplicationPrincipalRecord(
            rs.getString("principal_id"),
            rs.getString("display_name"),
            rs.getString("status"),
            permissions(rs.getString("permissions_json")),
            rs.getString("created_at"),
            rs.getString("updated_at")),
        requirePrincipalId(principalId));
    return rows.stream().findFirst();
  }

  public List<ApplicationPrincipalRecord> listPrincipals() {
    return jdbcTemplate.query("""
        select principal_id, display_name, status, permissions_json, created_at, updated_at
        from application_principals
        order by principal_id
        """, (rs, rowNum) -> new ApplicationPrincipalRecord(
            rs.getString("principal_id"),
            rs.getString("display_name"),
            rs.getString("status"),
            permissions(rs.getString("permissions_json")),
            rs.getString("created_at"),
            rs.getString("updated_at")));
  }

  public List<ApplicationCertificateRecord> listCertificates() {
    return jdbcTemplate.query("""
        select principal_id, serial_number, subject_dn, not_before, not_after, status, issued_at
        from application_certificates
        order by issued_at desc
        """, (rs, rowNum) -> new ApplicationCertificateRecord(
            rs.getString("principal_id"),
            rs.getString("serial_number"),
            rs.getString("subject_dn"),
            rs.getString("not_before"),
            rs.getString("not_after"),
            rs.getString("status"),
            rs.getString("issued_at")));
  }

  public ApplicationEnrollmentTokenRecord createToken(String name, String tokenHash, String principalId, Instant expiresAt) {
    String now = Instant.now().toString();
    jdbcTemplate.update("""
        insert into application_enrollment_tokens(name, token_hash, principal_id, expires_at, created_at)
        values (?, ?, ?, ?, ?)
        """, name, tokenHash, requirePrincipalId(principalId), expiresAt.toString(), now);
    return tokenByHash(tokenHash).orElseThrow();
  }

  public Optional<ApplicationEnrollmentTokenRecord> tokenByHash(String tokenHash) {
    List<ApplicationEnrollmentTokenRecord> rows = jdbcTemplate.query("""
        select id, name, token_hash, principal_id, used_at, expires_at, revoked_at, created_at
        from application_enrollment_tokens
        where token_hash = ?
        """, (rs, rowNum) -> new ApplicationEnrollmentTokenRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("token_hash"),
            rs.getString("principal_id"),
            instantOrNull(rs.getString("used_at")),
            Instant.parse(rs.getString("expires_at")),
            instantOrNull(rs.getString("revoked_at")),
            Instant.parse(rs.getString("created_at"))),
        tokenHash);
    return rows.stream().findFirst();
  }

  public List<ApplicationEnrollmentTokenRecord> listTokens() {
    return jdbcTemplate.query("""
        select id, name, token_hash, principal_id, used_at, expires_at, revoked_at, created_at
        from application_enrollment_tokens
        order by id desc
        """, (rs, rowNum) -> new ApplicationEnrollmentTokenRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("token_hash"),
            rs.getString("principal_id"),
            instantOrNull(rs.getString("used_at")),
            Instant.parse(rs.getString("expires_at")),
            instantOrNull(rs.getString("revoked_at")),
            Instant.parse(rs.getString("created_at"))));
  }

  public boolean consumeToken(long id, Instant now) {
    return jdbcTemplate.update("""
        update application_enrollment_tokens
        set used_at = ?
        where id = ?
          and used_at is null
          and revoked_at is null
          and expires_at > ?
        """, now.toString(), id, now.toString()) == 1;
  }

  public boolean revokeToken(long id) {
    return jdbcTemplate.update("""
        update application_enrollment_tokens
        set revoked_at = ?
        where id = ? and revoked_at is null
        """, Instant.now().toString(), id) == 1;
  }

  public void saveCertificate(String principalId, X509Certificate certificate) {
    String normalized = requirePrincipalId(principalId);
    jdbcTemplate.update("update application_certificates set status = 'SUPERSEDED' where principal_id = ? and status = 'ACTIVE'", normalized);
    jdbcTemplate.update("""
        insert into application_certificates(principal_id, serial_number, subject_dn, not_before, not_after, pem, status, issued_at)
        values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
        """,
        normalized,
        certificate.getSerialNumber().toString(16),
        certificate.getSubjectX500Principal().getName(),
        certificate.getNotBefore().toInstant().toString(),
        certificate.getNotAfter().toInstant().toString(),
        PemUtil.certificateToPem(certificate),
        Instant.now().toString());
  }

  public boolean hasActiveCertificate(String principalId, String serialNumber) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from application_certificates
        where principal_id = ? and serial_number = ? and status = 'ACTIVE' and not_after > ?
        """, Integer.class, requirePrincipalId(principalId), serialNumber, Instant.now().toString());
    return count != null && count > 0;
  }

  private String permissionsJson(List<String> permissions) {
    try {
      return objectMapper.writeValueAsString(permissions);
    } catch (Exception e) {
      throw new IllegalArgumentException("permissions are invalid", e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> permissions(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, List.class);
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String requirePrincipalId(String principalId) {
    if (principalId == null || principalId.isBlank()) {
      throw new IllegalArgumentException("principal_id is required");
    }
    return principalId.trim();
  }

  private static Instant instantOrNull(String value) {
    return value == null || value.isBlank() ? null : Instant.parse(value);
  }

  public record ApplicationPrincipalRecord(
      String principalId,
      String displayName,
      String status,
      List<String> permissions,
      String createdAt,
      String updatedAt) {
  }

  public record ApplicationEnrollmentTokenRecord(
      long id,
      String name,
      String tokenHash,
      String principalId,
      Instant usedAt,
      Instant expiresAt,
      Instant revokedAt,
      Instant createdAt) {
  }

  public record ApplicationCertificateRecord(
      String principalId,
      String serialNumber,
      String subjectDn,
      String notBefore,
      String notAfter,
      String status,
      String issuedAt) {
  }
}
