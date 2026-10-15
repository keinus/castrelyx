package org.keinus.logparser.domain.model.structured;

import java.util.List;
import java.util.Map;

/** Canonical metric/state/event rows shared by all output adapters. */
public record StructuredTelemetry(
        List<Map<String, Object>> metrics,
        List<Map<String, Object>> states,
        List<Map<String, Object>> events) {
    public StructuredTelemetry {
        metrics = List.copyOf(metrics);
        states = List.copyOf(states);
        events = List.copyOf(events);
    }
}
