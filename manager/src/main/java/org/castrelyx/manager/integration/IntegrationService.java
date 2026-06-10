package org.castrelyx.manager.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.castrelyx.manager.secret.SecretCrypto;
import org.castrelyx.manager.secret.SecretMasker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {
  private final JdbcTemplate jdbcTemplate;
  private final SecretCrypto secretCrypto;

  public IntegrationService(JdbcTemplate jdbcTemplate, SecretCrypto secretCrypto) {
    this.jdbcTemplate = jdbcTemplate;
    this.secretCrypto = secretCrypto;
  }

  public IntegrationConfig get(String serviceName) {
    var rows = jdbcTemplate.query("""
        select service_name, base_url, encrypted_secret, enabled
        from integration_configs
        where service_name = ?
        """, IntegrationService::config, serviceName);
    if (rows.isEmpty()) {
      return new IntegrationConfig(serviceName, "", SecretMasker.mask(null), false);
    }
    return rows.getFirst();
  }

  public String decryptedSecret(String serviceName) {
    var rows = jdbcTemplate.query("select encrypted_secret from integration_configs where service_name = ?",
        (rs, rowNum) -> rs.getString("encrypted_secret"), serviceName);
    return rows.isEmpty() ? null : secretCrypto.decrypt(rows.getFirst());
  }

  public IntegrationConfig upsert(String serviceName, IntegrationUpdateRequest request) {
    IntegrationConfig current = get(serviceName);
    String encrypted = request.secret() == null || request.secret().isBlank()
        ? encryptedSecretOrNull(serviceName)
        : secretCrypto.encrypt(request.secret());
    String baseUrl = request.baseUrl() == null ? current.baseUrl() : request.baseUrl();
    Integer count = jdbcTemplate.queryForObject("select count(*) from integration_configs where service_name = ?",
        Integer.class, serviceName);
    if (count == null || count == 0) {
      jdbcTemplate.update("""
          insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?)
          """, serviceName, baseUrl, encrypted, request.enabled(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    } else {
      jdbcTemplate.update("""
          update integration_configs
          set base_url = ?, encrypted_secret = ?, enabled = ?, updated_at = ?
          where service_name = ?
          """, baseUrl, encrypted, request.enabled(), Timestamp.from(Instant.now()), serviceName);
    }
    return get(serviceName);
  }

  private String encryptedSecretOrNull(String serviceName) {
    var rows = jdbcTemplate.query("select encrypted_secret from integration_configs where service_name = ?",
        (rs, rowNum) -> rs.getString("encrypted_secret"), serviceName);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static IntegrationConfig config(ResultSet rs, int rowNum) throws SQLException {
    return new IntegrationConfig(
        rs.getString("service_name"),
        rs.getString("base_url"),
        SecretMasker.mask(rs.getString("encrypted_secret")),
        rs.getBoolean("enabled"));
  }
}
