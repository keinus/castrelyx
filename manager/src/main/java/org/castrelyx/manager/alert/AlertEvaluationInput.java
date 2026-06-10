package org.castrelyx.manager.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AlertEvaluationInput(
    String assetUid,
    Instant observedAt,
    Instant lastHeartbeatAt,
    Map<String, Double> metrics,
    Map<String, String> states,
    List<String> events) {
}
