package org.castrelyx.manager.snmp;

public record SnmpTarget(
    long id,
    String name,
    String host,
    int port,
    Long credentialId,
    boolean enabled,
    long pollIntervalMs,
    Long logparserAdapterId) {
}
