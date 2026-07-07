package org.castrelyx.manager.vault;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.manager.secret.SecretCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VaultMigrationService {
  private final JdbcTemplate jdbcTemplate;
  private final SecretCrypto secretCrypto;
  private final VaultClient vaultClient;
  private final ObjectMapper objectMapper;

  public VaultMigrationService(JdbcTemplate jdbcTemplate, SecretCrypto secretCrypto, VaultClient vaultClient, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.secretCrypto = secretCrypto;
    this.vaultClient = vaultClient;
    this.objectMapper = objectMapper;
  }

  public MigrationReport migrateLegacySecrets() {
    return migrateLegacySecrets(null);
  }

  public MigrationReport migrateLegacySecrets(String adminSessionToken) {
    if (!vaultClient.isEnabled()) {
      return new MigrationReport(0, 0, "vault client disabled");
    }
    int integrations = migrateIntegrations(adminSessionToken);
    int snmpCredentials = migrateSnmpCredentials(adminSessionToken);
    return new MigrationReport(integrations, snmpCredentials, "migrated");
  }

  public MigrationStatus status() {
    PendingMigrationPlan plan = dryRunLegacySecrets();
    Integer migratedIntegrations = jdbcTemplate.queryForObject("""
        select count(*)
        from integration_configs
        where vault_ref is not null and vault_ref <> ''
        """, Integer.class);
    Integer migratedSnmp = jdbcTemplate.queryForObject("""
        select count(*)
        from snmp_credentials
        where vault_ref is not null and vault_ref <> ''
        """, Integer.class);
    return new MigrationStatus(
        vaultClient.isEnabled(),
        plan.pendingIntegrationSecrets(),
        plan.pendingSnmpCredentials(),
        migratedIntegrations == null ? 0 : migratedIntegrations,
        migratedSnmp == null ? 0 : migratedSnmp,
        vaultClient.isEnabled() ? "ready" : "vault client disabled");
  }

  public PendingMigrationPlan dryRunLegacySecrets() {
    List<PendingSecret> integrations = pendingIntegrations();
    List<PendingSecret> snmpCredentials = pendingSnmpCredentials();
    return new PendingMigrationPlan(
        vaultClient.isEnabled(),
        integrations.size(),
        snmpCredentials.size(),
        integrations,
        snmpCredentials,
        vaultClient.isEnabled() ? "dry-run" : "vault client disabled");
  }

  private int migrateIntegrations(String adminSessionToken) {
    List<IntegrationLegacySecret> rows = integrationLegacySecrets();
    int migrated = 0;
    for (IntegrationLegacySecret row : rows) {
      String plaintext = secretCrypto.decrypt(row.encryptedSecret());
      String path = "/manager/integrations/" + safeSegment(row.serviceName()) + "/secret";
      String ref = createSecret(new VaultSecretWriteRequest(
          path,
          "Manager " + row.serviceName() + " integration secret",
          "API_TOKEN",
          Map.of("value", plaintext)), adminSessionToken);
      jdbcTemplate.update("""
          update integration_configs
          set vault_ref = ?, encrypted_secret = null, updated_at = current_timestamp
          where service_name = ?
          """, ref, row.serviceName());
      migrated++;
    }
    return migrated;
  }

  private List<IntegrationLegacySecret> integrationLegacySecrets() {
    return jdbcTemplate.query("""
        select service_name, encrypted_secret
        from integration_configs
        where encrypted_secret is not null
          and encrypted_secret <> ''
          and (vault_ref is null or vault_ref = '')
        """, VaultMigrationService::integrationLegacySecret);
  }

  private int migrateSnmpCredentials(String adminSessionToken) {
    List<SnmpLegacySecret> rows = snmpLegacySecrets();
    int migrated = 0;
    for (SnmpLegacySecret row : rows) {
      String plaintext = secretCrypto.decrypt(row.encryptedParams());
      String path = "/manager/snmp/credentials/" + row.id();
      String ref = createSecret(new VaultSecretWriteRequest(
          path,
          "Manager SNMP credential " + row.name(),
          "SNMP_V3".equalsIgnoreCase(row.version()) ? "SNMP_V3" : "SNMP_V2C",
          payload(plaintext)), adminSessionToken);
      jdbcTemplate.update("update snmp_credentials set vault_ref = ? where id = ?", ref, row.id());
      migrated++;
    }
    return migrated;
  }

  private List<SnmpLegacySecret> snmpLegacySecrets() {
    return jdbcTemplate.query("""
        select id, name, version, encrypted_params
        from snmp_credentials
        where encrypted_params is not null
          and encrypted_params <> ''
          and (vault_ref is null or vault_ref = '')
        """, VaultMigrationService::snmpLegacySecret);
  }

  private List<PendingSecret> pendingIntegrations() {
    return integrationLegacySecrets().stream()
        .map(row -> new PendingSecret(
            "integration",
            row.serviceName(),
            "/manager/integrations/" + safeSegment(row.serviceName()) + "/secret",
            "API_TOKEN"))
        .toList();
  }

  private List<PendingSecret> pendingSnmpCredentials() {
    return snmpLegacySecrets().stream()
        .map(row -> new PendingSecret(
            "snmp",
            row.name(),
            "/manager/snmp/credentials/" + row.id(),
            "SNMP_V3".equalsIgnoreCase(row.version()) ? "SNMP_V3" : "SNMP_V2C"))
        .toList();
  }

  private String createSecret(VaultSecretWriteRequest request, String adminSessionToken) {
    if (adminSessionToken == null || adminSessionToken.isBlank()) {
      return vaultClient.createSecret(request);
    }
    return vaultClient.createSecret(request, adminSessionToken);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> payload(String plaintext) {
    if (plaintext != null && !plaintext.isBlank()) {
      try {
        Object value = objectMapper.readValue(plaintext, Object.class);
        if (value instanceof Map<?, ?> map) {
          Map<String, Object> normalized = new LinkedHashMap<>();
          for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
          }
          return normalized;
        }
      } catch (Exception ignored) {
        // Legacy values were not guaranteed to be JSON.
      }
    }
    return Map.of("value", plaintext == null ? "" : plaintext);
  }

  private static IntegrationLegacySecret integrationLegacySecret(ResultSet rs, int rowNum) throws SQLException {
    return new IntegrationLegacySecret(rs.getString("service_name"), rs.getString("encrypted_secret"));
  }

  private static SnmpLegacySecret snmpLegacySecret(ResultSet rs, int rowNum) throws SQLException {
    return new SnmpLegacySecret(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("version"),
        rs.getString("encrypted_params"));
  }

  private static String safeSegment(String value) {
    return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "-");
  }

  private record IntegrationLegacySecret(String serviceName, String encryptedSecret) {
  }

  private record SnmpLegacySecret(long id, String name, String version, String encryptedParams) {
  }

  public record MigrationReport(int migratedIntegrationSecrets, int migratedSnmpCredentials, String status) {
  }

  public record MigrationStatus(
      boolean vaultEnabled,
      int pendingIntegrationSecrets,
      int pendingSnmpCredentials,
      int migratedIntegrationSecrets,
      int migratedSnmpCredentials,
      String status) {
  }

  public record PendingMigrationPlan(
      boolean vaultEnabled,
      int pendingIntegrationSecrets,
      int pendingSnmpCredentials,
      List<PendingSecret> integrations,
      List<PendingSecret> snmpCredentials,
      String status) {
  }

  public record PendingSecret(String source, String name, String vaultPath, String type) {
  }
}
