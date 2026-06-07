package org.castrelyx.castrelsign.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class BouncyCastleSupport {
  public static final String PROVIDER = "BC";

  private BouncyCastleSupport() {
  }

  public static void install() {
    if (Security.getProvider(PROVIDER) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }
}
