package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
  }

  public List<RawTelemetryRow> fetchRawTelemetryRows(String cursorValue, int limit) {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String where = cursorValue == null || cursorValue.isBlank() ? "" : "where " + cursorWhere(cursorValue);
    String sql = """
        SELECT received_at, source_id, item_kind, item_type, item_key, event_json
        FROM %s
        %s
        ORDER BY received_at, source_id, item_key
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
    String table = tableReference(properties.clickhouse().database(), "manager_metric_samples");
    String assetFilter = assetUid == null || assetUid.isBlank() ? "" : "AND asset_uid = " + sqlString(assetUid);
    String sql = """
        SELECT
          asset_uid,
          JSONExtractString(labels_json, 'interface') AS interface_name,
          argMaxIf(metric_value, observed_at, metric_name IN ('interface.in.bps', 'interface.in.bytes')) AS in_bps,
          argMaxIf(metric_value, observed_at, metric_name IN ('interface.out.bps', 'interface.out.bytes')) AS out_bps,
          argMaxIf(metric_value, observed_at, metric_name = 'interface.utilization.pct') AS utilization_pct,
          toUInt64(argMaxIf(metric_value, observed_at, metric_name IN ('interface.in.errors', 'interface.out.errors'))) AS errors,
          toUInt64(argMaxIf(metric_value, observed_at, metric_name IN ('interface.in.discards', 'interface.out.discards'))) AS discards
        FROM %s
        WHERE observed_at >= now() - INTERVAL 1 HOUR
          AND JSONExtractString(labels_json, 'interface') != ''
          %s
        GROUP BY asset_uid, interface_name
        FORMAT JSONEachRow
        """.formatted(table, assetFilter);
    String body = post(sql, "").body(String.class);
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<TrafficQueryService.InterfaceTraffic> result = new ArrayList<>();
    for (String line : body.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        JsonNode node = OBJECT_MAPPER.readTree(line);
        result.add(new TrafficQueryService.InterfaceTraffic(
            node.path("asset_uid").asText(),
            node.path("interface_name").asText(),
            node.path("in_bps").asDouble(),
            node.path("out_bps").asDouble(),
            node.path("utilization_pct").asDouble(),
            node.path("errors").asLong(),
            node.path("discards").asLong(),
            "up"));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("invalid ClickHouse traffic row", exception);
      }
    }
    return result;
  }

  public Map<String, Object> queryAgentDashboard(Long assetId) {
    return Map.of(
        "heartbeat", Map.of("healthy", 0, "stale", 0),
        "collectors", List.of(),
        "resources", Map.of(),
        "events", List.of());
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
}
