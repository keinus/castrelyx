package org.castrelyx.manager.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.castrelyx.manager.secret.SecretCrypto;
import org.castrelyx.manager.secret.SecretMasker;
import org.castrelyx.manager.vault.VaultClient;
import org.castrelyx.manager.vault.VaultSecretWriteRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IntegrationService {
  private final JdbcTemplate jdbcTemplate;
  private final SecretCrypto secretCrypto;
  private final VaultClient vaultClient;

  public IntegrationService(JdbcTemplate jdbcTemplate, SecretCrypto secretCrypto, VaultClient vaultClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.secretCrypto = secretCrypto;
    this.vaultClient = vaultClient;
  }

  public IntegrationConfig get(String serviceName) {
    var rows = jdbcTemplate.query("""
        select service_name, base_url, encrypted_secret, vault_ref, enabled
        from integration_configs
        where service_name = ?
        """, IntegrationService::config, serviceName);
    if (rows.isEmpty()) {
      return new IntegrationConfig(serviceName, "", SecretMasker.mask(null), null, false);
    }
    return rows.getFirst();
  }

  public String decryptedSecret(String serviceName) {
    var rows = jdbcTemplate.query("select encrypted_secret, vault_ref from integration_configs where service_name = ?",
        (rs, rowNum) -> new SecretColumns(rs.getString("encrypted_secret"), rs.getString("vault_ref")), serviceName);
    if (rows.isEmpty()) {
      return null;
    }
    SecretColumns columns = rows.getFirst();
    if (columns.vaultRef() != null && !columns.vaultRef().isBlank()) {
      return vaultClient.resolveString(columns.vaultRef());
    }
    return secretCrypto.decrypt(columns.encryptedSecret());
  }

  public IntegrationConfig upsert(String serviceName, IntegrationUpdateRequest request) {
    IntegrationConfig current = get(serviceName);
    SecretColumns existing = secretColumnsOrNull(serviceName);
    String encrypted = existing == null ? null : existing.encryptedSecret();
    String vaultRef = existing == null ? null : existing.vaultRef();
    if (request.secret() != null && !request.secret().isBlank()) {
      if (vaultClient.isEnabled()) {
        String path = "/manager/integrations/" + serviceName + "/secret";
        vaultRef = vaultClient.createSecret(new VaultSecretWriteRequest(
            path,
            "Manager " + serviceName + " integration secret",
            "API_TOKEN",
            java.util.Map.of("value", request.secret())));
        encrypted = null;
      } else {
        encrypted = secretCrypto.encrypt(request.secret());
        vaultRef = null;
      }
    }
    String baseUrl = request.baseUrl() == null ? current.baseUrl() : request.baseUrl();
    Integer count = jdbcTemplate.queryForObject("select count(*) from integration_configs where service_name = ?",
        Integer.class, serviceName);
    if (count == null || count == 0) {
      jdbcTemplate.update("""
          insert into integration_configs(service_name, base_url, encrypted_secret, vault_ref, enabled, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?)
          """, serviceName, baseUrl, encrypted, vaultRef, request.enabled(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    } else {
      jdbcTemplate.update("""
          update integration_configs
          set base_url = ?, encrypted_secret = ?, vault_ref = ?, enabled = ?, updated_at = ?
          where service_name = ?
          """, baseUrl, encrypted, vaultRef, request.enabled(), Timestamp.from(Instant.now()), serviceName);
    }
    return get(serviceName);
  }

  private SecretColumns secretColumnsOrNull(String serviceName) {
    var rows = jdbcTemplate.query("select encrypted_secret, vault_ref from integration_configs where service_name = ?",
        (rs, rowNum) -> new SecretColumns(rs.getString("encrypted_secret"), rs.getString("vault_ref")), serviceName);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static IntegrationConfig config(ResultSet rs, int rowNum) throws SQLException {
    String encryptedSecret = rs.getString("encrypted_secret");
    String vaultRef = rs.getString("vault_ref");
    return new IntegrationConfig(
        rs.getString("service_name"),
        rs.getString("base_url"),
        SecretMasker.maskConfigured((encryptedSecret != null && !encryptedSecret.isBlank()) || (vaultRef != null && !vaultRef.isBlank())),
        vaultRef,
        rs.getBoolean("enabled"));
  }

  private record SecretColumns(String encryptedSecret, String vaultRef) {
  }
}
