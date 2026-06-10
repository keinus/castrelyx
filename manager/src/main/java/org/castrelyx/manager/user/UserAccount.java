package org.castrelyx.manager.user;

import org.castrelyx.manager.auth.Role;

public record UserAccount(
    long id,
    String username,
    String passwordHash,
    String displayName,
    Role role,
    boolean enabled) {
}
