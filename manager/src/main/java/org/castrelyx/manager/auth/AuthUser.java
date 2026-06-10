package org.castrelyx.manager.auth;

public record AuthUser(long id, String username, String displayName, Role role) {
}
