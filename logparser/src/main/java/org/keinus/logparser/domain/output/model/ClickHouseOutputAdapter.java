package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Slf4j
public class ClickHouseOutputAdapter extends OutputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter CLICKHOUSE_DATE_TIME_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLICKHOUSE_DATE_TIME_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]");
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final String DEFAULT_DATABASE = "default";
    private static final String DEFAULT_TABLE_NAME = "castrelyx_agent_events";
    private static final String DEFAULT_METRIC_TABLE_NAME = "manager_metric_samples";
    private static final String DEFAULT_STATE_TABLE_NAME = "manager_state_snapshots";
    private static final String DEFAULT_EVENT_TABLE_NAME = "manager_events";

    private final ClickHouseConfig config;
    private final HttpClient httpClient;
    private final List<EventRecord> buffer = new ArrayList<>();
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService flushExecutor;

    public ClickHouseOutputAdapter(Map<String, String> obj) throws IOException {
        super(obj);
        this.config = ClickHouseConfig.from(obj, System::getenv);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(getTimeoutMs()))
                .build();
        if (config.autoCreateSchema()) {
            createSchema();
        }
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("ClickHouseOutputAdapterFlush-" + config.tableName());
            thread.setDaemon(true);
            return thread;
        });
        this.flushExecutor.scheduleWithFixedDelay(
                this::flushQuietly,
                config.flushIntervalMs(),
                config.flushIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void send(LogEvent logEvent) {
        if (closed.get()) {
            throw deliveryFailure("Adapter is closed");
        }
        synchronized (lock) {
            buffer.add(EventRecord.from(logEvent, isAddOriginText()));
            if (buffer.size() >= config.batchSize()) {
                flushLocked();
            }
        }
    }

    private void createSchema() {
        String rawSql = """
                CREATE TABLE IF NOT EXISTS %s (
                  received_at DateTime64(3) DEFAULT now64(3),
                  agent_id String,
                  tenant_id Nullable(String),
                  source_id String,
                  item_kind Nullable(String),
                  item_type Nullable(String),
                  item_key Nullable(String),
                  event_json String
                )
                ENGINE = MergeTree
                PARTITION BY toDate(received_at)
                ORDER BY (received_at, source_id, ifNull(item_key, ''))
                TTL toDateTime(received_at) + INTERVAL 7 DAY DELETE
                """.formatted(config.tableReference());
        postQuery(rawSql, "");
        postQuery("ALTER TABLE " + config.tableReference()
                + " MODIFY TTL toDateTime(received_at) + INTERVAL 7 DAY DELETE SETTINGS materialize_ttl_after_modify=0", "");
        if (config.writeTelemetryTables()) {
            postQuery(createMetricSamplesSql(), "");
            postQuery(createStateSnapshotsSql(), "");
            postQuery(createEventsSql(), "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.metricTableName())
                    + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.stateTableName())
                    + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.eventTableName())
                    + " MODIFY TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE SETTINGS materialize_ttl_after_modify=0", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.metricTableName())
                    + " ADD INDEX IF NOT EXISTS idx_metric_observed_at observed_at TYPE minmax GRANULARITY 1", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.metricTableName())
                    + " ADD INDEX IF NOT EXISTS idx_metric_name metric_name TYPE set(4096) GRANULARITY 4", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.metricTableName())
                    + " ADD INDEX IF NOT EXISTS idx_metric_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.stateTableName())
                    + " ADD INDEX IF NOT EXISTS idx_state_observed_at observed_at TYPE minmax GRANULARITY 1", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.stateTableName())
                    + " ADD INDEX IF NOT EXISTS idx_state_type state_type TYPE set(1024) GRANULARITY 4", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.stateTableName())
                    + " ADD INDEX IF NOT EXISTS idx_state_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.eventTableName())
                    + " ADD INDEX IF NOT EXISTS idx_event_observed_at observed_at TYPE minmax GRANULARITY 1", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.eventTableName())
                    + " ADD INDEX IF NOT EXISTS idx_event_type event_type TYPE set(1024) GRANULARITY 4", "");
            postQuery("ALTER TABLE " + config.telemetryTableReference(config.eventTableName())
                    + " ADD INDEX IF NOT EXISTS idx_event_asset asset_uid TYPE bloom_filter(0.01) GRANULARITY 4", "");
        }
    }

    private String createMetricSamplesSql() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
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
                )
                ENGINE = MergeTree
                PARTITION BY toDate(observed_at)
                ORDER BY (asset_uid, metric_name, observed_at)
                TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
                """.formatted(config.telemetryTableReference(config.metricTableName()));
    }

    private String createStateSnapshotsSql() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
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
                )
                ENGINE = ReplacingMergeTree(observed_at)
                PARTITION BY toDate(observed_at)
                ORDER BY (asset_uid, state_type, state_key)
                TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
                """.formatted(config.telemetryTableReference(config.stateTableName()));
    }

    private String createEventsSql() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
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
                )
                ENGINE = MergeTree
                PARTITION BY toDate(observed_at)
                ORDER BY (asset_uid, event_type, observed_at)
                TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE
                """.formatted(config.telemetryTableReference(config.eventTableName()));
    }

    private void flushQuietly() {
        try {
            synchronized (lock) {
                flushLocked();
            }
        } catch (Exception e) {
            log.warn("ClickHouse output flush failed: {}", e.getMessage(), e);
        }
    }

    private void flushLocked() {
        if (buffer.isEmpty()) {
            return;
        }
        List<EventRecord> records = List.copyOf(buffer);
        StringBuilder body = new StringBuilder();
        for (EventRecord record : records) {
            body.append(record.toJsonEachRow()).append('\n');
        }
        postQuery(insertSql(), body.toString());
        if (config.writeTelemetryTables()) {
            insertTelemetryRows(records);
        }
        buffer.clear();
    }

    private String insertSql() {
        return "INSERT INTO " + config.tableReference()
                + " (agent_id, tenant_id, source_id, item_kind, item_type, item_key, event_json)"
                + " FORMAT JSONEachRow";
    }

    private void insertTelemetryRows(List<EventRecord> records) {
        List<Map<String, Object>> metricRows = new ArrayList<>();
        List<Map<String, Object>> stateRows = new ArrayList<>();
        List<Map<String, Object>> eventRows = new ArrayList<>();
        for (EventRecord record : records) {
            addTelemetryRows(record, metricRows, stateRows, eventRows);
        }
        insertTelemetryTable(
                config.metricTableName(),
                "(observed_at, asset_uid, source_type, source_id, metric_name, metric_value, unit, labels_json)",
                metricRows);
        insertTelemetryTable(
                config.stateTableName(),
                "(observed_at, asset_uid, source_type, source_id, state_type, state_key, state_json)",
                stateRows);
        insertTelemetryTable(
                config.eventTableName(),
                "(observed_at, asset_uid, source_type, source_id, event_type, severity, event_json)",
                eventRows);
    }

    private void addTelemetryRows(
            EventRecord record,
            List<Map<String, Object>> metricRows,
            List<Map<String, Object>> stateRows,
            List<Map<String, Object>> eventRows
    ) {
        JsonNode event = eventJson(record);
        JsonNode payload = payloadNode(event);
        String kind = firstNonBlank(
                record.itemKind(),
                text(event, "item_kind"),
                text(event.path("common"), "eventCategory"));
        if (kind == null && "snmp".equalsIgnoreCase(firstNonBlank(text(payload, "protocol"), text(event, "protocol")))) {
            kind = "snmp";
        }
        Instant observedAt = observedAt(event, payload);
        if ("snmp".equalsIgnoreCase(kind)) {
            addSnmpRows(record, event, payload, observedAt, metricRows, stateRows, eventRows);
            return;
        }
        if ("metric".equalsIgnoreCase(kind)) {
            Map<String, Object> row = metricRow(record, event, payload, observedAt, "AGENT");
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
            EventRecord record,
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
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                Map<String, Object> row = snmpMetricRow(record, targetHost, observedAt, metric, null);
                if (row != null) {
                    metricRows.add(row);
                }
            }
        } else if (metrics.isObject()) {
            metrics.fields().forEachRemaining(field -> {
                Map<String, Object> row = snmpMetricRow(record, targetHost, observedAt, field.getValue(), field.getKey());
                if (row != null) {
                    metricRows.add(row);
                }
            });
        }

        if ("error".equalsIgnoreCase(firstNonBlank(text(payload, "poll_status"), text(event, "poll_status")))) {
            eventRows.add(eventRow(observedAt, targetHost, "SNMP", record.sourceId(), "snmp.poll.failure", "WARNING", json(payload)));
        }
    }

    private Map<String, Object> metricRow(
            EventRecord record,
            JsonNode event,
            JsonNode metric,
            Instant observedAt,
            String sourceType
    ) {
        Double value = metricValue(metric);
        if (value == null) {
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
        return hasText((String) row.get("metric_name")) ? row : null;
    }

    private Map<String, Object> snmpMetricRow(
            EventRecord record,
            String targetHost,
            Instant observedAt,
            JsonNode metric,
            String fallbackName
    ) {
        String name = firstNonBlank(text(metric, "name"), fallbackName);
        Double value = metricValue(metric);
        if (value == null || name == null || name.isBlank()) {
            return null;
        }
        ObjectNode labels = OBJECT_MAPPER.createObjectNode();
        copyLabel(metric, labels, "interface");
        Map<String, Object> row = baseTelemetryRow(observedAt, targetHost, "SNMP", record.sourceId());
        row.put("metric_name", snmpMetricName(name));
        row.put("metric_value", value);
        row.put("unit", text(metric, "unit"));
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
        row.put("asset_uid", firstNonBlank(assetUid, sourceId, "unknown"));
        row.put("source_type", firstNonBlank(sourceType, "AGENT"));
        row.put("source_id", firstNonBlank(sourceId, assetUid, "unknown"));
        return row;
    }

    private void insertTelemetryTable(String tableName, String columns, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        postQuery("INSERT INTO " + config.telemetryTableReference(tableName) + " " + columns + " FORMAT JSONEachRow",
                jsonEachRow(rows));
    }

    private static JsonNode eventJson(EventRecord record) {
        if (record.eventJson() == null || record.eventJson().isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(record.eventJson());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse ClickHouse telemetry event JSON", e);
        }
    }

    private static JsonNode payloadNode(JsonNode event) {
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

    private static String assetUid(EventRecord record, JsonNode event, JsonNode payload) {
        return firstNonBlank(text(payload, "asset_uid"), text(event, "asset_uid"), record.sourceId());
    }

    private static Instant observedAt(JsonNode event, JsonNode payload) {
        return instant(firstNonBlank(
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
            case "ifInOctets" -> "interface.in.bytes";
            case "ifOutOctets" -> "interface.out.bytes";
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

    private static String json(JsonNode node) {
        try {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "{}";
            }
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize telemetry JSON", e);
        }
    }

    private static String jsonEachRow(List<Map<String, Object>> rows) {
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> row : rows) {
            try {
                body.append(OBJECT_MAPPER.writeValueAsString(row)).append('\n');
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize ClickHouse telemetry JSONEachRow payload", e);
            }
        }
        return body.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void postQuery(String query, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(queryUri(query))
                    .timeout(Duration.ofMillis(getTimeoutMs()))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            config.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw deliveryFailure("ClickHouse HTTP status " + response.statusCode() + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw deliveryFailure("Interrupted while sending ClickHouse request", e);
        } catch (IOException e) {
            throw deliveryFailure("Failed to send ClickHouse request", e);
        }
    }

    private URI queryUri(String query) {
        String separator = config.endpointUrl().contains("?") ? "&" : "?";
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return URI.create(config.endpointUrl() + separator + "query=" + encodedQuery);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        flushExecutor.shutdown();
        synchronized (lock) {
            flushLocked();
        }
    }

    record ClickHouseConfig(
            String endpointUrl,
            String database,
            String username,
            String password,
            String tableName,
            String metricTableName,
            String stateTableName,
            String eventTableName,
            int batchSize,
            int flushIntervalMs,
            boolean autoCreateSchema,
            boolean writeTelemetryTables
    ) {
        static ClickHouseConfig from(Map<String, String> rawConfig, Function<String, String> envLookup) throws IOException {
            String configParams = rawConfig.get("configParams");
            if (configParams == null || configParams.isBlank()) {
                throw new IOException("ClickHouseOutputAdapter requires configParams");
            }
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            String endpointUrl = normalizeEndpointUrl(requiredText(root, "endpointUrl"));
            String database = optionalText(root, "database", DEFAULT_DATABASE);
            String tableName = optionalText(root, "tableName", DEFAULT_TABLE_NAME);
            String metricTableName = optionalText(root, "metricTableName", DEFAULT_METRIC_TABLE_NAME);
            String stateTableName = optionalText(root, "stateTableName", DEFAULT_STATE_TABLE_NAME);
            String eventTableName = optionalText(root, "eventTableName", DEFAULT_EVENT_TABLE_NAME);
            validateIdentifier(database, "database");
            validateIdentifier(tableName, "tableName");
            validateIdentifier(metricTableName, "metricTableName");
            validateIdentifier(stateTableName, "stateTableName");
            validateIdentifier(eventTableName, "eventTableName");

            String username = resolveEnv(root, "usernameEnv", envLookup);
            String password = resolveEnv(root, "passwordEnv", envLookup);
            if ((username == null) != (password == null)) {
                throw new IOException("configParams.usernameEnv and configParams.passwordEnv must be provided together");
            }

            int batchSize = root.has("batchSize") ? root.get("batchSize").asInt() : DEFAULT_BATCH_SIZE;
            int flushIntervalMs = root.has("flushIntervalMs")
                    ? root.get("flushIntervalMs").asInt()
                    : DEFAULT_FLUSH_INTERVAL_MS;
            if (batchSize <= 0) {
                throw new IOException("batchSize must be greater than zero");
            }
            if (flushIntervalMs <= 0) {
                throw new IOException("flushIntervalMs must be greater than zero");
            }
            return new ClickHouseConfig(
                    endpointUrl,
                    database,
                    username,
                    password,
                    tableName,
                    metricTableName,
                    stateTableName,
                    eventTableName,
                    batchSize,
                    flushIntervalMs,
                    root.has("autoCreateSchema") && root.get("autoCreateSchema").asBoolean(),
                    root.has("writeTelemetryTables")
                            ? root.get("writeTelemetryTables").asBoolean()
                            : DEFAULT_TABLE_NAME.equals(tableName)
            );
        }

        String tableReference() {
            return database + "." + tableName;
        }

        String telemetryTableReference(String tableName) {
            return database + "." + tableName;
        }

        java.util.Optional<String> authorizationHeader() {
            if (username == null || password == null) {
                return java.util.Optional.empty();
            }
            String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return java.util.Optional.of("Basic " + token);
        }

        private static String requiredText(JsonNode root, String fieldName) throws IOException {
            String value = optionalText(root, fieldName, null);
            if (value == null || value.isBlank()) {
                throw new IOException("configParams." + fieldName + " is required");
            }
            return value;
        }

        private static String optionalText(JsonNode root, String fieldName, String defaultValue) {
            JsonNode value = root.get(fieldName);
            if (value == null || value.isNull()) {
                return defaultValue;
            }
            return value.asText();
        }

        private static String resolveEnv(JsonNode root, String fieldName, Function<String, String> envLookup) throws IOException {
            String envName = optionalText(root, fieldName, null);
            if (envName == null || envName.isBlank()) {
                return null;
            }
            String value = envLookup.apply(envName);
            if (value == null || value.isBlank()) {
                throw new IOException("Environment variable " + envName + " is required");
            }
            return value;
        }

        private static String normalizeEndpointUrl(String endpointUrl) throws IOException {
            URI uri;
            try {
                uri = URI.create(endpointUrl);
            } catch (IllegalArgumentException e) {
                throw new IOException("configParams.endpointUrl must be a valid URI", e);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IOException("configParams.endpointUrl must use http or https");
            }
            if (endpointUrl.endsWith("/")) {
                return endpointUrl.substring(0, endpointUrl.length() - 1);
            }
            return endpointUrl;
        }

        private static void validateIdentifier(String value, String fieldName) throws IOException {
            if (value == null || !value.matches("[A-Za-z0-9_]+")) {
                throw new IOException(fieldName + " must contain only letters, numbers, and underscore");
            }
        }
    }

    record EventRecord(
            String agentId,
            String tenantId,
            String sourceId,
            String itemKind,
            String itemType,
            String itemKey,
            String eventJson
    ) {
        static EventRecord from(LogEvent event, boolean includeOriginText) {
            Map<String, Object> output = event.toOutputMap(includeOriginText);
            String sourceId = firstNonBlank(
                    asString(output.get("source_id")),
                    asString(additional(output).get("source_id")),
                    asString(common(output).get("srcHost")),
                    event.getSourceHost(),
                    "unknown"
            );
            return new EventRecord(
                    sourceId,
                    firstNonBlank(
                            asString(output.get("tenant_id")),
                            asString(additional(output).get("tenant_id"))
                    ),
                    sourceId,
                    firstNonBlank(
                            asString(output.get("item_kind")),
                            asString(additional(output).get("item_kind")),
                            asString(common(output).get("eventCategory"))
                    ),
                    firstNonBlank(
                            asString(output.get("item_type")),
                            asString(additional(output).get("item_type")),
                            asString(common(output).get("eventType"))
                    ),
                    firstNonBlank(
                            asString(output.get("item_key")),
                            asString(additional(output).get("item_key")),
                            asString(common(output).get("eventAction"))
                    ),
                    event.toOutputJson(includeOriginText)
            );
        }

        String toJsonEachRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent_id", agentId);
            row.put("tenant_id", tenantId);
            row.put("source_id", sourceId);
            row.put("item_kind", itemKind);
            row.put("item_type", itemType);
            row.put("item_key", itemKey);
            row.put("event_json", eventJson);
            try {
                return OBJECT_MAPPER.writeValueAsString(row);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize ClickHouse JSONEachRow payload", e);
            }
        }

        private static Map<?, ?> common(Map<String, Object> output) {
            return mapValue(output.get("common"));
        }

        private static Map<?, ?> additional(Map<String, Object> output) {
            return mapValue(output.get("additionalAttributes"));
        }

        private static Map<?, ?> mapValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            return Map.of();
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }
}
