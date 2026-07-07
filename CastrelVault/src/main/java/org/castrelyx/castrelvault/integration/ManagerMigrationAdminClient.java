package org.castrelyx.castrelvault.integration;

import java.util.Map;

public interface ManagerMigrationAdminClient {
  Map<String, Object> status();

  Map<String, Object> dryRun();

  Map<String, Object> run(String vaultAdminSessionToken);
}
