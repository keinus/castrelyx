package org.castrelyx.manager.alert;

import java.time.Instant;

public record AlertInstance(
    long id,
    long ruleId,
    Long assetId,
    Severity severity,
    AlertStatus status,
    String title,
    String detail,
    String stateKey,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant acknowledgedAt,
    Instant resolvedAt) {
}
