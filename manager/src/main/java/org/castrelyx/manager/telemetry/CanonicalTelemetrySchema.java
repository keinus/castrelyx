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
          labels_json String,
          INDEX idx_metric_observed_at observed_at TYPE minmax GRANULARITY 1,
          INDEX idx_metric_name metric_name TYPE set(4096) GRANULARITY 4,
          INDEX idx_metric_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4
        ) ENGINE = MergeTree
        PARTITION BY toDate(observed_at)
        ORDER BY (asset_uid, metric_name, observed_at)
        TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
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
          state_json String,
          INDEX idx_state_observed_at observed_at TYPE minmax GRANULARITY 1,
          INDEX idx_state_type state_type TYPE set(1024) GRANULARITY 4,
          INDEX idx_state_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4
        ) ENGINE = ReplacingMergeTree(observed_at)
        PARTITION BY toDate(observed_at)
        ORDER BY (asset_uid, state_type, state_key)
        TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
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
          event_json String,
          INDEX idx_event_observed_at observed_at TYPE minmax GRANULARITY 1,
          INDEX idx_event_type event_type TYPE set(1024) GRANULARITY 4,
          INDEX idx_event_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4
        ) ENGINE = MergeTree
        PARTITION BY toDate(observed_at)
        ORDER BY (asset_uid, event_type, observed_at)
        TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
        """.formatted(database);
  }
}
