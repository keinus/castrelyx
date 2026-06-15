package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelemetryNormalizer {
  private final ObjectMapper objectMapper = new ObjectMapper();

  public List<CanonicalTelemetryRecord> normalizeRawLogparserEvent(String rawJson) {
    try {
      JsonNode root = objectMapper.readTree(rawJson);
      Instant observedAt = instant(text(root, "received_at"), Instant.now());
      String sourceId = text(root, "source_id");
      String kind = text(root, "item_kind");
      JsonNode event = eventJson(root);
      if ("snmp".equalsIgnoreCase(kind)) {
        return normalizeSnmp(observedAt, sourceId, event);
      }
      if ("metric".equalsIgnoreCase(kind)) {
        JsonNode metric = payloadNode(event);
        return List.of(CanonicalTelemetryRecord.metric(
            observedAt,
            textOr(metric, "asset_uid", textOr(event, "asset_uid", sourceId)),
            "AGENT",
            sourceId,
            textOr(metric, "metric_name", textOr(event, "metric_name", textOr(root, "item_key", text(root, "item_type")))),
            metricValue(metric),
            nullableText(metric, "unit"),
            json(metricLabels(metric))));
      }
      if ("state".equalsIgnoreCase(kind)) {
        return List.of(CanonicalTelemetryRecord.state(
            observedAt,
            textOr(event, "asset_uid", sourceId),
            "AGENT",
            sourceId,
            textOr(root, "item_type", "state"),
            textOr(root, "item_key", "state"),
            json(event)));
      }
      if ("event".equalsIgnoreCase(kind)) {
        return List.of(CanonicalTelemetryRecord.event(
            observedAt,
            textOr(event, "asset_uid", sourceId),
            "AGENT",
            sourceId,
            textOr(event, "event_type", textOr(root, "item_type", "agent.event")),
            nullableText(event, "severity"),
            json(event)));
      }
      if ("asset".equalsIgnoreCase(kind) || "identity".equalsIgnoreCase(text(root, "item_type"))) {
        return List.of(CanonicalTelemetryRecord.state(
            observedAt,
            textOr(event, "asset_uid", sourceId),
            "AGENT",
            sourceId,
            "identity",
            textOr(root, "item_key", sourceId),
            json(event)));
      }
      return List.of();
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid raw telemetry JSON", exception);
    }
  }

  private List<CanonicalTelemetryRecord> normalizeSnmp(Instant observedAt, String sourceId, JsonNode event) {
    String targetHost = textOr(event, "target_host", sourceId);
    String targetName = textOr(event, "target_name", targetHost);
    List<CanonicalTelemetryRecord> records = new ArrayList<>();
    records.add(CanonicalTelemetryRecord.state(
        observedAt,
        targetHost,
        "SNMP",
        sourceId,
        "identity",
        targetHost,
        json(objectMapper.createObjectNode().put("target_name", targetName).put("target_host", targetHost))));
    for (JsonNode metric : event.path("metrics")) {
      ObjectNode labels = objectMapper.createObjectNode();
      if (metric.hasNonNull("interface")) {
        labels.put("interface", metric.get("interface").asText());
      }
      records.add(CanonicalTelemetryRecord.metric(
          observedAt,
          targetHost,
          "SNMP",
          sourceId,
          snmpMetricName(text(metric, "name")),
          metric.path("value").isMissingNode() ? null : metric.path("value").asDouble(),
          nullableText(metric, "unit"),
          json(labels)));
    }
    if ("error".equalsIgnoreCase(text(event, "poll_status"))) {
      records.add(CanonicalTelemetryRecord.event(
          observedAt,
          targetHost,
          "SNMP",
          sourceId,
          "snmp.poll.failure",
          "WARNING",
          json(event)));
    }
    return records;
  }

  private static String snmpMetricName(String name) {
    return switch (name == null ? "" : name) {
      case "ifInOctets" -> "interface.in.bytes";
      case "ifOutOctets" -> "interface.out.bytes";
      case "ifInErrors" -> "interface.in.errors";
      case "ifOutErrors" -> "interface.out.errors";
      case "ifInDiscards" -> "interface.in.discards";
      case "ifOutDiscards" -> "interface.out.discards";
      default -> "snmp." + name;
    };
  }

  private JsonNode eventJson(JsonNode root) throws JsonProcessingException {
    JsonNode node = root.path("event_json");
    if (node.isTextual()) {
      return objectMapper.readTree(node.asText());
    }
    return node.isMissingNode() || node.isNull() ? objectMapper.createObjectNode() : node;
  }

  private JsonNode payloadNode(JsonNode event) throws JsonProcessingException {
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
      JsonNode rawPayload = objectMapper.readTree(rawLog.asText()).path("payload");
      if (rawPayload.isObject()) {
        return rawPayload;
      }
    }
    return event;
  }

  private JsonNode metricLabels(JsonNode metric) {
    ObjectNode labels = objectMapper.createObjectNode();
    JsonNode existing = metric.path("labels");
    if (existing.isObject()) {
      existing.fields().forEachRemaining(field -> labels.set(field.getKey(), field.getValue()));
    }
    copyLabel(metric, labels, "collector");
    copyLabel(metric, labels, "interface");
    copyLabel(metric, labels, "direction");
    return labels;
  }

  private static void copyLabel(JsonNode source, ObjectNode labels, String field) {
    JsonNode value = source.get(field);
    if (value != null && !value.isNull() && !labels.has(field)) {
      labels.set(field, value);
    }
  }

  private static Double metricValue(JsonNode metric) {
    if (metric.hasNonNull("metric_value")) {
      return metric.path("metric_value").asDouble();
    }
    if (metric.hasNonNull("value")) {
      return metric.path("value").asDouble();
    }
    return null;
  }

  private String json(JsonNode node) {
    try {
      if (node == null || node.isMissingNode() || node.isNull()) {
        return "{}";
      }
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("failed to serialize telemetry JSON", exception);
    }
  }

  private static Instant instant(String text, Instant fallback) {
    return text == null || text.isBlank() ? fallback : Instant.parse(text);
  }

  private static String text(JsonNode node, String field) {
    return nullableText(node, field);
  }

  private static String textOr(JsonNode node, String field, String fallback) {
    String value = nullableText(node, field);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
