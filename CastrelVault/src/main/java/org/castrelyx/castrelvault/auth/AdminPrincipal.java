package org.castrelyx.castrelvault.auth;

public record AdminPrincipal(
    long sessionId,
    long userId,
    String username,
    String role,
    boolean requirePasswordChange,
    String csrfToken) {
  public void requireReady() {
    if (requirePasswordChange) {
      throw new IllegalStateException("password change is required before accessing CastrelVault");
    }
    if (!"ADMIN".equals(role)) {
      throw new IllegalStateException("ADMIN role is required");
    }
  }
}
