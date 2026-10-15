package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final int DEFAULT_INCOMPLETE_GROUP_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_MAX_PENDING_GROUPS = 2_048;
    private static final int DEFAULT_MAX_PENDING_ITEMS = 50_000;
    private static final long DEFAULT_MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final long DEFAULT_MAX_INCOMPLETE_DLQ_BYTES = 128L * 1024 * 1024;
    private static final int DEFAULT_MAX_INCOMPLETE_DLQ_RECORDS = 1_000;
    private static final String DEFAULT_DATABASE = "default";
    private static final String DEFAULT_TABLE_NAME = "castrelyx_agent_events";
    private static final String DEFAULT_METRIC_TABLE_NAME = "manager_metric_samples";
    private static final String DEFAULT_STATE_TABLE_NAME = "manager_state_snapshots";
    private static final String DEFAULT_EVENT_TABLE_NAME = "manager_events";

    private final ClickHouseConfig config;
    private final HttpClient httpClient;
    private final List<EventRecord> legacyBuffer = new ArrayList<>();
    private long legacyBufferBytes;
    private final LinkedHashMap<ChunkGroupKey, PendingChunkGroup> pendingChunkGroups = new LinkedHashMap<>();
    private int pendingChunkItemCount;
    private long pendingChunkBytes;
    private final IncompleteChunkDlq incompleteChunkDlq;
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService flushExecutor;

    public ClickHouseOutputAdapter(Map<String, String> obj) throws IOException {
        super(obj);
        this.config = ClickHouseConfig.from(obj, System::getenv);
        this.incompleteChunkDlq = new IncompleteChunkDlq(
                config.incompleteChunkDlqDir(),
                config.maxIncompleteChunkDlqBytes(),
                config.maxIncompleteChunkDlqRecords());
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
        if ("security-router".equals(logEvent.getMessageType())) {
            writeRouterEvent(logEvent);
            return;
        }
        synchronized (lock) {
            EventRecord record = EventRecord.from(logEvent, isAddOriginText());
            if (record.isChunkAligned()) {
                addChunkRecordLocked(record);
            } else {
                long recordBytes = record.serializedSizeBytes();
                if (!legacyBuffer.isEmpty()
                        && legacyBufferBytes + pendingChunkBytes + recordBytes > config.maxPendingBytes()) {
                    flushLegacyLocked();
                }
                while (!pendingChunkGroups.isEmpty()
                        && pendingChunkBytes + recordBytes > config.maxPendingBytes()) {
                    forceFlushOldestChunkGroupLocked("global pending byte limit");
                }
                if (recordBytes > config.maxPendingBytes()) {
                    writeRecords(List.of(record), null, true);
                    return;
                }
                legacyBuffer.add(record);
                legacyBufferBytes += recordBytes;
                if (legacyBuffer.size() >= config.batchSize()
                        || legacyBufferBytes >= config.maxPendingBytes()) {
                    flushLegacyLocked();
                }
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
                  batch_id String DEFAULT '',
                  chunk_index UInt32 DEFAULT 0,
                  chunk_item_count UInt32 DEFAULT 0,
                  item_sequence UInt32 DEFAULT 0,
                  item_id String DEFAULT '',
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
        postQuery("ALTER TABLE " + config.tableReference() + " ADD COLUMN IF NOT EXISTS batch_id String DEFAULT '' AFTER source_id", "");
        postQuery("ALTER TABLE " + config.tableReference() + " ADD COLUMN IF NOT EXISTS chunk_index UInt32 DEFAULT 0 AFTER batch_id", "");
        postQuery("ALTER TABLE " + config.tableReference() + " ADD COLUMN IF NOT EXISTS chunk_item_count UInt32 DEFAULT 0 AFTER chunk_index", "");
        postQuery("ALTER TABLE " + config.tableReference() + " ADD COLUMN IF NOT EXISTS item_sequence UInt32 DEFAULT 0 AFTER chunk_item_count", "");
        postQuery("ALTER TABLE " + config.tableReference() + " ADD COLUMN IF NOT EXISTS item_id String DEFAULT '' AFTER item_sequence", "");
        postQuery("ALTER TABLE " + config.tableReference()
                + " ADD INDEX IF NOT EXISTS idx_batch_id batch_id TYPE bloom_filter(0.01) GRANULARITY 4", "");
        enableNonReplicatedDeduplication(config.tableReference());
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
            enableNonReplicatedDeduplication(config.telemetryTableReference(config.metricTableName()));
            enableNonReplicatedDeduplication(config.telemetryTableReference(config.stateTableName()));
            enableNonReplicatedDeduplication(config.telemetryTableReference(config.eventTableName()));
        }
    }

    private void writeRouterEvent(LogEvent event) {
        if (!config.writeTelemetryTables()) throw deliveryFailure("Router output requires writeTelemetryTables=true");
        String source = String.valueOf(event.getField("source_id"));
        String id = String.valueOf(event.getField("item_id"));
        if (!source.equals(event.getAuthenticatedSourceId()) || !"SECURITY_ROUTER".equals(event.getField("source_type"))) throw deliveryFailure("Router event was not authenticated and normalized");
        String key = "router:" + source.length() + ":" + source + ":" + id;
        EventRecord record = EventRecord.from(event, true);
        // Synchronous writes: a successful send means canonical DB insertion,
        // not merely appending to the ordinary batch buffer.
        writeRawRecords(List.of(record), key);
        Map<String,Object> payload = new LinkedHashMap<>(OBJECT_MAPPER.convertValue(event.getField("payload"), Map.class));
        payload.put("stored_at", Instant.now().toString());
        Map<String,Object> row = eventRow(Instant.parse(String.valueOf(event.getField("observed_at"))),
                event.getAuthenticatedAssetUid(), "SECURITY_ROUTER", source,
                String.valueOf(event.getField("item_type")), String.valueOf(payload.get("severity")), json(OBJECT_MAPPER.valueToTree(payload)));
        insertTelemetryTable(config.eventTableName(),
                "(observed_at, asset_uid, source_type, source_id, event_type, severity, event_json)", List.of(row), key);
        event.acknowledgeStored();
    }

    private void enableNonReplicatedDeduplication(String tableReference) {
        postQuery("ALTER TABLE " + tableReference
                + " MODIFY SETTING non_replicated_deduplication_window = 4096", "");
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
                if (backgroundFlushPaused) { return; }
                flushLegacyLocked();
                flushExpiredChunkGroupsLocked(System.nanoTime());
            }
        } catch (Exception e) {
            log.warn("ClickHouse output flush failed: {}", e.getMessage(), e);
            // Notify outside the buffer lock; recovery may be waiting to send on this adapter.
            reportBackgroundFailure(e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("ClickHouse background flush failed", e));
        }
    }

    @Override
    public void retryPendingFlush() {
        synchronized (lock) {
            flushLegacyLocked();
            flushExpiredChunkGroupsLocked(System.nanoTime());
        }
    }

    private void flushLegacyLocked() {
        if (legacyBuffer.isEmpty()) {
            return;
        }
        List<EventRecord> records = List.copyOf(legacyBuffer);
        writeRecords(records, null, true);
        legacyBuffer.clear();
        legacyBufferBytes = 0;
    }

    private void addChunkRecordLocked(EventRecord record) {
        ChunkGroupKey key = new ChunkGroupKey(record.sourceId(), record.batchId(), record.chunkIndex());
        if (record.chunkItemCount() == 1) {
            writeRecords(List.of(record), key.deduplicationKey(), true);
            return;
        }
        long recordBytes = record.serializedSizeBytes();
        if (!legacyBuffer.isEmpty()
                && legacyBufferBytes + pendingChunkBytes + recordBytes > config.maxPendingBytes()) {
            flushLegacyLocked();
        }
        if (recordBytes > config.maxPendingBytes()) {
            quarantineIncompleteRecords(
                    key,
                    record.chunkItemCount(),
                    List.of(record),
                    "single item exceeds pending chunk byte limit");
            return;
        }

        PendingChunkGroup group = pendingChunkGroups.get(key);
        if (group != null && group.expectedItemCount() != record.chunkItemCount()) {
            flushChunkGroupLocked(key, group, "inconsistent chunk_item_count");
            group = null;
        }

        while (true) {
            boolean addsGroup = group == null;
            boolean addsItem = group == null || !group.containsSequence(record.itemSequence());
            long byteDelta = group == null ? recordBytes : group.byteDeltaFor(record, recordBytes);
            if (pendingChunkGroups.size() + (addsGroup ? 1 : 0) <= config.maxPendingGroups()
                    && pendingChunkItemCount + (addsItem ? 1 : 0) <= config.maxPendingItems()
                    && legacyBufferBytes + pendingChunkBytes + byteDelta <= config.maxPendingBytes()) {
                break;
            }
            forceFlushOldestChunkGroupLocked("pending chunk count or byte limit");
            group = pendingChunkGroups.get(key);
        }

        if (group == null) {
            group = new PendingChunkGroup(record.chunkItemCount(), System.nanoTime());
            pendingChunkGroups.put(key, group);
        }
        PendingChunkGroup.PutResult result = group.put(record, recordBytes);
        if (result.added()) {
            pendingChunkItemCount++;
        }
        pendingChunkBytes += result.byteDelta();
        if (group.isComplete()) {
            flushChunkGroupLocked(key, group, null);
        }
    }

    private void flushExpiredChunkGroupsLocked(long nowNanos) {
        List<ChunkGroupKey> expired = pendingChunkGroups.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(nowNanos, config.incompleteGroupTimeoutMs()))
                .map(Map.Entry::getKey)
                .toList();
        for (ChunkGroupKey key : expired) {
            PendingChunkGroup group = pendingChunkGroups.get(key);
            if (group != null) {
                flushChunkGroupLocked(key, group, "incomplete chunk timeout");
            }
        }
    }

    private void forceFlushOldestChunkGroupLocked(String reason) {
        Map.Entry<ChunkGroupKey, PendingChunkGroup> oldest = pendingChunkGroups.entrySet().iterator().next();
        flushChunkGroupLocked(oldest.getKey(), oldest.getValue(), reason);
    }

    private void flushAllChunkGroupsLocked(String reason) {
        for (ChunkGroupKey key : List.copyOf(pendingChunkGroups.keySet())) {
            PendingChunkGroup group = pendingChunkGroups.get(key);
            if (group != null) {
                flushChunkGroupLocked(key, group, reason);
            }
        }
    }

    private void flushChunkGroupLocked(ChunkGroupKey key, PendingChunkGroup group, String forcedReason) {
        boolean complete = group.isComplete();
        List<EventRecord> records = group.recordsInSequenceOrder();
        if (!complete) {
            log.warn(
                    "Quarantining incomplete ClickHouse chunk group {} ({}/{} items): {}",
                    key,
                    group.size(),
                    group.expectedItemCount(),
                    forcedReason == null ? "incomplete chunk" : forcedReason
            );
            quarantineIncompleteRecords(
                    key,
                    group.expectedItemCount(),
                    records,
                    forcedReason == null ? "incomplete chunk" : forcedReason);
            removePendingGroup(key, group);
            return;
        }

        writeRecords(records, key.deduplicationKey(), true);
        removePendingGroup(key, group);
    }

    private void removePendingGroup(ChunkGroupKey key, PendingChunkGroup group) {
        pendingChunkGroups.remove(key);
        pendingChunkItemCount -= group.size();
        pendingChunkBytes -= group.totalBytes();
    }

    private void quarantineIncompleteRecords(
            ChunkGroupKey key,
            int expectedItemCount,
            List<EventRecord> records,
            String reason
    ) {
        List<String> rawRows = records.stream().map(EventRecord::toJsonEachRow).toList();
        try {
            incompleteChunkDlq.persist(
                    key.sourceId(),
                    key.batchId(),
                    key.chunkIndex(),
                    expectedItemCount,
                    reason,
                    rawRows);
        } catch (IOException e) {
            throw deliveryFailure("Failed to persist incomplete chunk DLQ record", e);
        }

        try {
            writeRawRecords(records, null);
        } catch (RuntimeException rawInsertFailure) {
            log.warn(
                    "Incomplete chunk {} is durable in the DLQ but its best-effort raw insert failed: {}",
                    key,
                    rawInsertFailure.getMessage(),
                    rawInsertFailure);
        }
    }

    private void writeRecords(List<EventRecord> records, String stableChunkKey, boolean writeCanonical) {
        if (records.isEmpty()) {
            return;
        }
        writeRawRecords(records, stableChunkKey);
        if (writeCanonical && config.writeTelemetryTables()) {
            insertTelemetryRows(records, stableChunkKey);
        }
    }

    private void writeRawRecords(List<EventRecord> records, String stableChunkKey) {
        StringBuilder body = new StringBuilder();
        for (EventRecord record : records) {
            body.append(record.toJsonEachRow()).append('\n');
        }
        postInsert(insertSql(), body.toString(), "raw:" + config.tableReference(), stableChunkKey);
    }

    private String insertSql() {
        return "INSERT INTO " + config.tableReference()
                + " (agent_id, tenant_id, source_id, batch_id, chunk_index, chunk_item_count, item_sequence, item_id, item_kind, item_type, item_key, event_json)"
                + " FORMAT JSONEachRow";
    }

    private void insertTelemetryRows(List<EventRecord> records, String stableChunkKey) {
        List<Map<String, Object>> metricRows = new ArrayList<>();
        List<Map<String, Object>> stateRows = new ArrayList<>();
        List<Map<String, Object>> eventRows = new ArrayList<>();
        for (EventRecord record : records) {
            addTelemetryRows(record, metricRows, stateRows, eventRows);
        }
        insertTelemetryTable(
                config.metricTableName(),
                "(observed_at, asset_uid, source_type, source_id, metric_name, metric_value, unit, labels_json)",
                metricRows,
                stableChunkKey);
        insertTelemetryTable(
                config.stateTableName(),
                "(observed_at, asset_uid, source_type, source_id, state_type, state_key, state_json)",
                stateRows,
                stableChunkKey);
        insertTelemetryTable(
                config.eventTableName(),
                "(observed_at, asset_uid, source_type, source_id, event_type, severity, event_json)",
                eventRows,
                stableChunkKey);
    }

    private void addTelemetryRows(EventRecord record, List<Map<String, Object>> metrics,
            List<Map<String, Object>> states, List<Map<String, Object>> events) {
        try {
            JsonNode telemetry = OBJECT_MAPPER.readTree(record.eventJson()).path("telemetry");
            appendRows(telemetry.path("metrics"), metrics);
            appendRows(telemetry.path("states"), states);
            appendRows(telemetry.path("events"), events);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid structured telemetry", e);
        }
    }

    private static void appendRows(JsonNode rows, List<Map<String, Object>> target) {
        if (rows.isArray()) rows.forEach(row -> target.add(OBJECT_MAPPER.convertValue(row,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})));
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
        row.put("observed_at", CLICKHOUSE_DATE_TIME_OUT.format(observedAt));
        row.put("asset_uid", firstNonBlank(assetUid, sourceId, "unknown"));
        row.put("source_type", firstNonBlank(sourceType, "AGENT"));
        row.put("source_id", firstNonBlank(sourceId, assetUid, "unknown"));
        return row;
    }

    private void insertTelemetryTable(String tableName, String columns, List<Map<String, Object>> rows, String stableChunkKey) {
        if (rows.isEmpty()) {
            return;
        }
        String body = jsonEachRow(rows);
        String tableReference = config.telemetryTableReference(tableName);
        postInsert("INSERT INTO " + tableReference + " " + columns + " FORMAT JSONEachRow",
                body,
                "telemetry:" + tableReference,
                stableChunkKey);
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
        postQuery(query, body, null);
    }

    private void postInsert(String query, String body, String namespace, String stableChunkKey) {
        String tokenMaterial = stableChunkKey == null
                ? namespace + "\u0000" + body
                : namespace + "\u0000chunk\u0000" + stableChunkKey;
        postQuery(query, body, deduplicationToken(tokenMaterial));
    }

    private void postQuery(String query, String body, String deduplicationToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(queryUri(query, deduplicationToken))
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

    private URI queryUri(String query, String deduplicationToken) {
        String separator = config.endpointUrl().contains("?") ? "&" : "?";
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        StringBuilder uri = new StringBuilder(config.endpointUrl())
                .append(separator)
                .append("query=")
                .append(encodedQuery);
        if (deduplicationToken != null && !deduplicationToken.isBlank()) {
            uri.append("&insert_deduplication_token=")
                    .append(URLEncoder.encode(deduplicationToken, StandardCharsets.UTF_8));
        }
        return URI.create(uri.toString());
    }

    private static String deduplicationToken(String tokenMaterial) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(tokenMaterial.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        flushExecutor.shutdown();
        synchronized (lock) {
            flushLegacyLocked();
            flushAllChunkGroupsLocked("adapter close");
        }
    }

    private record ChunkGroupKey(String sourceId, String batchId, int chunkIndex) {
        private String deduplicationKey() {
            return sourceId + "\u0000" + batchId + "\u0000" + chunkIndex;
        }
    }

    /** Durable, bounded quarantine for incomplete chunks. */
    static final class IncompleteChunkDlq {
        private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);

        private final Path directory;
        private final long maxBytes;
        private final int maxRecords;

        IncompleteChunkDlq(Path directory, long maxBytes, int maxRecords) {
            if (directory == null) {
                throw new IllegalArgumentException("incomplete chunk DLQ directory is required");
            }
            if (maxBytes <= 0 || maxRecords <= 0) {
                throw new IllegalArgumentException("incomplete chunk DLQ limits must be positive");
            }
            this.directory = directory.toAbsolutePath().normalize();
            this.maxBytes = maxBytes;
            this.maxRecords = maxRecords;
        }

        Path persist(
                String sourceId,
                String batchId,
                int chunkIndex,
                int expectedItemCount,
                String reason,
                List<String> jsonEachRows
        ) throws IOException {
            ObjectNode entry = OBJECT_MAPPER.createObjectNode();
            entry.put("schema_version", "1");
            entry.put("captured_at", Instant.now().toString());
            entry.put("source_id", sourceId);
            entry.put("batch_id", batchId);
            entry.put("chunk_index", chunkIndex);
            entry.put("expected_item_count", expectedItemCount);
            entry.put("actual_item_count", jsonEachRows.size());
            entry.put("reason", reason == null ? "incomplete chunk" : reason);
            ArrayNode rows = entry.putArray("raw_rows");
            for (String row : jsonEachRows) {
                rows.add(OBJECT_MAPPER.readTree(row));
            }
            byte[] encoded = OBJECT_MAPPER.writeValueAsBytes(entry);
            if (encoded.length > maxBytes) {
                throw new IOException("incomplete chunk DLQ record exceeds max bytes: " + encoded.length);
            }

            synchronized (this) {
                ensureDirectory();
                pruneFor(encoded.length);
                String filename = "%019d-%s.json".formatted(System.currentTimeMillis(), UUID.randomUUID());
                Path target = directory.resolve(filename);
                Path temp = Files.createTempFile(directory, ".incomplete-", ".tmp");
                try {
                    setPermissions(temp, FILE_PERMISSIONS);
                    try (FileChannel channel = FileChannel.open(
                            temp,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                        ByteBuffer buffer = ByteBuffer.wrap(encoded);
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                        channel.force(true);
                    }
                    moveAtomically(temp, target);
                    setPermissions(target, FILE_PERMISSIONS);
                    forceDirectory();
                    return target;
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        }

        private void ensureDirectory() throws IOException {
            Files.createDirectories(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
        }

        private void pruneFor(long requiredBytes) throws IOException {
            List<Path> records;
            try (var paths = Files.list(directory)) {
                records = paths
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            long totalBytes = 0;
            for (Path record : records) {
                totalBytes += Files.size(record);
            }
            int index = 0;
            while (records.size() - index >= maxRecords || totalBytes + requiredBytes > maxBytes) {
                if (index >= records.size()) {
                    throw new IOException("incomplete chunk DLQ cannot make room for " + requiredBytes + " bytes");
                }
                Path oldest = records.get(index++);
                totalBytes -= Files.size(oldest);
                Files.deleteIfExists(oldest);
            }
            if (index > 0) {
                forceDirectory();
            }
        }

        private static void moveAtomically(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
            try {
                Files.setPosixFilePermissions(path, permissions);
            } catch (UnsupportedOperationException ignored) {
                // Windows inherits the ACL from the Logparser data directory.
            }
        }

        private void forceDirectory() throws IOException {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                return;
            }
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private static final class PendingChunkGroup {
        private final int expectedItemCount;
        private final long createdAtNanos;
        private final TreeMap<Integer, StoredRecord> recordsBySequence = new TreeMap<>();
        private long totalBytes;

        private PendingChunkGroup(int expectedItemCount, long createdAtNanos) {
            this.expectedItemCount = expectedItemCount;
            this.createdAtNanos = createdAtNanos;
        }

        private int expectedItemCount() {
            return expectedItemCount;
        }

        private boolean containsSequence(int sequence) {
            return recordsBySequence.containsKey(sequence);
        }

        private long byteDeltaFor(EventRecord record, long serializedBytes) {
            StoredRecord previous = recordsBySequence.get(record.itemSequence());
            return previous == null ? serializedBytes : serializedBytes - previous.serializedBytes();
        }

        private PutResult put(EventRecord record, long serializedBytes) {
            StoredRecord previous = recordsBySequence.put(
                    record.itemSequence(),
                    new StoredRecord(record, serializedBytes));
            long byteDelta = previous == null
                    ? serializedBytes
                    : serializedBytes - previous.serializedBytes();
            totalBytes += byteDelta;
            return new PutResult(previous == null, byteDelta);
        }

        private boolean isComplete() {
            return recordsBySequence.size() >= expectedItemCount;
        }

        private boolean isExpired(long nowNanos, int timeoutMs) {
            return nowNanos - createdAtNanos >= TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        private int size() {
            return recordsBySequence.size();
        }

        private long totalBytes() {
            return totalBytes;
        }

        private List<EventRecord> recordsInSequenceOrder() {
            return recordsBySequence.values().stream().map(StoredRecord::record).toList();
        }

        private record StoredRecord(EventRecord record, long serializedBytes) {
        }

        private record PutResult(boolean added, long byteDelta) {
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
            int incompleteGroupTimeoutMs,
            int maxPendingGroups,
            int maxPendingItems,
            long maxPendingBytes,
            Path incompleteChunkDlqDir,
            long maxIncompleteChunkDlqBytes,
            int maxIncompleteChunkDlqRecords,
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
            int incompleteGroupTimeoutMs = root.has("incompleteGroupTimeoutMs")
                    ? root.get("incompleteGroupTimeoutMs").asInt()
                    : DEFAULT_INCOMPLETE_GROUP_TIMEOUT_MS;
            int maxPendingGroups = root.has("maxPendingGroups")
                    ? root.get("maxPendingGroups").asInt()
                    : DEFAULT_MAX_PENDING_GROUPS;
            int maxPendingItems = root.has("maxPendingItems")
                    ? root.get("maxPendingItems").asInt()
                    : DEFAULT_MAX_PENDING_ITEMS;
            long maxPendingBytes = root.has("maxPendingBytes")
                    ? root.get("maxPendingBytes").asLong()
                    : DEFAULT_MAX_PENDING_BYTES;
            String defaultDlqDir = Path.of(
                    System.getProperty("user.home"),
                    "logparser",
                    "data",
                    "incomplete-chunks").toString();
            String incompleteChunkDlqDirValue = optionalText(
                    root,
                    "incompleteChunkDlqDir",
                    defaultDlqDir);
            Path incompleteChunkDlqDir;
            try {
                incompleteChunkDlqDir = Path.of(incompleteChunkDlqDirValue);
            } catch (RuntimeException invalidPath) {
                throw new IOException("incompleteChunkDlqDir must be a valid path", invalidPath);
            }
            long maxIncompleteChunkDlqBytes = root.has("maxIncompleteChunkDlqBytes")
                    ? root.get("maxIncompleteChunkDlqBytes").asLong()
                    : DEFAULT_MAX_INCOMPLETE_DLQ_BYTES;
            int maxIncompleteChunkDlqRecords = root.has("maxIncompleteChunkDlqRecords")
                    ? root.get("maxIncompleteChunkDlqRecords").asInt()
                    : DEFAULT_MAX_INCOMPLETE_DLQ_RECORDS;
            if (batchSize <= 0) {
                throw new IOException("batchSize must be greater than zero");
            }
            if (flushIntervalMs <= 0) {
                throw new IOException("flushIntervalMs must be greater than zero");
            }
            if (incompleteGroupTimeoutMs <= 0) {
                throw new IOException("incompleteGroupTimeoutMs must be greater than zero");
            }
            if (maxPendingGroups <= 0) {
                throw new IOException("maxPendingGroups must be greater than zero");
            }
            if (maxPendingItems <= 0) {
                throw new IOException("maxPendingItems must be greater than zero");
            }
            if (maxPendingBytes <= 0) {
                throw new IOException("maxPendingBytes must be greater than zero");
            }
            if (incompleteChunkDlqDirValue == null || incompleteChunkDlqDirValue.isBlank()) {
                throw new IOException("incompleteChunkDlqDir must not be blank");
            }
            if (maxIncompleteChunkDlqBytes <= 0) {
                throw new IOException("maxIncompleteChunkDlqBytes must be greater than zero");
            }
            if (maxIncompleteChunkDlqRecords <= 0) {
                throw new IOException("maxIncompleteChunkDlqRecords must be greater than zero");
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
                    incompleteGroupTimeoutMs,
                    maxPendingGroups,
                    maxPendingItems,
                    maxPendingBytes,
                    incompleteChunkDlqDir,
                    maxIncompleteChunkDlqBytes,
                    maxIncompleteChunkDlqRecords,
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
            String batchId,
            int chunkIndex,
            int chunkItemCount,
            int itemSequence,
            String itemId,
            String itemKind,
            String itemType,
            String itemKey,
            String eventJson
    ) {
        static EventRecord from(LogEvent event, boolean includeOriginText) {
            Map<String, Object> output = event.toOutputMap(includeOriginText);
            Map<?, ?> additional = additional(output);
            Map<?, ?> common = common(output);
            String sourceId = firstNonBlank(
                    asString(output.get("source_id")),
                    asString(additional.get("source_id")),
                    asString(common.get("srcHost")),
                    event.getSourceHost(),
                    "unknown"
            );
            String itemKind = firstNonBlank(
                    asString(output.get("item_kind")),
                    asString(additional.get("item_kind")),
                    asString(common.get("eventCategory"))
            );
            String itemType = firstNonBlank(
                    asString(output.get("item_type")),
                    asString(additional.get("item_type")),
                    asString(common.get("eventType"))
            );
            String itemKey = firstNonBlank(
                    asString(output.get("item_key")),
                    asString(additional.get("item_key")),
                    asString(common.get("eventAction"))
            );
            String batchId = firstNonBlank(asString(output.get("batch_id")), asString(additional.get("batch_id")));
            int chunkIndex = asInt(firstNonNull(output.get("chunk_index"), additional.get("chunk_index")));
            int chunkItemCount = asInt(firstNonNull(output.get("chunk_item_count"), additional.get("chunk_item_count")));
            int itemSequence = asInt(firstNonNull(output.get("item_sequence"), additional.get("item_sequence")));
            String itemId = firstNonBlank(asString(output.get("item_id")), asString(additional.get("item_id")));
            return new EventRecord(
                    sourceId,
                    firstNonBlank(
                            asString(output.get("tenant_id")),
                            asString(additional.get("tenant_id"))
                    ),
                    sourceId,
                    batchId,
                    chunkIndex,
                    chunkItemCount,
                    itemSequence,
                    itemId,
                    itemKind,
                    itemType,
                    itemKey,
                    compactEventJson(
                            event,
                            output,
                            additional,
                            common,
                            itemKind,
                            itemType,
                            itemKey,
                            batchId,
                            chunkIndex,
                            chunkItemCount,
                            itemSequence,
                            itemId,
                            includeOriginText
                    )
            );
        }

        boolean isChunkAligned() {
            return hasText(batchId) && chunkIndex >= 0 && chunkItemCount > 0;
        }

        String toJsonEachRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent_id", agentId);
            row.put("tenant_id", tenantId);
            row.put("source_id", sourceId);
            row.put("batch_id", batchId == null ? "" : batchId);
            row.put("chunk_index", chunkIndex);
            row.put("chunk_item_count", chunkItemCount);
            row.put("item_sequence", itemSequence);
            row.put("item_id", itemId == null ? "" : itemId);
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

        private static int asInt(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null) {
                return 0;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        long serializedSizeBytes() {
            return toJsonEachRow().getBytes(StandardCharsets.UTF_8).length + 1L;
        }

        private static Object firstNonNull(Object first, Object second) {
            return first != null ? first : second;
        }

        private static String compactEventJson(
                LogEvent event,
                Map<String, Object> output,
                Map<?, ?> additional,
                Map<?, ?> common,
                String itemKind,
                String itemType,
                String itemKey,
                String batchId,
                int chunkIndex,
                int chunkItemCount,
                int itemSequence,
                String itemId,
                boolean includeOriginText
        ) {
            if (itemKind == null || !List.of("asset", "metric", "state", "event").contains(itemKind)
                    || output.get("subDomainType") != null) {
                return event.toOutputJson(includeOriginText);
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("schema_version", firstNonNull(output.get("schema_version"), additional.get("schema_version")));
            compact.put("source", firstNonNull(output.get("source"), additional.get("source")));
            compact.put("source_id", firstNonNull(output.get("source_id"), additional.get("source_id")));
            if (output.get("telemetry") != null) compact.put("telemetry", output.get("telemetry"));
            compact.put("tenant_id", firstNonNull(output.get("tenant_id"), additional.get("tenant_id")));
            compact.put("batch_id", batchId);
            compact.put("chunk_index", chunkIndex);
            compact.put("chunk_item_count", chunkItemCount);
            compact.put("item_sequence", itemSequence);
            compact.put("item_id", itemId);
            compact.put("observed_at", firstNonNull(output.get("observed_at"), additional.get("observed_at")));
            compact.put("sent_at", firstNonNull(output.get("sent_at"), additional.get("sent_at")));
            compact.put("item_kind", itemKind);
            compact.put("item_type", itemType);
            compact.put("item_key", itemKey);
            compact.put("payload", compactPayload(output, additional));
            Object eventTime = common.get("eventTime");
            if (eventTime != null) {
                compact.put("event_time", eventTime);
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(compact);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize compact agent event", e);
            }
        }

        private static Map<?, ?> compactPayload(Map<String, Object> output, Map<?, ?> additional) {
            Object payload = firstNonNull(output.get("payload"), additional.get("payload"));
            if (payload instanceof Map<?, ?> payloadMap) {
                return payloadMap;
            }

            Map<String, Object> flattened = new LinkedHashMap<>();
            copyFlattenedPayload(output, flattened);
            copyFlattenedPayload(additional, flattened);
            return flattened;
        }

        private static void copyFlattenedPayload(Map<?, ?> source, Map<String, Object> target) {
            source.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (name.startsWith("payload_") && name.length() > "payload_".length()) {
                    target.putIfAbsent(name.substring("payload_".length()), value);
                }
            });
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
