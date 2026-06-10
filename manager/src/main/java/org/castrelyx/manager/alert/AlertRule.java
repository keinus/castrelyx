package org.castrelyx.manager.alert;

public record AlertRule(
    long id,
    String name,
    String ruleType,
    Severity severity,
    String expressionJson,
    boolean enabled) {
}
