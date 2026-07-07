package org.castrelyx.manager.integration;

import org.castrelyx.manager.secret.SecretValue;

public record IntegrationConfig(
    String serviceName,
    String baseUrl,
    SecretValue secret,
    String vaultRef,
    boolean enabled) {
}
