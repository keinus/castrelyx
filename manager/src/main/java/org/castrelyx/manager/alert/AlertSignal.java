package org.castrelyx.manager.alert;

public record AlertSignal(
    String ruleType,
    Severity severity,
    String stateKey,
    String title,
    String detail) {
}
