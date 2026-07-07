package org.castrelyx.manager.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.castrelyx.manager.integration.IntegrationService;
import org.castrelyx.manager.integration.IntegrationUpdateRequest;
import org.castrelyx.manager.secret.SecretCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "manager.vault.enabled=true",
    "manager.vault.base-url=http://castrelvault:8781",
    "manager.vault.migration-token=vault-migration-token",
    "manager.vault.migrationToken=vault-migration-token"
})
class VaultMigrationServiceTest {
  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  SecretCrypto secretCrypto;

  @Autowired
  VaultMigrationService migrationService;

  @Autowired
  IntegrationService integrationService;

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  VaultClient vaultClient;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from integration_configs");
    jdbcTemplate.update("delete from snmp_targets");
    jdbcTemplate.update("delete from snmp_credentials");
    doReturn(true).when(vaultClient).isEnabled();
    doAnswer(invocation -> {
      VaultSecretWriteRequest request = invocation.getArgument(0);
      return "vault://" + request.path();
    }).when(vaultClient).createSecret(any(VaultSecretWriteRequest.class));
    doAnswer(invocation -> {
      VaultSecretWriteRequest request = invocation.getArgument(0);
      return "vault://" + request.path();
    }).when(vaultClient).createSecret(any(VaultSecretWriteRequest.class), anyString());
  }

  @Test
  void migratesLegacyIntegrationAndSnmpSecretsToVaultReferences() {
    String integrationPlaintext = "castrelsign-admin-token-secret";
    String snmpPlaintext = "{\"community\":\"private-community\"}";
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values ('castrelsign', 'https://castrelsign:8443', ?, true, current_timestamp, current_timestamp)
        """, secretCrypto.encrypt(integrationPlaintext));
    jdbcTemplate.update("""
        insert into snmp_credentials(name, version, encrypted_params)
        values ('edge-snmp', 'SNMP_V2C', ?)
        """, secretCrypto.encrypt(snmpPlaintext));
    Long snmpId = jdbcTemplate.queryForObject("select id from snmp_credentials where name = 'edge-snmp'", Long.class);

    VaultMigrationService.MigrationReport report = migrationService.migrateLegacySecrets();

    assertThat(report.migratedIntegrationSecrets()).isEqualTo(1);
    assertThat(report.migratedSnmpCredentials()).isEqualTo(1);

    String integrationVaultRef = jdbcTemplate.queryForObject(
        "select vault_ref from integration_configs where service_name = 'castrelsign'", String.class);
    String integrationEncrypted = jdbcTemplate.queryForObject(
        "select encrypted_secret from integration_configs where service_name = 'castrelsign'", String.class);
    String snmpVaultRef = jdbcTemplate.queryForObject(
        "select vault_ref from snmp_credentials where name = 'edge-snmp'", String.class);

    assertThat(integrationVaultRef).isEqualTo("vault:///manager/integrations/castrelsign/secret");
    assertThat(integrationEncrypted).isNull();
    assertThat(snmpVaultRef).isEqualTo("vault:///manager/snmp/credentials/" + snmpId);

    ArgumentCaptor<VaultSecretWriteRequest> requests = ArgumentCaptor.forClass(VaultSecretWriteRequest.class);
    verify(vaultClient, org.mockito.Mockito.times(2)).createSecret(requests.capture());
    List<VaultSecretWriteRequest> writes = requests.getAllValues();
    assertThat(writes).anySatisfy(request -> {
      assertThat(request.path()).isEqualTo("/manager/integrations/castrelsign/secret");
      assertThat(request.type()).isEqualTo("API_TOKEN");
      assertThat(request.payload()).containsEntry("value", integrationPlaintext);
    });
    assertThat(writes).anySatisfy(request -> {
      assertThat(request.path()).isEqualTo("/manager/snmp/credentials/" + snmpId);
      assertThat(request.type()).isEqualTo("SNMP_V2C");
      assertThat(request.payload()).containsEntry("community", "private-community");
    });
  }

  @Test
  void integrationReadsResolveThroughVaultAndUpdatesDoNotRequireSecretBodyWhenReferenceExists() {
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, vault_ref, enabled, created_at, updated_at)
        values ('logparser', 'http://logparser:8765', null, 'vault:///manager/integrations/logparser/secret', true,
                current_timestamp, current_timestamp)
        """);
    doReturn("resolved-logparser-token").when(vaultClient)
        .resolveString("vault:///manager/integrations/logparser/secret");

    assertThat(integrationService.decryptedSecret("logparser")).isEqualTo("resolved-logparser-token");

    var updated = integrationService.upsert("logparser", new IntegrationUpdateRequest("http://logparser:9000", null, null, true));

    assertThat(updated.vaultRef()).isEqualTo("vault:///manager/integrations/logparser/secret");
    assertThat(updated.secret().configured()).isTrue();
    String encrypted = jdbcTemplate.queryForObject(
        "select encrypted_secret from integration_configs where service_name = 'logparser'", String.class);
    assertThat(encrypted).isNull();
  }

  @Test
  void newIntegrationSecretWritesToVaultWhenClientIsEnabled() {
    var updated = integrationService.upsert("castrelsign",
        new IntegrationUpdateRequest("https://castrelsign:8443", "new-admin-token", null, true));

    assertThat(updated.vaultRef()).isEqualTo("vault:///manager/integrations/castrelsign/secret");
    assertThat(updated.secret().configured()).isTrue();
    String encrypted = jdbcTemplate.queryForObject(
        "select encrypted_secret from integration_configs where service_name = 'castrelsign'", String.class);
    assertThat(encrypted).isNull();
  }

  @Test
  void migrationControllerProvidesDryRunAndUsesForwardedVaultAdminSessionForRun() throws Exception {
    String integrationPlaintext = "manager-controller-secret";
    jdbcTemplate.update("""
        insert into integration_configs(service_name, base_url, encrypted_secret, enabled, created_at, updated_at)
        values ('castrelsign', 'https://castrelsign:8443', ?, true, current_timestamp, current_timestamp)
        """, secretCrypto.encrypt(integrationPlaintext));

    mockMvc.perform(get("/api/vault-migration/status")
            .header("Authorization", "Bearer vault-migration-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vaultEnabled").value(true))
        .andExpect(jsonPath("$.pendingIntegrationSecrets").value(1));

    mockMvc.perform(post("/api/vault-migration/dry-run")
            .header("Authorization", "Bearer vault-migration-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingIntegrationSecrets").value(1))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(integrationPlaintext))));

    mockMvc.perform(post("/api/vault-migration/run")
            .header("Authorization", "Bearer vault-migration-token")
            .header("X-CastrelVault-Admin-Session", "vault-admin-session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.migratedIntegrationSecrets").value(1));

    verify(vaultClient).createSecret(any(VaultSecretWriteRequest.class), org.mockito.ArgumentMatchers.eq("vault-admin-session"));
  }
}
