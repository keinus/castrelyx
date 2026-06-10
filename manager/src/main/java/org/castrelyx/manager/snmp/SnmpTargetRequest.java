package org.castrelyx.manager.snmp;

import java.util.List;

public record SnmpTargetRequest(
    String name,
    String host,
    Integer port,
    Long credentialId,
    Boolean enabled,
    Long pollIntervalMs,
    List<String> oids) {
}
