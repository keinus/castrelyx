package org.castrelyx.manager.secret;

public final class SecretMasker {
  private SecretMasker() {
  }

  public static SecretValue mask(String encryptedSecret) {
    if (encryptedSecret == null || encryptedSecret.isBlank()) {
      return new SecretValue(false, null);
    }
    return new SecretValue(true, "********");
  }

  public static SecretValue maskConfigured(boolean configured) {
    return configured ? new SecretValue(true, "********") : new SecretValue(false, null);
  }
}
