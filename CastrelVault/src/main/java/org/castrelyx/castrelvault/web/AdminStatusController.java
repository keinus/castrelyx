package org.castrelyx.castrelvault.web;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.castrelvault.audit.AuditService;
import org.castrelyx.castrelvault.auth.AdminSessionService;
import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.castrelyx.castrelvault.crypto.MasterKeyRegistry;
import org.castrelyx.castrelvault.integration.CastrelSignApplicationAdminClient;
import org.castrelyx.castrelvault.integration.ManagerMigrationAdminClient;
import org.castrelyx.castrelvault.secret.SecretService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminStatusController {
  private final AdminSessionService sessions;
  private final CastrelVaultProperties properties;
  private final MasterKeyRegistry masterKeys;
  private final SecretService secrets;
  private final AuditService audit;
  private final CastrelSignApplicationAdminClient castrelSign;
  private final ManagerMigrationAdminClient managerMigration;

  public AdminStatusController(AdminSessionService sessions, CastrelVaultProperties properties,
      MasterKeyRegistry masterKeys, SecretService secrets, AuditService audit,
      CastrelSignApplicationAdminClient castrelSign, ManagerMigrationAdminClient managerMigration) {
    this.sessions = sessions;
    this.properties = properties;
    this.masterKeys = masterKeys;
    this.secrets = secrets;
    this.audit = audit;
    this.castrelSign = castrelSign;
    this.managerMigration = managerMigration;
  }

  @GetMapping("/status")
  public Map<String, Object> status(HttpServletRequest request) {
    sessions.requireReadyAdmin(request);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", "CastrelVault");
    response.put("dataDir", properties.getDataDir());
    response.put("databasePath", Path.of(properties.getDataDir()).resolve("vault.db").toString());
    response.put("activeMasterKeyId", masterKeys.activeKeyId());
    response.put("configuredMasterKeyIds", masterKeys.keys().keySet().stream().sorted().toList());
    response.put("tls", Map.of(
        "serverTlsConfigured", !isBlank(properties.getTlsKeyStorePath()),
        "trustStoreConfigured", !isBlank(properties.getTlsTrustStorePath()),
        "castrelSignCaConfigured", !isBlank(properties.getCastrelsignCaCertPath())));
    response.put("secrets", secrets.summary());
    response.put("audit", audit.summary());
    response.put("castrelSign", castrelSign.status());
    response.put("managerMigration", managerMigration.status());
    return response;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
