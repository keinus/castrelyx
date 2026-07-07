package org.castrelyx.castrelvault.application;

import java.time.Instant;

public interface CastrelSignAccessClient {
  AccessDecision checkVaultAccess(String principalId, String permission, String certificateSerialNumber);

  record AccessDecision(boolean allowed, String reason, Instant cacheExpiresAt) {
    public static AccessDecision allowed(Instant cacheExpiresAt) {
      return new AccessDecision(true, "allowed", cacheExpiresAt);
    }

    public static AccessDecision denied(String reason) {
      return new AccessDecision(false, reason == null || reason.isBlank() ? "denied" : reason, Instant.now());
    }
  }
}
