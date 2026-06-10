package org.castrelyx.manager.telemetry;

import java.time.Instant;

public record CanonicalTelemetryRecord(
    Kind kind,
    Instant observedAt,
    String assetUid,
    String sourceType,
    String sourceId,
    String metricName,
    Double metricValue,
    String unit,
    String labelsJson,
    String stateType,
    String stateKey,
    String stateJson,
    String eventType,
    String severity,
    String eventJson) {
  public enum Kind {
    ASSET,
    METRIC,
    STATE,
    EVENT
  }

  public static CanonicalTelemetryRecord metric(Instant observedAt, String assetUid, String sourceType, String sourceId,
      String metricName, Double metricValue, String unit, String labelsJson) {
    return new CanonicalTelemetryRecord(Kind.METRIC, observedAt, assetUid, sourceType, sourceId, metricName, metricValue,
        unit, labelsJson, null, null, null, null, null, null);
  }

  public static CanonicalTelemetryRecord state(Instant observedAt, String assetUid, String sourceType, String sourceId,
      String stateType, String stateKey, String stateJson) {
    return new CanonicalTelemetryRecord(Kind.STATE, observedAt, assetUid, sourceType, sourceId, null, null, null, null,
        stateType, stateKey, stateJson, null, null, null);
  }

  public static CanonicalTelemetryRecord event(Instant observedAt, String assetUid, String sourceType, String sourceId,
      String eventType, String severity, String eventJson) {
    return new CanonicalTelemetryRecord(Kind.EVENT, observedAt, assetUid, sourceType, sourceId, null, null, null, null,
        null, null, null, eventType, severity, eventJson);
  }
}
