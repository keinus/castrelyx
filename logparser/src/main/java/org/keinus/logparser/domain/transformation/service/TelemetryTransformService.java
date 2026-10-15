package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.keinus.logparser.domain.model.structured.StructuredTelemetry;
import org.springframework.stereotype.Service;

/** The output-independent telemetry contract. All protocol interpretation happens here. */
@Service
public class TelemetryTransformService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final DateTimeFormatter CLICKHOUSE_DATE_TIME_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]");
    private static final DateTimeFormatter CLICKHOUSE_DATE_TIME_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private final SnmpHostMetricNormalizer snmpHostMetrics = new SnmpHostMetricNormalizer();
    private final TelemetryAssetBindings bindings;

    public TelemetryTransformService(TelemetryAssetBindings bindings) { this.bindings = bindings; }

    public StructuredTelemetry transform(Map<String, Object> fields) {
        JsonNode event = OBJECT_MAPPER.valueToTree(fields);
        JsonNode additional = event.path("additionalAttributes");
        String source = firstNonBlank(text(event, "source_id"), text(additional, "source_id"), text(event, "source_host"), "unknown");
        var record = new InputRecord(event, source,
                firstNonBlank(text(event, "item_kind"), text(additional, "item_kind")),
                firstNonBlank(text(event, "item_type"), text(additional, "item_type")),
                firstNonBlank(text(event, "item_key"), text(additional, "item_key")));
        List<Map<String, Object>> metrics = new ArrayList<>(), states = new ArrayList<>(), events = new ArrayList<>();
        addTelemetryRows(record, metrics, states, events);
        return new StructuredTelemetry(metrics, states, events);
    }

    private record InputRecord(JsonNode event, String sourceId, String itemKind, String itemType, String itemKey) {}

    private void addTelemetryRows(
            InputRecord record,
            List<Map<String, Object>> metricRows,
            List<Map<String, Object>> stateRows,
            List<Map<String, Object>> eventRows
    ) {
        JsonNode event = record.event();
        JsonNode payload = payloadNode(event);
        String kind = firstNonBlank(
                record.itemKind(),
                text(event, "item_kind"),
                text(event.path("common"), "eventCategory"));
        if ("event_metric".equals(event.path("subDomainType").asText())) kind = "metric";
        if (kind == null && "snmp".equalsIgnoreCase(firstNonBlank(text(payload, "protocol"), text(event, "protocol")))) {
            kind = "snmp";
        }
        Instant observedAt = observedAt(event, payload);
        if ("snmp".equalsIgnoreCase(kind)) {
            addSnmpRows(record, event, payload, observedAt, metricRows, stateRows, eventRows);
            return;
        }
        if ("metric".equalsIgnoreCase(kind)) {
            Map<String, Object> row = metricRow(record, event, payload, observedAt,
                    firstNonBlank(text(payload, "source_type"), "AGENT"));
            if (row != null) {
                metricRows.add(row);
            }
            return;
        }
        if ("state".equalsIgnoreCase(kind)) {
            stateRows.add(stateRow(
                    observedAt,
                    assetUid(record, event, payload),
                    "AGENT",
                    record.sourceId(),
                    firstNonBlank(record.itemType(), text(payload, "state_type"), "state"),
                    firstNonBlank(record.itemKey(), text(payload, "state_key"), "state"),
                    json(payload)));
            return;
        }
        if ("event".equalsIgnoreCase(kind)) {
            eventRows.add(eventRow(
                    observedAt,
                    assetUid(record, event, payload),
                    "AGENT",
                    record.sourceId(),
                    firstNonBlank(text(payload, "event_type"), text(event, "event_type"), record.itemType(), "agent.event"),
                    firstNonBlank(text(payload, "severity"), text(event, "severity"), text(event.path("common"), "severity")),
                    json(payload)));
            return;
        }
        if ("asset".equalsIgnoreCase(kind) || "identity".equalsIgnoreCase(record.itemType())) {
            stateRows.add(stateRow(
                    observedAt,
                    assetUid(record, event, payload),
                    "AGENT",
                    record.sourceId(),
                    "identity",
                    firstNonBlank(record.itemKey(), record.sourceId()),
                    json(payload)));
        }
    }

    private void addSnmpRows(
            InputRecord record,
            JsonNode event,
            JsonNode payload,
            Instant observedAt,
            List<Map<String, Object>> metricRows,
            List<Map<String, Object>> stateRows,
            List<Map<String, Object>> eventRows
    ) {
        String targetHost = firstNonBlank(text(payload, "target_host"), text(event, "target_host"), record.sourceId());
        String targetName = firstNonBlank(text(payload, "target_name"), text(event, "target_name"), targetHost);
        ObjectNode identity = OBJECT_MAPPER.createObjectNode()
                .put("target_name", targetName)
                .put("target_host", targetHost);
        stateRows.add(stateRow(observedAt, targetHost, "SNMP", record.sourceId(), "identity", targetHost, json(identity)));

        JsonNode metrics = payload.path("metrics");
        SnmpHostMetricNormalizer.Result normalized = snmpHostMetrics.normalize(
                record.sourceId() + "\u0000" + targetHost, observedAt, payload);
        for (SnmpHostMetricNormalizer.Metric metric : normalized.metrics()) {
            Map<String, Object> row = baseTelemetryRow(observedAt, targetHost, "SNMP", record.sourceId());
            row.put("metric_name", metric.name());
            row.put("metric_value", metric.value());
            row.put("unit", metric.unit());
            row.put("labels_json", json(OBJECT_MAPPER.valueToTree(metric.labels())));
            metricRows.add(row);
        }
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric.hasNonNull("name") && normalized.handledNames().contains(text(metric, "name"))) continue;
                Map<String, Object> row = snmpMetricRow(record, targetHost, observedAt, metric, null);
                if (row != null) {
                    metricRows.add(row);
                }
            }
        } else if (metrics.isObject()) {
            metrics.fields().forEachRemaining(field -> {
                if (normalized.handledNames().contains(field.getKey())) return;
                Map<String, Object> row = snmpMetricRow(record, targetHost, observedAt, field.getValue(), field.getKey());
                if (row != null) {
                    metricRows.add(row);
                }
            });
        }

        String operation = text(payload, "operation");
        if ("trap".equals(operation) || "inform".equals(operation)) {
            eventRows.add(eventRow(observedAt, targetHost, "SNMP", record.sourceId(), "snmp." + operation, "INFO", json(payload)));
        }
        if ("error".equalsIgnoreCase(firstNonBlank(text(payload, "poll_status"), text(event, "poll_status")))) {
            eventRows.add(eventRow(observedAt, targetHost, "SNMP", record.sourceId(), "snmp.poll.failure", "WARNING", json(payload)));
        }
    }

    private Map<String, Object> metricRow(
            InputRecord record,
            JsonNode event,
            JsonNode metric,
            Instant observedAt,
            String sourceType
    ) {
        Double value = metricValue(metric);
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        Map<String, Object> row = baseTelemetryRow(observedAt, assetUid(record, event, metric), sourceType, record.sourceId());
        row.put("metric_name", firstNonBlank(
                text(metric, "metric_name"),
                text(event, "metric_name"),
                record.itemKey(),
                record.itemType()));
        row.put("metric_value", value);
        row.put("unit", text(metric, "unit"));
        row.put("labels_json", json(metricLabels(metric)));
        return firstNonBlank((String) row.get("metric_name")) != null ? row : null;
    }

    private Map<String, Object> snmpMetricRow(
            InputRecord record,
            String targetHost,
            Instant observedAt,
            JsonNode metric,
            String fallbackName
    ) {
        String name = firstNonBlank(text(metric, "name"), fallbackName);
        Double value = metricValue(metric);
        if (value == null || !Double.isFinite(value) || name == null || name.isBlank()
                || !"success".equals(metric.path("status").asText("success"))) {
            return null;
        }
        ObjectNode labels = OBJECT_MAPPER.createObjectNode();
        copyLabel(metric, labels, "interface");
        String canonicalName = snmpMetricName(name);
        String unit = text(metric, "unit");
        if ("host.network.rx_bytes".equals(canonicalName) || "host.network.tx_bytes".equals(canonicalName)) {
            unit = "bytes";
            labels.put("direction", "host.network.rx_bytes".equals(canonicalName) ? "ingress" : "egress");
        }
        Map<String, Object> row = baseTelemetryRow(observedAt, targetHost, "SNMP", record.sourceId());
        row.put("metric_name", canonicalName);
        row.put("metric_value", value);
        row.put("unit", unit);
        row.put("labels_json", json(labels));
        return row;
    }

    private Map<String, Object> stateRow(
            Instant observedAt,
            String assetUid,
            String sourceType,
            String sourceId,
            String stateType,
            String stateKey,
            String stateJson
    ) {
        Map<String, Object> row = baseTelemetryRow(observedAt, assetUid, sourceType, sourceId);
        row.put("state_type", firstNonBlank(stateType, "state"));
        row.put("state_key", firstNonBlank(stateKey, "state"));
        row.put("state_json", stateJson);
        return row;
    }

    private Map<String, Object> eventRow(
            Instant observedAt,
            String assetUid,
            String sourceType,
            String sourceId,
            String eventType,
            String severity,
            String eventJson
    ) {
        Map<String, Object> row = baseTelemetryRow(observedAt, assetUid, sourceType, sourceId);
        row.put("event_type", firstNonBlank(eventType, "event"));
        row.put("severity", severity);
        row.put("event_json", eventJson);
        return row;
    }

    private Map<String, Object> baseTelemetryRow(Instant observedAt, String assetUid, String sourceType, String sourceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("observed_at", formatDateTime64(observedAt));
        row.put("asset_uid", bindings.resolve(firstNonBlank(assetUid, sourceId, "unknown")));
        row.put("source_type", firstNonBlank(sourceType, "AGENT"));
        row.put("source_id", firstNonBlank(sourceId, assetUid, "unknown"));
        return row;
    }

    private static JsonNode payloadNode(JsonNode event) {
        if (List.of("event_snmp", "event_metric").contains(event.path("subDomainType").asText())
                && event.path("subFields").isObject()) {
            ObjectNode payload = OBJECT_MAPPER.createObjectNode();
            if (event.path("additionalAttributes").isObject()) payload.setAll((ObjectNode) event.path("additionalAttributes"));
            payload.setAll((ObjectNode) event.path("subFields"));
            return payload;
        }
        JsonNode attributes = event.path("additionalAttributes");
        if ("snmp".equals(attributes.path("protocol").asText())) {
            return attributes;
        }
        JsonNode payload = event.path("payload");
        if (payload.isObject()) {
            return payload;
        }
        JsonNode nestedPayload = event.path("additionalAttributes").path("payload");
        if (nestedPayload.isObject()) {
            return nestedPayload;
        }
        JsonNode rawLog = event.path("common").path("rawLog");
        if (rawLog.isTextual()) {
            try {
                JsonNode rawPayload = OBJECT_MAPPER.readTree(rawLog.asText()).path("payload");
                if (rawPayload.isObject()) {
                    return rawPayload;
                }
            } catch (IOException ignored) {
                // Fall through to the transformed event root.
            }
        }
        if (event.isObject()) {
            ObjectNode flattenedPayload = OBJECT_MAPPER.createObjectNode();
            event.fields().forEachRemaining(field -> {
                if (field.getKey().startsWith("payload_") && field.getKey().length() > "payload_".length()) {
                    flattenedPayload.set(field.getKey().substring("payload_".length()), field.getValue());
                }
            });
            if (flattenedPayload.size() > 0) {
                return flattenedPayload;
            }
        }
        return event.isObject() ? event : OBJECT_MAPPER.createObjectNode();
    }

    private static ObjectNode metricLabels(JsonNode metric) {
        ObjectNode labels = OBJECT_MAPPER.createObjectNode();
        JsonNode existing = metric.path("labels");
        if (existing.isObject()) {
            existing.fields().forEachRemaining(field -> labels.set(field.getKey(), field.getValue()));
        }
        copyLabel(metric, labels, "collector");
        copyLabel(metric, labels, "interface");
        copyLabel(metric, labels, "direction");
        copyLabel(metric, labels, "mount_point");
        copyLabel(metric, labels, "filesystem");
        copyLabel(metric, labels, "device");
        return labels;
    }

    private static void copyLabel(JsonNode source, ObjectNode labels, String field) {
        JsonNode value = value(source, field);
        if (value != null && !value.isNull() && !labels.has(field)) {
            labels.set(field, value);
        }
    }

    private static Double metricValue(JsonNode metric) {
        JsonNode value = value(metric, "metric_value");
        if (value == null || value.isNull()) {
            value = value(metric, "value");
        }
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String assetUid(InputRecord record, JsonNode event, JsonNode payload) {
        return firstNonBlank(text(payload, "asset_uid"), text(event, "asset_uid"), record.sourceId());
    }

    private static Instant observedAt(JsonNode event, JsonNode payload) {
        return instant(firstNonBlank(
                text(payload, "event_time"),
                text(payload, "observed_at"),
                text(event, "observed_at"),
                text(event, "@timestamp"),
                text(event.path("common"), "eventTime"),
                text(event.path("common"), "time")),
                Instant.now());
    }

    private static Instant instant(String text, Instant fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text, CLICKHOUSE_DATE_TIME_IN).toInstant(ZoneOffset.UTC);
            } catch (RuntimeException ignoredAgain) {
                return fallback;
            }
        }
    }

    private static String formatDateTime64(Instant instant) {
        return CLICKHOUSE_DATE_TIME_OUT.format(instant);
    }

    private static String snmpMetricName(String name) {
        return switch (name == null ? "" : name) {
            case "ifInOctets", "ifHCInOctets" -> "host.network.rx_bytes";
            case "ifOutOctets", "ifHCOutOctets" -> "host.network.tx_bytes";
            case "ifInErrors" -> "interface.in.errors";
            case "ifOutErrors" -> "interface.out.errors";
            case "ifInDiscards" -> "interface.in.discards";
            case "ifOutDiscards" -> "interface.out.discards";
            default -> "snmp." + name;
        };
    }

    private static JsonNode value(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode direct = node.get(field);
        if (direct != null && !direct.isNull()) {
            return direct;
        }
        JsonNode flattened = node.get("payload_" + field);
        return flattened == null || flattened.isNull() ? null : flattened;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = value(node, field);
        return value == null ? null : value.asText();
    }

    private static String json(Object value) {
        try { return OBJECT_MAPPER.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid telemetry JSON", e); }
    }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
