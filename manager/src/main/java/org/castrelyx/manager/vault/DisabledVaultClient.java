package org.castrelyx.manager.vault;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "manager.vault", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledVaultClient implements VaultClient {
  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public String createSecret(VaultSecretWriteRequest request) {
    throw new IllegalStateException("Manager Vault client is disabled");
  }

  @Override
  public String createSecret(VaultSecretWriteRequest request, String adminSessionToken) {
    throw new IllegalStateException("Manager Vault client is disabled");
  }

  @Override
  public String resolveString(String reference) {
    throw new IllegalStateException("Manager Vault client is disabled");
  }
}
