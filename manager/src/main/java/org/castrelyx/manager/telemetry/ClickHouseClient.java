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

  public List<ObservedAgentSource> fetchObservedAgentSources() {
    String table = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    List<Map<String, Object>> rows = queryJsonRows("""
        SELECT
          source_id,
          max(received_at) AS last_seen_at,
          argMaxIf(event_json, received_at, item_kind = 'asset' OR item_type = 'identity') AS identity_json
        FROM %s
        GROUP BY source_id
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
    String rawTable = tableReference(properties.clickhouse().database(), properties.clickhouse().rawTable());
    String sourceFilter = assetUid == null || assetUid.isBlank() ? "" : "AND source_id = " + sqlString(assetUid);
    List<Map<String, Object>> agents = queryJsonRows("""
        SELECT source_id AS asset_uid, source_id, max(received_at) AS last_seen_at
        FROM %s
        WHERE source_id != ''
          %s
        GROUP BY source_id
        ORDER BY last_seen_at DESC
        LIMIT 50
        FORMAT JSONEachRow
        """.formatted(rawTable, sourceFilter));
    Map<String, Object> heartbeat = agentHeartbeat(agents);
    List<Map<String, Object>> collectors = queryJsonRows("""
        SELECT item_type AS name, count() AS sample_count, max(received_at) AS last_seen_at
        FROM %s
        WHERE source_id != ''
          %s
        GROUP BY item_type
        ORDER BY sample_count DESC, name
        LIMIT 20
        FORMAT JSONEachRow
        """.formatted(rawTable, sourceFilter));
    List<Map<String, Object>> metrics = queryJsonRows("""
        SELECT
          source_id AS asset_uid,
          metric_name,
          event_json,
          last_observed_at AS observed_at
        FROM (
          SELECT
            source_id,
            item_key AS metric_name,
            argMax(event_json, received_at) AS event_json,
            max(received_at) AS last_observed_at
          FROM %s
          WHERE item_kind = 'metric'
            %s
          GROUP BY source_id, item_key
        )
        ORDER BY last_observed_at DESC, metric_name
        LIMIT 24
        FORMAT JSONEachRow
        """.formatted(rawTable, sourceFilter)).stream()
        .map(ClickHouseClient::rawMetricRow)
        .toList();
    List<Map<String, Object>> stateRows = queryJsonRows("""
        SELECT
          source_id AS asset_uid,
          source_id,
          item_type AS state_type,
          item_key AS state_key,
          argMax(event_json, received_at) AS event_json,
          max(received_at) AS observed_at
        FROM %s
        WHERE item_kind = 'state'
          AND source_id != ''
          %s
        GROUP BY source_id, item_type, item_key
        ORDER BY observed_at DESC
        LIMIT 250
        FORMAT JSONEachRow
        """.formatted(rawTable, sourceFilter)).stream()
        .map(ClickHouseClient::rawStateRow)
        .toList();
    List<Map<String, Object>> sockets = stateRows.stream()
        .filter(row -> "socket".equals(row.get("stateType")))
        .limit(50)
        .toList();
    List<Map<String, Object>> services = stateRows.stream()
        .filter(row -> "service".equals(row.get("stateType")))
        .limit(50)
        .toList();
    List<Map<String, Object>> firewalls = stateRows.stream()
        .filter(row -> "firewall".equals(row.get("stateType")))
        .limit(50)
        .toList();
    List<Map<String, Object>> processes = stateRows.stream()
        .filter(row -> "process".equals(row.get("stateType")))
        .limit(50)
        .toList();
    List<Map<String, Object>> packages = stateRows.stream()
        .filter(row -> "package".equals(row.get("stateType")))
        .limit(50)
        .toList();
    List<Map<String, Object>> events = queryJsonRows("""
        SELECT
          source_id AS asset_uid,
          item_type AS event_type,
          argMax(event_json, received_at) AS event_json,
          max(received_at) AS observed_at
        FROM %s
        WHERE item_kind = 'event'
          %s
        GROUP BY source_id, item_type
        ORDER BY observed_at DESC
        LIMIT 20
        FORMAT JSONEachRow
        """.formatted(rawTable, sourceFilter)).stream()
        .map(ClickHouseClient::rawEventRow)
        .toList();
    return Map.of(
        "heartbeat", normalizeDashboardKeys(heartbeat),
        "securityPosture", securityPosture(sockets, services, firewalls, events),
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
        target.put(snakeToCamel(field.getKey()), OBJECT_MAPPER.convertValue(field.getValue(), Object.class));
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
      List<Map<String, Object>> firewalls,
      List<Map<String, Object>> events) {
    return Map.of(
        "exposedPorts", sockets.stream().filter(ClickHouseClient::isListeningSocket).count(),
        "failedServices", services.stream().filter(ClickHouseClient::isProblemService).count(),
        "firewallDisabled", firewalls.stream().filter(ClickHouseClient::isFirewallDisabled).count(),
        "securityEvents", (long) events.size());
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
}
