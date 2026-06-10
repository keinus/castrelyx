package org.castrelyx.manager.telemetry;

public final class CanonicalTelemetrySchema {
  private CanonicalTelemetrySchema() {
  }

  public static String createMetricSamples(String database) {
    return """
        CREATE TABLE IF NOT EXISTS %s.manager_metric_samples (
          observed_at DateTime64(3),
          asset_uid String,
          source_type String,
          source_id String,
          metric_name String,
          metric_value Float64,
          unit Nullable(String),
          labels_json String
        ) ENGINE = MergeTree
        PARTITION BY toYYYYMM(observed_at)
        ORDER BY (asset_uid, metric_name, observed_at)
        """.formatted(database);
  }

  public static String createStateSnapshots(String database) {
    return """
        CREATE TABLE IF NOT EXISTS %s.manager_state_snapshots (
          observed_at DateTime64(3),
          asset_uid String,
          source_type String,
          source_id String,
          state_type String,
          state_key String,
          state_json String
        ) ENGINE = ReplacingMergeTree(observed_at)
        ORDER BY (asset_uid, state_type, state_key)
        """.formatted(database);
  }

  public static String createEvents(String database) {
    return """
        CREATE TABLE IF NOT EXISTS %s.manager_events (
          observed_at DateTime64(3),
          asset_uid String,
          source_type String,
          source_id String,
          event_type String,
          severity Nullable(String),
          event_json String
        ) ENGINE = MergeTree
        PARTITION BY toYYYYMM(observed_at)
        ORDER BY (asset_uid, event_type, observed_at)
        """.formatted(database);
  }
}
