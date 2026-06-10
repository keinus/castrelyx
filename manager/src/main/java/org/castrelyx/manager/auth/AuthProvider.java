package org.castrelyx.manager.auth;

public interface AuthProvider {
  AuthUser authenticate(String username, String password);

  AuthUser currentUser(String sessionToken);

  String createSession(AuthUser user);

  void revokeSession(String sessionToken);
}
