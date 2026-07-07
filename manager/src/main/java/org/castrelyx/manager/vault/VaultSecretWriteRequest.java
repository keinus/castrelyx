package org.castrelyx.manager.vault;

import java.util.Map;

public record VaultSecretWriteRequest(
    String path,
    String displayName,
    String type,
    Map<String, Object> payload) {
}
