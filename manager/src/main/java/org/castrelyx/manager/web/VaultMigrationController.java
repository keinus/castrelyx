package org.castrelyx.manager.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.vault.VaultMigrationService;
import org.castrelyx.manager.vault.VaultMigrationService.MigrationReport;
import org.castrelyx.manager.vault.VaultMigrationService.MigrationStatus;
import org.castrelyx.manager.vault.VaultMigrationService.PendingMigrationPlan;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vault-migration")
public class VaultMigrationController {
  private final VaultMigrationService migrationService;
  private final ManagerProperties properties;

  public VaultMigrationController(VaultMigrationService migrationService, ManagerProperties properties) {
    this.migrationService = migrationService;
    this.properties = properties;
  }

  @GetMapping("/status")
  public MigrationStatus status(@RequestHeader(name = "Authorization", required = false) String authorization) {
    requireToken(authorization);
    return migrationService.status();
  }

  @PostMapping("/dry-run")
  public PendingMigrationPlan dryRun(@RequestHeader(name = "Authorization", required = false) String authorization) {
    requireToken(authorization);
    return migrationService.dryRunLegacySecrets();
  }

  @PostMapping("/run")
  public MigrationReport run(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "X-CastrelVault-Admin-Session", required = false) String vaultAdminSession) {
    requireToken(authorization);
    return migrationService.migrateLegacySecrets(vaultAdminSession);
  }

  private void requireToken(String authorization) {
    String expected = properties.vault() == null ? null : properties.vault().migrationToken();
    if (expected == null || expected.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vault migration API is not configured");
    }
    String prefix = "Bearer ";
    if (authorization == null || !authorization.startsWith(prefix)
        || !MessageDigest.isEqual(authorization.substring(prefix.length()).getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid Vault migration token");
    }
  }
}
