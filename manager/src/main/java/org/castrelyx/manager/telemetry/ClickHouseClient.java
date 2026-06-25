package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.config.ManagerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ClickHouseClient {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DateTimeFormatter CLICKHOUSE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]");
  private static final DateTimeFormatter CLICKHOUSE_DATE_TIME_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
      .withZone(ZoneOffset.UTC);
  private static final String RECENT_TRANSFORMED_DASHBOARD_FILTER = "AND observed_at >= now() - INTERVAL 1 HOUR";

  private final ManagerProperties properties;
  private final RestClient restClient;

  public ClickHouseClient(ManagerProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    this.restClient = builder.build();
  }

  public void execute(String sql) {
    post(sql, "").toBodilessEntity();
  }

  public void ensureCanonicalTables() {
    String database = properties.clickhouse().database();
    execute(CanonicalTelemetrySchema.createMetricSamples(database));
    execute(CanonicalTelemetrySchema.createStateSnapshots(database));
    execute(CanonicalTelemetrySchema.createEvents(database));
    ensureCanonicalRetentionAndIndexes(database);
  }

  private void ensureCanonicalRetentionAndIndexes(String database) {
    String metricTable = tableReference(database, "manager_metric_samples");
    String stateTable = tableReference(database, "manager_state_snapshots");
    String eventTable = tableReference(database, "manager_events");
    execute("ALTER TABLE " + metricTable
        + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0");
    execute("ALTER TABLE " + stateTable
        + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0");
    execute("ALTER TABLE " + eventTable
        + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0");
    execute("ALTER TABLE " + metricTable + " ADD INDEX IF NOT EXISTS idx_metric_observed_at observed_at TYPE minmax GRANULARITY 1");
    execute("ALTER TABLE " + metricTable + " ADD INDEX IF NOT EXISTS idx_metric_name metric_name TYPE set(4096) GRANULARITY 4");
    execute("ALTER TABLE " + metricTable + " ADD INDEX IF NOT EXISTS idx_metric_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4");
    execute("ALTER TABLE " + stateTable + " ADD INDEX IF NOT EXISTS idx_state_observed_at observed_at TYPE minmax GRANULARITY 1");
    execute("ALTER TABLE " + stateTable + " ADD INDEX IF NOT EXISTS idx_state_type state_type TYPE set(1024) GRANULARITY 4");
    execute("ALTER TABLE " + stateTable + " ADD INDEX IF NOT EXISTS idx_state_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4");
    execute("ALTER TABLE " + eventTable + " ADD INDEX IF NOT EXISTS idx_event_observed_at observed_at TYPE minmax GRANULARITY 1");
    execute("ALTER TABLE " + eventTable + " ADD INDEX IF NOT EXISTS idx_event_type event_type TYPE set(1024) GRANULARITY 4");
    execute("ALTER TABLE " + eventTable + " ADD INDEX IF NOT EXISTS idx_event_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4");
  }

  public List<RawTelemetryRow> fetchRawTelemetryRows(String cursorValue, int limit) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String where = cursorValue == null || cursorValue.isBlank() ? "" : "where " + cursorWhere(cursorValue);
    String sql = """
        SELECT received_at, source_id, item_kind, item_type, item_key, event_json
        FROM %s
        %s
        ORDER BY received_at, source_id, ifNull(item_key, '')
        LIMIT %d
        FORMAT JSONEachRow
        """.formatted(table, where, limit);
    String body = post(sql, "").body(String.class);
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<RawTelemetryRow> rows = new ArrayList<>();
    for (String line : body.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        JsonNode node = OBJECT_MAPPER.readTree(line);
        rows.add(new RawTelemetryRow(
            parseInstant(node.path("received_at").asText()),
            node.path("source_id").asText(),
            nullableText(node, "item_kind"),
            nullableText(node, "item_type"),
            nullableText(node, "item_key"),
            node.path("event_json").asText("{}")));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("invalid ClickHouse raw telemetry row", exception);
      }
    }
    return rows;
  }

  public void insertCanonicalRecords(List<CanonicalTelemetryRecord> records) {
    insertMetrics(records.stream().filter(record -> record.kind() == CanonicalTelemetryRecord.Kind.METRIC).toList());
    insertStates(records.stream().filter(record -> record.kind() == CanonicalTelemetryRecord.Kind.STATE).toList());
    insertEvents(records.stream().filter(record -> record.kind() == CanonicalTelemetryRecord.Kind.EVENT).toList());
  }

  public List<TrafficQueryService.InterfaceTraffic> queryInterfaceTraffic(String range, String assetUid) {
    String rangeInterval = rangeInterval(range);
    return TrafficInterfaceFilter.visibleTrafficRows(queryCanonicalInterfaceTraffic(rangeInterval, assetUid)).stream()
        .sorted(Comparator.comparingDouble((TrafficQueryService.InterfaceTraffic row) -> row.inBps() + row.outBps()).reversed())
        .toList();
  }

  public List<DiskIo> queryDiskIo(String range, String assetUid) {
    String rangeInterval = rangeInterval(range);
    return queryCanonicalDiskIo(rangeInterval, assetUid).stream()
        .sorted(Comparator.comparingDouble((DiskIo row) -> row.readBps() + row.writeBps()).reversed())
        .toList();
  }

  public List<MetricSample> queryLatestMetricSamples(String range, String assetUid) {
    String rangeInterval = rangeInterval(range);
    return queryCanonicalLatestMetricSamples(rangeInterval, assetUid).stream()
        .sorted(Comparator.comparing(MetricSample::observedAt).reversed())
        .toList();
  }

  public List<MetricSample> queryMetricSeriesSamples(String range, String bucket, String assetUid) {
    String rangeInterval = rangeInterval(range);
    String bucketInterval = bucketInterval(range, bucket);
    return queryCanonicalMetricSeriesSamples(rangeInterval, bucketInterval, assetUid).stream()
        .sorted(Comparator.comparing(MetricSample::observedAt))
        .toList();
  }

  private List<MetricSample> queryCanonicalLatestMetricSamples(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          source_type,
          metric_name,
          value,
          unit,
          interface_name,
          mount_point,
          device_name,
          labels_payload_json AS labels_json,
          latest_observed_at AS observed_at
        FROM (
          SELECT
            asset_uid,
            source_type,
            metric_name,
            JSONExtractString(labels_json, 'interface') AS interface_name,
            JSONExtractString(labels_json, 'mount_point') AS mount_point,
            JSONExtractString(labels_json, 'device') AS device_name,
            argMax(metric_value, observed_at) AS value,
            argMax(unit, observed_at) AS unit,
            argMax(labels_json, observed_at) AS labels_payload_json,
            max(observed_at) AS latest_observed_at
          FROM %s
          WHERE observed_at >= now() - %s
            AND asset_uid != ''
            %s
            %s
          GROUP BY asset_uid, source_type, metric_name, interface_name, mount_point, device_name
        )
        ORDER BY latest_observed_at DESC
        LIMIT 4000
        FORMAT JSONEachRow
        """.formatted(table, rangeInterval, assetFilter, assetMetricCanonicalFilter());
    return queryMetricSampleRows(sql);
  }

  private List<MetricSample> queryRawLatestMetricSamples(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND source_id = " + sqlString(assetUid);
    String sql = """
        SELECT
          source_id AS asset_uid,
          'AGENT' AS source_type,
          if(metric_name_payload = '', item_key, metric_name_payload) AS metric_name,
          value,
          unit,
          interface_name,
          mount_point,
          device_name,
          labels_json,
          observed_at
        FROM (
          SELECT
            source_id,
            item_key,
            argMax(JSONExtractString(payload_json, 'metric_name'), received_at) AS metric_name_payload,
            argMax(JSONExtractFloat(payload_json, 'value'), received_at) AS value,
            argMax(JSONExtractString(payload_json, 'unit'), received_at) AS unit,
            argMax(JSONExtractString(payload_json, 'interface'), received_at) AS interface_name,
            argMax(JSONExtractString(payload_json, 'mount_point'), received_at) AS mount_point,
            argMax(JSONExtractString(payload_json, 'device'), received_at) AS device_name,
            argMax(payload_json, received_at) AS labels_json,
            max(received_at) AS observed_at
          FROM (
            SELECT
              received_at,
              source_id,
              item_key,
              %s AS payload_json
            FROM %s
            WHERE received_at >= now() - %s
              AND item_kind = 'metric'
              AND source_id != ''
              %s
              %s
          )
          GROUP BY source_id, item_key
        )
        WHERE metric_name_payload != '' OR item_key != ''
        ORDER BY observed_at DESC
        LIMIT 4000
        FORMAT JSONEachRow
        """.formatted(rawPayloadSql(), table, rangeInterval, assetFilter, assetMetricRawFilter());
    return queryMetricSampleRows(sql);
  }

  private List<MetricSample> queryCanonicalMetricSeriesSamples(String rangeInterval, String bucketInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          source_type,
          metric_name,
          value,
          unit,
          interface_name,
          mount_point,
          device_name,
          labels_payload_json AS labels_json,
          bucket_at AS observed_at
        FROM (
          SELECT
            asset_uid,
            source_type,
            metric_name,
            avg(metric_value) AS value,
            any(unit) AS unit,
            JSONExtractString(labels_json, 'interface') AS interface_name,
            JSONExtractString(labels_json, 'mount_point') AS mount_point,
            JSONExtractString(labels_json, 'device') AS device_name,
            any(labels_json) AS labels_payload_json,
            toStartOfInterval(observed_at, %s) AS bucket_at
          FROM %s
          WHERE observed_at >= now() - %s
            AND asset_uid != ''
            %s
            %s
          GROUP BY bucket_at, asset_uid, source_type, metric_name, interface_name, mount_point, device_name
        )
        ORDER BY observed_at ASC
        LIMIT 8000
        FORMAT JSONEachRow
        """.formatted(bucketInterval, table, rangeInterval, assetFilter, assetMetricCanonicalFilter());
    return queryMetricSampleRows(sql);
  }

  private List<MetricSample> queryRawMetricSeriesSamples(String rangeInterval, String bucketInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND source_id = " + sqlString(assetUid);
    String sql = """
        SELECT
          source_id AS asset_uid,
          'AGENT' AS source_type,
          if(metric_name_payload = '', item_key, metric_name_payload) AS metric_name,
          avg(value) AS value,
          any(unit) AS unit,
          interface_name,
          mount_point,
          device_name,
          any(labels_json) AS labels_json,
          bucket AS observed_at
        FROM (
          SELECT
            toStartOfInterval(received_at, %s) AS bucket,
            source_id,
            item_key,
            JSONExtractString(payload_json, 'metric_name') AS metric_name_payload,
            JSONExtractFloat(payload_json, 'value') AS value,
            JSONExtractString(payload_json, 'unit') AS unit,
            JSONExtractString(payload_json, 'interface') AS interface_name,
            JSONExtractString(payload_json, 'mount_point') AS mount_point,
            JSONExtractString(payload_json, 'device') AS device_name,
            payload_json AS labels_json
          FROM (
            SELECT
              received_at,
              source_id,
              item_key,
              %s AS payload_json
            FROM %s
            WHERE received_at >= now() - %s
              AND item_kind = 'metric'
              AND source_id != ''
              %s
              %s
          )
        )
        WHERE metric_name_payload != '' OR item_key != ''
        GROUP BY bucket, source_id, item_key, metric_name_payload, interface_name, mount_point, device_name
        ORDER BY observed_at ASC
        LIMIT 8000
        FORMAT JSONEachRow
        """.formatted(bucketInterval, rawPayloadSql(), table, rangeInterval, assetFilter, assetMetricRawFilter());
    return queryMetricSampleRows(sql);
  }

  private List<TrafficQueryService.InterfaceTraffic> queryCanonicalInterfaceTraffic(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          interface_name,
          if(in_latest_bps > 0, in_latest_bps, in_counter_bps) AS in_bps,
          if(out_latest_bps > 0, out_latest_bps, out_counter_bps) AS out_bps,
          utilization_pct,
          errors,
          discards
        FROM (
          SELECT
            asset_uid,
            JSONExtractString(labels_json, 'interface') AS interface_name,
            argMaxIf(metric_value, observed_at, metric_name = 'interface.in.bps') AS in_latest_bps,
            argMaxIf(metric_value, observed_at, metric_name = 'interface.out.bps') AS out_latest_bps,
            if(
              dateDiff('second', minIf(observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes')), maxIf(observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes'))) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes')) - argMinIf(metric_value, observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes'))) * 8
                / dateDiff('second', minIf(observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes')), maxIf(observed_at, metric_name IN ('interface.in.bytes', 'host.network.rx_bytes')))),
              0
            ) AS in_counter_bps,
            if(
              dateDiff('second', minIf(observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes')), maxIf(observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes'))) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes')) - argMinIf(metric_value, observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes'))) * 8
                / dateDiff('second', minIf(observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes')), maxIf(observed_at, metric_name IN ('interface.out.bytes', 'host.network.tx_bytes')))),
              0
            ) AS out_counter_bps,
            argMaxIf(metric_value, observed_at, metric_name = 'interface.utilization.pct') AS utilization_pct,
            toUInt64(argMaxIf(metric_value, observed_at, metric_name = 'interface.in.errors'))
              + toUInt64(argMaxIf(metric_value, observed_at, metric_name = 'interface.out.errors')) AS errors,
            toUInt64(argMaxIf(metric_value, observed_at, metric_name = 'interface.in.discards'))
              + toUInt64(argMaxIf(metric_value, observed_at, metric_name = 'interface.out.discards')) AS discards
          FROM %s
          WHERE observed_at >= now() - %s
            AND JSONExtractString(labels_json, 'interface') != ''
            %s
            %s
          GROUP BY asset_uid, interface_name
        )
        WHERE in_bps > 0 OR out_bps > 0 OR errors > 0 OR discards > 0
        ORDER BY in_bps + out_bps DESC
        LIMIT 200
        FORMAT JSONEachRow
        """.formatted(table, rangeInterval, assetFilter, TrafficInterfaceFilter.sqlVisibleInterfacePredicate("JSONExtractString(labels_json, 'interface')"));
    return queryInterfaceTrafficRows(sql);
  }

  private List<TrafficQueryService.InterfaceTraffic> queryRawAgentInterfaceTraffic(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND source_id = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          interface_name,
          sumIf(bps, direction = 'in') AS in_bps,
          sumIf(bps, direction = 'out') AS out_bps,
          0 AS utilization_pct,
          0 AS errors,
          0 AS discards
        FROM (
          SELECT
            asset_uid,
            interface_name,
            direction,
            if(elapsed_seconds > 0, greatest(0, (last_value - first_value) * 8 / elapsed_seconds), 0) AS bps
          FROM (
            SELECT
              source_id AS asset_uid,
              interface_name,
              direction,
              argMin(value, received_at) AS first_value,
              argMax(value, received_at) AS last_value,
              dateDiff('second', min(received_at), max(received_at)) AS elapsed_seconds
            FROM (
              SELECT
                received_at,
                source_id,
                JSONExtractString(payload_json, 'interface') AS interface_name,
                multiIf(
                  lower(JSONExtractString(payload_json, 'direction')) IN ('ingress', 'in', 'rx')
                    OR JSONExtractString(payload_json, 'metric_name') = 'host.network.rx_bytes'
                    OR startsWith(item_key, 'host.network.rx_bytes'),
                  'in',
                  lower(JSONExtractString(payload_json, 'direction')) IN ('egress', 'out', 'tx')
                    OR JSONExtractString(payload_json, 'metric_name') = 'host.network.tx_bytes'
                    OR startsWith(item_key, 'host.network.tx_bytes'),
                  'out',
                  ''
                ) AS direction,
                JSONExtractFloat(payload_json, 'value') AS value
              FROM (
                SELECT
                  received_at,
                  source_id,
                  item_key,
                  multiIf(
                    JSONExtractRaw(event_json, 'payload') != '',
                    JSONExtractRaw(event_json, 'payload'),
                    JSONExtractRaw(JSONExtractRaw(event_json, 'additionalAttributes'), 'payload') != '',
                    JSONExtractRaw(JSONExtractRaw(event_json, 'additionalAttributes'), 'payload'),
                    JSONExtractRaw(JSONExtractString(JSONExtractRaw(event_json, 'common'), 'rawLog'), 'payload') != '',
                    JSONExtractRaw(JSONExtractString(JSONExtractRaw(event_json, 'common'), 'rawLog'), 'payload'),
                    '{}'
                  ) AS payload_json
                FROM %s
                WHERE received_at >= now() - %s
                  AND item_kind = 'metric'
                  AND source_id != ''
                  AND (item_type = 'network' OR startsWith(item_key, 'host.network.'))
                  %s
              )
            )
            WHERE interface_name != ''
              AND direction != ''
              %s
            GROUP BY source_id, interface_name, direction
          )
        )
        GROUP BY asset_uid, interface_name
        HAVING in_bps > 0 OR out_bps > 0
        ORDER BY in_bps + out_bps DESC
        LIMIT 200
        FORMAT JSONEachRow
        """.formatted(table, rangeInterval, assetFilter, TrafficInterfaceFilter.sqlVisibleInterfacePredicate("interface_name"));
    return queryInterfaceTrafficRows(sql);
  }

  private List<DiskIo> queryCanonicalDiskIo(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          device_name,
          if(read_latest_bps > 0, read_latest_bps, read_counter_bps) AS read_bps,
          if(write_latest_bps > 0, write_latest_bps, write_counter_bps) AS write_bps,
          if(read_latest_iops > 0, read_latest_iops, read_counter_iops) AS read_iops,
          if(write_latest_iops > 0, write_latest_iops, write_counter_iops) AS write_iops,
          least(100, if(io_latest_pct > 0, io_latest_pct, io_counter_pct)) AS utilization_pct
        FROM (
          SELECT
            asset_uid,
            JSONExtractString(labels_json, 'device') AS device_name,
            argMaxIf(metric_value, observed_at, metric_name = 'host.disk.read_bps') AS read_latest_bps,
            argMaxIf(metric_value, observed_at, metric_name = 'host.disk.write_bps') AS write_latest_bps,
            argMaxIf(metric_value, observed_at, metric_name = 'host.disk.read_iops') AS read_latest_iops,
            argMaxIf(metric_value, observed_at, metric_name = 'host.disk.write_iops') AS write_latest_iops,
            argMaxIf(metric_value, observed_at, metric_name = 'host.disk.io_utilization_pct') AS io_latest_pct,
            if(
              dateDiff('second', minIf(observed_at, metric_name = 'host.disk.read_bytes'), maxIf(observed_at, metric_name = 'host.disk.read_bytes')) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name = 'host.disk.read_bytes') - argMinIf(metric_value, observed_at, metric_name = 'host.disk.read_bytes'))
                / dateDiff('second', minIf(observed_at, metric_name = 'host.disk.read_bytes'), maxIf(observed_at, metric_name = 'host.disk.read_bytes'))),
              0
            ) AS read_counter_bps,
            if(
              dateDiff('second', minIf(observed_at, metric_name = 'host.disk.write_bytes'), maxIf(observed_at, metric_name = 'host.disk.write_bytes')) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name = 'host.disk.write_bytes') - argMinIf(metric_value, observed_at, metric_name = 'host.disk.write_bytes'))
                / dateDiff('second', minIf(observed_at, metric_name = 'host.disk.write_bytes'), maxIf(observed_at, metric_name = 'host.disk.write_bytes'))),
              0
            ) AS write_counter_bps,
            if(
              dateDiff('second', minIf(observed_at, metric_name = 'host.disk.read_ops'), maxIf(observed_at, metric_name = 'host.disk.read_ops')) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name = 'host.disk.read_ops') - argMinIf(metric_value, observed_at, metric_name = 'host.disk.read_ops'))
                / dateDiff('second', minIf(observed_at, metric_name = 'host.disk.read_ops'), maxIf(observed_at, metric_name = 'host.disk.read_ops'))),
              0
            ) AS read_counter_iops,
            if(
              dateDiff('second', minIf(observed_at, metric_name = 'host.disk.write_ops'), maxIf(observed_at, metric_name = 'host.disk.write_ops')) > 0,
              greatest(0, (argMaxIf(metric_value, observed_at, metric_name = 'host.disk.write_ops') - argMinIf(metric_value, observed_at, metric_name = 'host.disk.write_ops'))
                / dateDiff('second', minIf(observed_at, metric_name = 'host.disk.write_ops'), maxIf(observed_at, metric_name = 'host.disk.write_ops'))),
              0
            ) AS write_counter_iops,
            if(
              dateDiff('second', minIf(observed_at, metric_name = 'host.disk.io_time_ms'), maxIf(observed_at, metric_name = 'host.disk.io_time_ms')) > 0,
              least(100, greatest(0, (argMaxIf(metric_value, observed_at, metric_name = 'host.disk.io_time_ms') - argMinIf(metric_value, observed_at, metric_name = 'host.disk.io_time_ms'))
                / dateDiff('second', minIf(observed_at, metric_name = 'host.disk.io_time_ms'), maxIf(observed_at, metric_name = 'host.disk.io_time_ms')) / 10)),
              0
            ) AS io_counter_pct
          FROM %s
          WHERE observed_at >= now() - %s
            AND JSONExtractString(labels_json, 'device') != ''
            %s
          GROUP BY asset_uid, device_name
        )
        WHERE read_bps > 0 OR write_bps > 0 OR read_iops > 0 OR write_iops > 0 OR utilization_pct > 0
        ORDER BY read_bps + write_bps DESC
        LIMIT 500
        FORMAT JSONEachRow
        """.formatted(table, rangeInterval, assetFilter);
    return queryDiskIoRows(sql);
  }

  private List<DiskIo> queryRawAgentDiskIo(String rangeInterval, String assetUid) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND source_id = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          device_name,
          if(read_latest_bps > 0, read_latest_bps, read_counter_bps) AS read_bps,
          if(write_latest_bps > 0, write_latest_bps, write_counter_bps) AS write_bps,
          if(read_latest_iops > 0, read_latest_iops, read_counter_iops) AS read_iops,
          if(write_latest_iops > 0, write_latest_iops, write_counter_iops) AS write_iops,
          least(100, if(io_latest_pct > 0, io_latest_pct, io_counter_pct)) AS utilization_pct
        FROM (
          SELECT
            asset_uid,
            device_name,
            argMaxIf(value, received_at, metric_name = 'host.disk.read_bps') AS read_latest_bps,
            argMaxIf(value, received_at, metric_name = 'host.disk.write_bps') AS write_latest_bps,
            argMaxIf(value, received_at, metric_name = 'host.disk.read_iops') AS read_latest_iops,
            argMaxIf(value, received_at, metric_name = 'host.disk.write_iops') AS write_latest_iops,
            argMaxIf(value, received_at, metric_name = 'host.disk.io_utilization_pct') AS io_latest_pct,
            if(
              dateDiff('second', minIf(received_at, metric_name = 'host.disk.read_bytes'), maxIf(received_at, metric_name = 'host.disk.read_bytes')) > 0,
              greatest(0, (argMaxIf(value, received_at, metric_name = 'host.disk.read_bytes') - argMinIf(value, received_at, metric_name = 'host.disk.read_bytes'))
                / dateDiff('second', minIf(received_at, metric_name = 'host.disk.read_bytes'), maxIf(received_at, metric_name = 'host.disk.read_bytes'))),
              0
            ) AS read_counter_bps,
            if(
              dateDiff('second', minIf(received_at, metric_name = 'host.disk.write_bytes'), maxIf(received_at, metric_name = 'host.disk.write_bytes')) > 0,
              greatest(0, (argMaxIf(value, received_at, metric_name = 'host.disk.write_bytes') - argMinIf(value, received_at, metric_name = 'host.disk.write_bytes'))
                / dateDiff('second', minIf(received_at, metric_name = 'host.disk.write_bytes'), maxIf(received_at, metric_name = 'host.disk.write_bytes'))),
              0
            ) AS write_counter_bps,
            if(
              dateDiff('second', minIf(received_at, metric_name = 'host.disk.read_ops'), maxIf(received_at, metric_name = 'host.disk.read_ops')) > 0,
              greatest(0, (argMaxIf(value, received_at, metric_name = 'host.disk.read_ops') - argMinIf(value, received_at, metric_name = 'host.disk.read_ops'))
                / dateDiff('second', minIf(received_at, metric_name = 'host.disk.read_ops'), maxIf(received_at, metric_name = 'host.disk.read_ops'))),
              0
            ) AS read_counter_iops,
            if(
              dateDiff('second', minIf(received_at, metric_name = 'host.disk.write_ops'), maxIf(received_at, metric_name = 'host.disk.write_ops')) > 0,
              greatest(0, (argMaxIf(value, received_at, metric_name = 'host.disk.write_ops') - argMinIf(value, received_at, metric_name = 'host.disk.write_ops'))
                / dateDiff('second', minIf(received_at, metric_name = 'host.disk.write_ops'), maxIf(received_at, metric_name = 'host.disk.write_ops'))),
              0
            ) AS write_counter_iops,
            if(
              dateDiff('second', minIf(received_at, metric_name = 'host.disk.io_time_ms'), maxIf(received_at, metric_name = 'host.disk.io_time_ms')) > 0,
              least(100, greatest(0, (argMaxIf(value, received_at, metric_name = 'host.disk.io_time_ms') - argMinIf(value, received_at, metric_name = 'host.disk.io_time_ms'))
                / dateDiff('second', minIf(received_at, metric_name = 'host.disk.io_time_ms'), maxIf(received_at, metric_name = 'host.disk.io_time_ms')) / 10)),
              0
            ) AS io_counter_pct
          FROM (
            SELECT
              received_at,
              source_id AS asset_uid,
              if(JSONExtractString(payload_json, 'metric_name') = '', item_key, JSONExtractString(payload_json, 'metric_name')) AS metric_name,
              JSONExtractString(payload_json, 'device') AS device_name,
              JSONExtractFloat(payload_json, 'value') AS value
            FROM (
              SELECT
                received_at,
                source_id,
                item_key,
                %s AS payload_json
              FROM %s
              WHERE received_at >= now() - %s
                AND item_kind = 'metric'
                AND source_id != ''
                AND startsWith(item_key, 'host.disk.')
                %s
            )
          )
          WHERE device_name != ''
          GROUP BY asset_uid, device_name
        )
        WHERE read_bps > 0 OR write_bps > 0 OR read_iops > 0 OR write_iops > 0 OR utilization_pct > 0
        ORDER BY read_bps + write_bps DESC
        LIMIT 500
        FORMAT JSONEachRow
        """.formatted(rawPayloadSql(), table, rangeInterval, assetFilter);
    return queryDiskIoRows(sql);
  }

  private List<DiskIo> queryDiskIoRows(String sql) {
    return queryJsonRows(sql).stream()
        .map(row -> new DiskIo(
            stringValue(row.get("asset_uid")),
            stringValue(row.get("device_name")),
            doubleValue(row.get("read_bps")),
            doubleValue(row.get("write_bps")),
            doubleValue(row.get("read_iops")),
            doubleValue(row.get("write_iops")),
            doubleValue(row.get("utilization_pct"))))
        .filter(row -> row.assetUid() != null && !row.assetUid().isBlank())
        .filter(row -> row.deviceName() != null && !row.deviceName().isBlank())
        .toList();
  }

  private List<TrafficQueryService.InterfaceTraffic> queryInterfaceTrafficRows(String sql) {
    return queryJsonRows(sql).stream()
        .map(row -> new TrafficQueryService.InterfaceTraffic(
            stringValue(row.get("asset_uid")),
            stringValue(row.get("interface_name")),
            doubleValue(row.get("in_bps")),
            doubleValue(row.get("out_bps")),
            doubleValue(row.get("utilization_pct")),
            longValue(row.get("errors")),
            longValue(row.get("discards")),
            "up"))
        .filter(row -> row.assetUid() != null && !row.assetUid().isBlank())
        .filter(row -> row.interfaceName() != null && !row.interfaceName().isBlank())
        .filter(row -> TrafficInterfaceFilter.isVisibleTrafficInterface(row.interfaceName()))
        .toList();
  }

  private List<MetricSample> queryMetricSampleRows(String sql) {
    return queryJsonRows(sql).stream()
        .map(row -> new MetricSample(
            stringValue(row.get("asset_uid")),
            stringValue(row.get("source_type")),
            stringValue(row.get("metric_name")),
            doubleValue(row.get("value")),
            stringValue(row.get("unit")),
            parseInstant(stringValue(row.get("observed_at"))),
            stringValue(row.get("interface_name")),
            stringValue(row.get("mount_point")),
            stringValue(row.get("device_name")),
            stringValue(row.get("labels_json"))))
        .filter(row -> row.assetUid() != null && !row.assetUid().isBlank())
        .filter(row -> row.metricName() != null && !row.metricName().isBlank())
        .toList();
  }

  private static String rawPayloadSql() {
    return """
        multiIf(
          JSONExtractRaw(event_json, 'payload') != '',
          JSONExtractRaw(event_json, 'payload'),
          JSONExtractRaw(JSONExtractRaw(event_json, 'additionalAttributes'), 'payload') != '',
          JSONExtractRaw(JSONExtractRaw(event_json, 'additionalAttributes'), 'payload'),
          JSONExtractRaw(JSONExtractString(JSONExtractRaw(event_json, 'common'), 'rawLog'), 'payload') != '',
          JSONExtractRaw(JSONExtractString(JSONExtractRaw(event_json, 'common'), 'rawLog'), 'payload'),
          '{}'
        )
        """;
  }

  private static String assetMetricCanonicalFilter() {
    return """
        AND (
          metric_name IN (
            'cpu.usage',
            'host.cpu.usage_percent',
            'host.cpu.count',
            'agent.host.cpu_count',
            'memory.usage',
            'host.memory.total_bytes',
            'host.memory.available_bytes',
            'disk.usage',
            'host.load.1',
            'host.load.5',
            'host.load.15',
            'host.load.normalized_1'
          )
          OR startsWith(metric_name, 'host.disk.')
          OR startsWith(metric_name, 'host.network.')
          OR startsWith(metric_name, 'interface.')
          OR startsWith(metric_name, 'system.')
        )
        """;
  }

  private static String assetMetricRawFilter() {
    return """
        AND (
          item_key IN (
            'cpu.usage',
            'host.cpu.usage_percent',
            'host.cpu.count',
            'agent.host.cpu_count',
            'memory.usage',
            'host.memory.total_bytes',
            'host.memory.available_bytes',
            'disk.usage',
            'host.load.1',
            'host.load.5',
            'host.load.15',
            'host.load.normalized_1'
          )
          OR startsWith(item_key, 'host.disk.')
          OR startsWith(item_key, 'host.network.')
          OR startsWith(item_key, 'interface.')
          OR startsWith(item_key, 'system.')
        )
        """;
  }

  public List<ObservedAgentSource> fetchObservedAgentSources() {
    String table = tableReference(properties.clickhouse().database(), "manager_state_snapshots");
    List<Map<String, Object>> rows = queryJsonRows("""
        SELECT
          asset_uid,
          argMax(source_id, observed_at) AS source_id,
          max(observed_at) AS last_seen_at,
          argMax(state_json, observed_at) AS identity_json
        FROM %s
        WHERE asset_uid != ''
          AND source_type = 'AGENT'
          AND state_type = 'identity'
        GROUP BY asset_uid
        ORDER BY last_seen_at DESC
        LIMIT 500
        FORMAT JSONEachRow
        """.formatted(table));
    return rows.stream()
        .map(row -> new ObservedAgentSource(
            stringValue(row.get("source_id")),
            parseInstant(stringValue(row.get("last_seen_at"))),
            stringValue(row.get("identity_json"))))
        .filter(source -> source.sourceId() != null && !source.sourceId().isBlank())
        .toList();
  }

  public Map<String, Object> queryAgentDashboard(String assetUid) {
    String metricTable = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String stateTable = tableReference(properties.clickhouse().database(), "manager_state_snapshots");
    String eventTable = tableReference(properties.clickhouse().database(), "manager_events");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    int stateTypeLimit = assetUid == null || assetUid.isBlank() ? 50 : 500;
    int packageLimit = assetUid == null || assetUid.isBlank() ? 50 : 200;
    int eventLimit = assetUid == null || assetUid.isBlank() ? 20 : 100;
    List<Map<String, Object>> agents = queryJsonRows("""
        SELECT asset_uid, argMax(source_id, observed_at) AS source_id, max(observed_at) AS last_seen_at
        FROM (
          SELECT asset_uid, source_id, observed_at FROM %s WHERE asset_uid != '' %s %s
          UNION ALL
          SELECT asset_uid, source_id, observed_at FROM %s WHERE asset_uid != '' %s %s
          UNION ALL
          SELECT asset_uid, source_id, observed_at FROM %s WHERE asset_uid != '' %s %s
        )
        GROUP BY asset_uid
        ORDER BY last_seen_at DESC
        LIMIT 50
        FORMAT JSONEachRow
        """.formatted(
        metricTable, RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter,
        stateTable, RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter,
        eventTable, RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter));
    Map<String, Object> heartbeat = agentHeartbeat(agents);
    List<Map<String, Object>> collectors = queryJsonRows("""
        SELECT name, count() AS sample_count, max(observed_at) AS last_seen_at
        FROM (
          SELECT
            if(JSONExtractString(labels_json, 'collector') = '', source_type, JSONExtractString(labels_json, 'collector')) AS name,
            observed_at
          FROM %s
          WHERE asset_uid != ''
            %s
            %s
        )
        GROUP BY name
        ORDER BY sample_count DESC, name
        LIMIT 20
        FORMAT JSONEachRow
        """.formatted(metricTable, RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter)).stream()
        .map(ClickHouseClient::normalizeDashboardKeys)
        .toList();
    List<Map<String, Object>> metrics = queryCanonicalLatestMetricSamples("INTERVAL 1 HOUR", assetUid).stream()
        .limit(24)
        .map(ClickHouseClient::canonicalMetricRow)
        .toList();
    List<Map<String, Object>> sockets = queryDashboardStateRows(stateTable, assetFilter, "socket", stateTypeLimit);
    List<Map<String, Object>> services = queryDashboardStateRows(stateTable, assetFilter, "service", stateTypeLimit);
    List<Map<String, Object>> firewalls = queryDashboardStateRows(stateTable, assetFilter, "firewall", stateTypeLimit);
    List<Map<String, Object>> processes = queryDashboardStateRows(stateTable, assetFilter, "process", stateTypeLimit);
    List<Map<String, Object>> packages = queryDashboardStateRows(stateTable, assetFilter, "package", packageLimit);
    List<Map<String, Object>> events = queryJsonRows("""
        SELECT
          asset_uid,
          source_id,
          event_type,
          severity,
          event_json,
          latest_observed_at AS observed_at
        FROM (
          SELECT
            asset_uid,
            source_id,
            event_type,
            argMax(severity, observed_at) AS severity,
            argMax(event_json, observed_at) AS event_json,
            max(observed_at) AS latest_observed_at
          FROM %s
          WHERE asset_uid != ''
            %s
            %s
          GROUP BY asset_uid, source_id, event_type
        )
        ORDER BY latest_observed_at DESC
        LIMIT %d
        FORMAT JSONEachRow
        """.formatted(eventTable, RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter, eventLimit)).stream()
        .map(ClickHouseClient::canonicalEventRow)
        .toList();
    return Map.of(
        "heartbeat", normalizeDashboardKeys(heartbeat),
        "securityPosture", securityPosture(sockets, services, firewalls),
        "agents", agents.stream().map(ClickHouseClient::normalizeDashboardKeys).toList(),
        "collectors", collectors.stream().map(ClickHouseClient::normalizeDashboardKeys).toList(),
        "states", Map.of(
            "sockets", sockets,
            "services", services,
            "firewalls", firewalls,
            "processes", processes,
            "packages", packages),
        "resources", Map.of("metrics", metrics.stream().map(ClickHouseClient::normalizeDashboardKeys).toList()),
        "events", events.stream().map(ClickHouseClient::normalizeDashboardKeys).toList());
  }

  private List<Map<String, Object>> queryDashboardStateRows(
      String stateTable,
      String assetFilter,
      String stateType,
      int limit) {
    return queryJsonRows("""
        SELECT
          asset_uid,
          source_id,
          state_type,
          state_key,
          state_json,
          latest_observed_at AS observed_at
        FROM (
          SELECT
            asset_uid,
            source_id,
            state_type,
            state_key,
            argMax(state_json, observed_at) AS state_json,
            max(observed_at) AS latest_observed_at
          FROM %s
          WHERE asset_uid != ''
            AND state_type = %s
            %s
            %s
          GROUP BY asset_uid, source_id, state_type, state_key
        )
        ORDER BY latest_observed_at DESC
        LIMIT %d
        FORMAT JSONEachRow
        """.formatted(stateTable, sqlString(stateType), RECENT_TRANSFORMED_DASHBOARD_FILTER, assetFilter, limit)).stream()
        .map(ClickHouseClient::canonicalStateRow)
        .toList();
  }

  public List<Map<String, Object>> queryAgentLogEvents(String range, String severity, String assetUid, int limit) {
    String table = tableReference(properties.clickhouse().database(), "manager_events");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          source_id,
          event_type,
          severity,
          event_json,
          observed_at
        FROM %s
        WHERE observed_at >= now() - %s
          AND asset_uid != ''
          AND source_type = 'AGENT'
          AND JSONExtractString(event_json, 'message') != ''
          %s
          %s
        ORDER BY observed_at DESC
        LIMIT %d
        FORMAT JSONEachRow
        """.formatted(table, rangeInterval(range), assetFilter, agentLogSeverityFilter(severity), agentLogLimit(limit));
    return queryJsonRows(sql).stream()
        .map(ClickHouseClient::canonicalEventRow)
        .map(ClickHouseClient::normalizeDashboardKeys)
        .toList();
  }

  public Map<String, Object> querySnmpDashboard(Long targetId) {
    return Map.of(
        "polls", Map.of("success", 0, "failure", 0),
        "targets", List.of(),
        "interfaces", queryInterfaceTraffic("1h", null));
  }

  private void insertMetrics(List<CanonicalTelemetryRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    String sql = "INSERT INTO " + tableReference(properties.clickhouse().database(), "manager_metric_samples")
        + " (observed_at, asset_uid, source_type, source_id, metric_name, metric_value, unit, labels_json) FORMAT JSONEachRow";
    post(sql, jsonEachRow(records, record -> {
      Map<String, Object> row = baseRow(record);
      row.put("metric_name", record.metricName());
      row.put("metric_value", record.metricValue());
      row.put("unit", record.unit());
      row.put("labels_json", record.labelsJson());
      return row;
    })).toBodilessEntity();
  }

  private void insertStates(List<CanonicalTelemetryRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    String sql = "INSERT INTO " + tableReference(properties.clickhouse().database(), "manager_state_snapshots")
        + " (observed_at, asset_uid, source_type, source_id, state_type, state_key, state_json) FORMAT JSONEachRow";
    post(sql, jsonEachRow(records, record -> {
      Map<String, Object> row = baseRow(record);
      row.put("state_type", record.stateType());
      row.put("state_key", record.stateKey());
      row.put("state_json", record.stateJson());
      return row;
    })).toBodilessEntity();
  }

  private void insertEvents(List<CanonicalTelemetryRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    String sql = "INSERT INTO " + tableReference(properties.clickhouse().database(), "manager_events")
        + " (observed_at, asset_uid, source_type, source_id, event_type, severity, event_json) FORMAT JSONEachRow";
    post(sql, jsonEachRow(records, record -> {
      Map<String, Object> row = baseRow(record);
      row.put("event_type", record.eventType());
      row.put("severity", record.severity());
      row.put("event_json", record.eventJson());
      return row;
    })).toBodilessEntity();
  }

  private static Map<String, Object> baseRow(CanonicalTelemetryRecord record) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("observed_at", formatDateTime64(record.observedAt()));
    row.put("asset_uid", record.assetUid());
    row.put("source_type", record.sourceType());
    row.put("source_id", record.sourceId());
    return row;
  }

  static String formatDateTime64(Instant instant) {
    return CLICKHOUSE_DATE_TIME_OUT.format(instant);
  }

  private static String jsonEachRow(List<CanonicalTelemetryRecord> records, RowMapper mapper) {
    StringBuilder body = new StringBuilder();
    for (CanonicalTelemetryRecord record : records) {
      try {
        body.append(OBJECT_MAPPER.writeValueAsString(mapper.map(record))).append('\n');
      } catch (JsonProcessingException exception) {
        throw new IllegalArgumentException("failed to serialize canonical telemetry row", exception);
      }
    }
    return body.toString();
  }

  private RestClient.ResponseSpec post(String sql, String body) {
    RestClient.RequestBodySpec request = restClient.post()
        .uri(queryEndpoint(sql))
        .header("Content-Type", "text/plain; charset=utf-8");
    authorizationHeader().forEach(value -> request.header(HttpHeaders.AUTHORIZATION, value));
    return request.body(body).retrieve();
  }

  private List<Map<String, Object>> queryJsonRows(String sql) {
    String body = post(sql, "").body(String.class);
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String line : body.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        rows.add(OBJECT_MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
        }));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("invalid ClickHouse dashboard row", exception);
      }
    }
    return rows;
  }

  private URI endpoint() {
    return URI.create(properties.clickhouse().endpointUrl());
  }

  private URI queryEndpoint(String sql) {
    String endpoint = properties.clickhouse().endpointUrl();
    String separator = endpoint.contains("?") ? "&" : "?";
    return URI.create(endpoint + separator + "query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
  }

  private List<String> authorizationHeader() {
    String username = properties.clickhouse().username();
    String password = properties.clickhouse().password();
    if (username == null || username.isBlank()) {
      return List.of();
    }
    String token = Base64.getEncoder().encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    return List.of("Basic " + token);
  }

  private static String cursorWhere(String cursorValue) {
    try {
      JsonNode cursor = OBJECT_MAPPER.readTree(cursorValue);
      String observedAt = sqlString(cursor.path("received_at").asText());
      String sourceId = sqlString(cursor.path("source_id").asText());
      String itemKey = sqlString(cursor.path("item_key").asText(""));
      return "(received_at, source_id, ifNull(item_key, '')) > (parseDateTime64BestEffort(" + observedAt + "), "
          + sourceId + ", " + itemKey + ")";
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid telemetry cursor", exception);
    }
  }

  private static String tableReference(String database, String table) {
    validateIdentifier(database, "database");
    validateIdentifier(table, "table");
    return database + "." + table;
  }

  private static void validateIdentifier(String value, String fieldName) {
    if (value == null || !value.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("ClickHouse " + fieldName + " must contain only letters, numbers, and underscore");
    }
  }

  private static String sqlString(String value) {
    return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
  }

  static String rangeInterval(String range) {
    return switch (range == null ? "" : range.trim().toLowerCase()) {
      case "15m" -> "INTERVAL 15 MINUTE";
      case "30m" -> "INTERVAL 30 MINUTE";
      case "6h" -> "INTERVAL 6 HOUR";
      case "24h" -> "INTERVAL 24 HOUR";
      default -> "INTERVAL 1 HOUR";
    };
  }

  static String bucketInterval(String range, String bucket) {
    String requested = bucket == null ? "" : bucket.trim().toLowerCase();
    if (!requested.isBlank() && !"auto".equals(requested)) {
      return switch (requested) {
        case "1m" -> "INTERVAL 1 MINUTE";
        case "5m" -> "INTERVAL 5 MINUTE";
        case "15m" -> "INTERVAL 15 MINUTE";
        case "1h" -> "INTERVAL 1 HOUR";
        default -> "INTERVAL 5 MINUTE";
      };
    }
    return switch (range == null ? "" : range.trim().toLowerCase()) {
      case "15m", "30m" -> "INTERVAL 1 MINUTE";
      case "6h" -> "INTERVAL 15 MINUTE";
      case "24h" -> "INTERVAL 1 HOUR";
      default -> "INTERVAL 5 MINUTE";
    };
  }

  static int agentLogLimit(int limit) {
    if (limit <= 0) {
      return 200;
    }
    return Math.min(limit, 1000);
  }

  static String agentLogSeverityFilter(String severity) {
    String normalized = severity == null ? "" : severity.trim().toUpperCase();
    return switch (normalized) {
      case "INFO", "WARNING", "ERROR", "CRITICAL" -> "AND upper(ifNull(severity, '')) = " + sqlString(normalized);
      default -> "";
    };
  }

  private static String interfaceKey(TrafficQueryService.InterfaceTraffic row) {
    return row.assetUid() + "::" + row.interfaceName();
  }

  private static String diskIoKey(DiskIo row) {
    return row.assetUid() + "::" + row.deviceName();
  }

  private static String metricSampleKey(MetricSample sample) {
    return sample.assetUid() + "::" + sample.sourceType() + "::" + sample.metricName() + "::"
        + (sample.interfaceName() == null ? "" : sample.interfaceName()) + "::"
        + (sample.mountPoint() == null ? "" : sample.mountPoint()) + "::"
        + (sample.deviceName() == null ? "" : sample.deviceName());
  }

  private static String metricSeriesKey(MetricSample sample) {
    return metricSampleKey(sample) + "::" + sample.observedAt();
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return LocalDateTime.parse(value, CLICKHOUSE_DATE_TIME).toInstant(ZoneOffset.UTC);
    }
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Map<String, Object> canonicalMetricRow(MetricSample sample) {
    Map<String, Object> metric = new LinkedHashMap<>();
    metric.put("assetUid", sample.assetUid());
    metric.put("sourceType", sample.sourceType());
    metric.put("metricName", sample.metricName());
    metric.put("value", sample.value());
    metric.put("unit", sample.unit());
    metric.put("observedAt", sample.observedAt().toString());
    if (sample.interfaceName() != null && !sample.interfaceName().isBlank()) {
      metric.put("interfaceName", sample.interfaceName());
    }
    if (sample.mountPoint() != null && !sample.mountPoint().isBlank()) {
      metric.put("mountPoint", sample.mountPoint());
    }
    if (sample.deviceName() != null && !sample.deviceName().isBlank()) {
      metric.put("deviceName", sample.deviceName());
    }
    copyPayloadFields(metric, jsonObject(sample.labelsJson()));
    return metric;
  }

  private static Map<String, Object> canonicalStateRow(Map<String, Object> row) {
    Map<String, Object> state = normalizeDashboardKeys(row);
    state.remove("stateJson");
    copyPayloadFields(state, jsonObject(row.get("state_json")));
    return state;
  }

  private static Map<String, Object> canonicalEventRow(Map<String, Object> row) {
    Map<String, Object> event = normalizeDashboardKeys(row);
    event.remove("eventJson");
    JsonNode payload = jsonObject(row.get("event_json"));
    copyPayloadFields(event, payload);
    copyText(event, payload, "event_type", "eventType");
    copyText(event, payload, "severity", "severity");
    copyText(event, payload, "message", "message");
    copyText(event, payload, "source_name", "sourceName");
    copyText(event, payload, "outcome", "outcome");
    return event;
  }

  private static JsonNode jsonObject(Object json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(stringValue(json));
      return node == null || node.isNull() || node.isMissingNode() ? OBJECT_MAPPER.createObjectNode() : node;
    } catch (Exception ignored) {
      return OBJECT_MAPPER.createObjectNode();
    }
  }

  private static Map<String, Object> rawMetricRow(Map<String, Object> row) {
    Map<String, Object> metric = normalizeDashboardKeys(row);
    metric.remove("eventJson");
    JsonNode payload = rawPayload(row.get("event_json"));
    String metricName = textOr(payload, "metric_name", stringValue(metric.get("metricName")));
    metric.put("metricName", metricName);
    if (payload.hasNonNull("value")) {
      metric.put("value", payload.path("value").asDouble());
    }
    String unit = nullableText(payload, "unit");
    if (unit != null) {
      metric.put("unit", unit);
    }
    return metric;
  }

  static Map<String, Object> rawStateRow(Map<String, Object> row) {
    Map<String, Object> state = normalizeDashboardKeys(row);
    state.remove("eventJson");
    copyPayloadFields(state, rawPayload(row.get("event_json")));
    return state;
  }

  private static Map<String, Object> rawEventRow(Map<String, Object> row) {
    Map<String, Object> event = normalizeDashboardKeys(row);
    event.remove("eventJson");
    JsonNode payload = rawPayload(row.get("event_json"));
    copyText(event, payload, "event_type", "eventType");
    copyText(event, payload, "severity", "severity");
    copyText(event, payload, "message", "message");
    copyText(event, payload, "source_name", "sourceName");
    copyText(event, payload, "outcome", "outcome");
    try {
      JsonNode root = OBJECT_MAPPER.readTree(stringValue(row.get("event_json")));
      String severity = nullableText(root.path("common"), "severity");
      if (severity != null) {
        event.put("severity", severity);
      }
    } catch (JsonProcessingException ignored) {
      // Keep the summary row even when the raw event payload cannot be parsed.
    }
    return event;
  }

  private static JsonNode rawPayload(Object eventJson) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(stringValue(eventJson));
      JsonNode payload = root.path("payload");
      if (!payload.isMissingNode() && !payload.isNull()) {
        return payload;
      }
      JsonNode nestedPayload = root.path("additionalAttributes").path("payload");
      if (!nestedPayload.isMissingNode() && !nestedPayload.isNull()) {
        return nestedPayload;
      }
      JsonNode rawLog = root.path("common").path("rawLog");
      if (rawLog.isTextual()) {
        return OBJECT_MAPPER.readTree(rawLog.asText()).path("payload");
      }
    } catch (JsonProcessingException ignored) {
      // Fall through to empty payload.
    }
    return OBJECT_MAPPER.createObjectNode();
  }

  private static void copyPayloadFields(Map<String, Object> target, JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      return;
    }
    payload.fields().forEachRemaining(field -> {
      if (!field.getValue().isNull()) {
        String key = field.getKey().startsWith("payload_")
            ? field.getKey().substring("payload_".length())
            : field.getKey();
        target.put(snakeToCamel(key), OBJECT_MAPPER.convertValue(field.getValue(), Object.class));
      }
    });
  }

  private static void copyText(Map<String, Object> target, JsonNode payload, String sourceField, String targetField) {
    String value = nullableText(payload, sourceField);
    if (value != null && !value.isBlank()) {
      target.put(targetField, value);
    }
  }

  private static Map<String, Object> securityPosture(
      List<Map<String, Object>> sockets,
      List<Map<String, Object>> services,
      List<Map<String, Object>> firewalls) {
    return Map.of(
        "exposedPorts", sockets.stream().filter(ClickHouseClient::isListeningSocket).count(),
        "failedServices", services.stream().filter(ClickHouseClient::isProblemService).count(),
        "firewallDisabled", firewalls.stream().filter(ClickHouseClient::isFirewallDisabled).count());
  }

  private static boolean isListeningSocket(Map<String, Object> row) {
    String direction = stringValue(row.get("direction"));
    String state = stringValue(row.get("state"));
    return "listening".equalsIgnoreCase(direction) || "listen".equalsIgnoreCase(state);
  }

  private static boolean isProblemService(Map<String, Object> row) {
    String status = stringValue(row.get("status"));
    return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
  }

  private static boolean isFirewallDisabled(Map<String, Object> row) {
    Object enabled = row.get("enabled");
    if (enabled instanceof Boolean bool) {
      return !bool;
    }
    return "false".equalsIgnoreCase(stringValue(enabled));
  }

  private static Map<String, Object> agentHeartbeat(List<Map<String, Object>> agents) {
    Instant staleCutoff = Instant.now().minus(Duration.ofMinutes(5));
    int healthy = 0;
    int stale = 0;
    Instant lastSeenAt = null;
    for (Map<String, Object> agent : agents) {
      Object value = agent.get("last_seen_at");
      if (value == null) {
        value = agent.get("lastSeenAt");
      }
      if (value == null) {
        stale++;
        continue;
      }
      Instant seen = parseInstant(String.valueOf(value));
      if (seen.isAfter(staleCutoff)) {
        healthy++;
      } else {
        stale++;
      }
      if (lastSeenAt == null || seen.isAfter(lastSeenAt)) {
        lastSeenAt = seen;
      }
    }
    Map<String, Object> heartbeat = new LinkedHashMap<>();
    heartbeat.put("healthy", healthy);
    heartbeat.put("stale", stale);
    if (lastSeenAt != null) {
      heartbeat.put("last_seen_at", lastSeenAt.toString());
    }
    return heartbeat;
  }

  private static Map<String, Object> normalizeDashboardKeys(Map<String, Object> row) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    row.forEach((key, value) -> normalized.put(snakeToCamel(key), normalizeDashboardValue(key, value)));
    return normalized;
  }

  private static Object normalizeDashboardValue(String key, Object value) {
    if (value instanceof String text && key.endsWith("_at") && !text.isBlank()) {
      return parseInstant(text).toString();
    }
    return value;
  }

  private static String textOr(JsonNode node, String field, String fallback) {
    String value = nullableText(node, field);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static double doubleValue(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static String snakeToCamel(String value) {
    StringBuilder result = new StringBuilder();
    boolean upperNext = false;
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      if (ch == '_') {
        upperNext = true;
      } else if (upperNext) {
        result.append(Character.toUpperCase(ch));
        upperNext = false;
      } else {
        result.append(ch);
      }
    }
    return result.toString();
  }

  @FunctionalInterface
  private interface RowMapper {
    Map<String, Object> map(CanonicalTelemetryRecord record);
  }

  public record RawTelemetryRow(
      Instant receivedAt,
      String sourceId,
      String itemKind,
      String itemType,
      String itemKey,
      String eventJson) {
  }

  public record ObservedAgentSource(
      String sourceId,
      Instant lastSeenAt,
      String identityJson) {
  }

  public record DiskIo(
      String assetUid,
      String deviceName,
      double readBps,
      double writeBps,
      double readIops,
      double writeIops,
      double utilizationPct) {
  }

  public record MetricSample(
      String assetUid,
      String sourceType,
      String metricName,
      double value,
      String unit,
      Instant observedAt,
      String interfaceName,
      String mountPoint,
      String deviceName,
      String labelsJson) {
  }
}
