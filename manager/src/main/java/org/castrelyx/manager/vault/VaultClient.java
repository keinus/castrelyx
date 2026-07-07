package org.castrelyx.manager.vault;

public interface VaultClient {
  boolean isEnabled();

  String createSecret(VaultSecretWriteRequest request);

  default String createSecret(VaultSecretWriteRequest request, String adminSessionToken) {
    return createSecret(request);
  }

  String resolveString(String reference);
}
