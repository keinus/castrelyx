package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseOutputAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    void parsesConfigParamsAndResolvesCredentialsFromEnvironment() throws Exception {
        Map<String, String> rawConfig = Map.of(
                "configParams", """
                        {"endpointUrl":"http://clickhouse:8123","database":"castrelyx","usernameEnv":"CLICKHOUSE_USER","passwordEnv":"CLICKHOUSE_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
                        """
        );
        Map<String, String> env = Map.of(
                "CLICKHOUSE_USER", "castrelyx",
                "CLICKHOUSE_PASSWORD", "secret"
        );

        ClickHouseOutputAdapter.ClickHouseConfig config = ClickHouseOutputAdapter.ClickHouseConfig.from(rawConfig, env::get);

        assertEquals("http://clickhouse:8123", config.endpointUrl());
        assertEquals("castrelyx", config.database());
        assertEquals("castrelyx", config.username());
        assertEquals("secret", config.password());
        assertEquals("castrelyx_agent_events", config.tableName());
        assertEquals("manager_metric_samples", config.metricTableName());
        assertEquals("manager_state_snapshots", config.stateTableName());
        assertEquals("manager_events", config.eventTableName());
        assertEquals("castrelyx.castrelyx_agent_events", config.tableReference());
        assertEquals(100, config.batchSize());
        assertEquals(5000, config.flushIntervalMs());
        assertEquals(30_000, config.incompleteGroupTimeoutMs());
        assertEquals(2_048, config.maxPendingGroups());
        assertEquals(50_000, config.maxPendingItems());
        assertEquals(64L * 1024 * 1024, config.maxPendingBytes());
        assertEquals(128L * 1024 * 1024, config.maxIncompleteChunkDlqBytes());
        assertEquals(1_000, config.maxIncompleteChunkDlqRecords());
        assertTrue(config.autoCreateSchema());
        assertTrue(config.writeTelemetryTables());
    }

    @Test
    void createsEventRecordFromLogEventFields() {
        LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
        event.setField("source_id", "agent-01");
        event.setField("tenant_id", "tenant-01");
        event.setField("batch_id", "batch-01");
        event.setField("chunk_index", 2);
        event.setField("chunk_item_count", 3);
        event.setField("item_sequence", 7);
        event.setField("item_kind", "event");
        event.setField("item_type", "health");
        event.setField("item_key", "heartbeat");

        ClickHouseOutputAdapter.EventRecord record = ClickHouseOutputAdapter.EventRecord.from(event, false);

        assertEquals("agent-01", record.agentId());
        assertEquals("tenant-01", record.tenantId());
        assertEquals("agent-01", record.sourceId());
        assertEquals("batch-01", record.batchId());
        assertEquals(2, record.chunkIndex());
        assertEquals(3, record.chunkItemCount());
        assertEquals(7, record.itemSequence());
        assertEquals("event", record.itemKind());
        assertEquals("health", record.itemType());
        assertEquals("heartbeat", record.itemKey());
        assertTrue(record.eventJson().contains("\"source_id\":\"agent-01\""));
    }

    @Test
    void createsEventRecordFromStructuredLogEventFields() {
        LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
        event.setFields(Map.of(
                "common", Map.of(
                        "srcHost", "agent-01",
                        "eventCategory", "event",
                        "eventType", "health",
                        "eventAction", "heartbeat"
                ),
                "additionalAttributes", Map.of(
                        "tenant_id", "tenant-01",
                        "payload", Map.of("status", "ok")
                )
        ));
        event.markAsTransformed();

        ClickHouseOutputAdapter.EventRecord record = ClickHouseOutputAdapter.EventRecord.from(event, false);

        assertEquals("agent-01", record.agentId());
        assertEquals("tenant-01", record.tenantId());
        assertEquals("agent-01", record.sourceId());
        assertEquals("event", record.itemKind());
        assertEquals("health", record.itemType());
        assertEquals("heartbeat", record.itemKey());
        assertTrue(record.eventJson().contains("\"payload\":{\"status\":\"ok\"}"));
    }

    @Test
    void sendsCreateSchemaRawBatchAndTransformedTelemetryRowsToClickHouseHttpEndpoint() throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> rawConfig = Map.of(
                    "configParams", """
                            {"endpointUrl":"%s","database":"default","tableName":"castrelyx_agent_events","batchSize":10,"flushIntervalMs":60000,"autoCreateSchema":true}
                            """.formatted(endpointUrl)
            );
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(rawConfig);
            LogEvent metric = new LogEvent("{\"type\":\"metric\"}", "agent-01", "castrelyx-agent-item");
            metric.setField("source_id", "agent-01");
            metric.setField("batch_id", "batch-01");
            metric.setField("item_sequence", 0);
            metric.setField("item_id", "batch-01:0");
            metric.setField("tenant_id", "tenant-01");
            metric.setField("item_kind", "metric");
            metric.setField("item_type", "cpu");
            metric.setField("item_key", "cpu.total");
            metric.setField("payload_asset_uid", "agent-01");
            metric.setField("payload_metric_name", "cpu.usage");
            metric.setField("payload_metric_value", 87.5);
            metric.setField("payload_unit", "percent");
            metric.setField("payload_collector", "agent-metrics");
            LogEvent state = new LogEvent("{\"type\":\"state\"}", "agent-01", "castrelyx-agent-item");
            state.setField("source_id", "agent-01");
            state.setField("item_kind", "state");
            state.setField("item_type", "process");
            state.setField("item_key", "pid-100");
            state.setField("payload", Map.of("pid", 100, "name", "java", "status", "running"));
            LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
            event.setField("source_id", "agent-01");
            event.setField("item_kind", "event");
            event.setField("item_type", "health");
            event.setField("item_key", "heartbeat");
            event.setField("payload", Map.of("event_type", "health", "severity", "INFO", "message", "ok"));

            adapter.send(metric);
            adapter.send(state);
            adapter.send(event);
            adapter.close();

            assertTrue(requests.size() >= 8);
            CapturedRequest rawCreate = requestContaining(requests, "CREATE+TABLE+IF+NOT+EXISTS+default.castrelyx_agent_events");
            assertTrue(rawCreate.query().contains("PARTITION+BY+toDate%28received_at%29"));
            assertTrue(rawCreate.query().contains("TTL+toDateTime%28received_at%29+%2B+INTERVAL+7+DAY+DELETE"));
            assertEquals("", rawCreate.body());
            assertTrue(requestContaining(requests, "ALTER+TABLE+default.castrelyx_agent_events+MODIFY+TTL").query()
                    .contains("INTERVAL+7+DAY+DELETE"));
            assertTrue(requestContaining(requests, "ALTER+TABLE+default.castrelyx_agent_events+MODIFY+SETTING+non_replicated_deduplication_window").query()
                    .contains("4096"));
            assertTrue(requestContaining(requests, "ADD+COLUMN+IF+NOT+EXISTS+chunk_index").query()
                    .contains("UInt32"));
            assertTrue(requestContaining(requests, "ADD+COLUMN+IF+NOT+EXISTS+chunk_item_count").query()
                    .contains("UInt32"));
            assertTrue(requestContaining(requests, "CREATE+TABLE+IF+NOT+EXISTS+default.manager_metric_samples").query()
                    .contains("TTL+toDateTime%28observed_at%29+%2B+INTERVAL+30+DAY+DELETE"));
            assertTrue(requestContaining(requests, "CREATE+TABLE+IF+NOT+EXISTS+default.manager_state_snapshots").query()
                    .contains("PARTITION+BY+toDate%28observed_at%29"));
            assertTrue(requestContaining(requests, "CREATE+TABLE+IF+NOT+EXISTS+default.manager_events").query()
                    .contains("idx_event_type"));
            assertTrue(requestContaining(requests, "ADD+INDEX+IF+NOT+EXISTS+idx_metric_name").query()
                    .contains("TYPE+set%284096%29"));

            CapturedRequest rawInsert = requestContaining(requests, "INSERT+INTO+default.castrelyx_agent_events");
            assertTrue(rawInsert.query().contains("FORMAT+JSONEachRow"));
            assertTrue(rawInsert.query().contains("insert_deduplication_token="));
            assertTrue(rawInsert.body().contains("\"agent_id\":\"agent-01\""));
            assertTrue(rawInsert.body().contains("\"tenant_id\":\"tenant-01\""));
            assertTrue(rawInsert.body().contains("\"batch_id\":\"batch-01\""));
            assertTrue(rawInsert.body().contains("\"chunk_index\":0"));
            assertTrue(rawInsert.body().contains("\"chunk_item_count\":0"));
            assertTrue(rawInsert.body().contains("\"item_id\":\"batch-01:0\""));
            assertTrue(rawInsert.body().contains("\"event_json\""));
            assertEquals(3, rawInsert.body().lines().count());

            CapturedRequest metricInsert = requestContaining(requests, "INSERT+INTO+default.manager_metric_samples");
            assertTrue(metricInsert.query().contains("insert_deduplication_token="));
            assertTrue(metricInsert.body().contains("\"metric_name\":\"cpu.usage\""));
            assertTrue(metricInsert.body().contains("\"metric_value\":87.5"));
            assertTrue(metricInsert.body().contains("\"labels_json\":\"{\\\"collector\\\":\\\"agent-metrics\\\"}\""));

            CapturedRequest stateInsert = requestContaining(requests, "INSERT+INTO+default.manager_state_snapshots");
            assertTrue(stateInsert.body().contains("\"state_type\":\"process\""));
            assertTrue(stateInsert.body().contains("\"state_key\":\"pid-100\""));

            CapturedRequest eventInsert = requestContaining(requests, "INSERT+INTO+default.manager_events");
            assertTrue(eventInsert.body().contains("\"event_type\":\"health\""));
            assertTrue(eventInsert.body().contains("\"severity\":\"INFO\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void flushesCompleteChunkInSequenceOrderWithStableRetryBodyAndToken() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> config = chunkConfig(endpointUrl, 60_000, 60_000, 100, 1_000);

            ClickHouseOutputAdapter first = new ClickHouseOutputAdapter(config);
            first.send(chunkEvent("batch-stable", 1, 3, 2));
            first.send(chunkEvent("batch-stable", 1, 3, 0));
            assertEquals(0, rawInserts(requests).size(), "incomplete chunk must not flush");
            first.send(chunkEvent("batch-stable", 1, 3, 1));
            first.close();

            ClickHouseOutputAdapter retry = new ClickHouseOutputAdapter(config);
            for (int sequence : List.of(1, 0, 2)) {
                LogEvent retryEvent = chunkEvent("batch-stable", 1, 3, sequence);
                retryEvent.setField("sent_at", "2026-07-10T00:01:00Z");
                retry.send(retryEvent);
            }
            retry.close();

            List<CapturedRequest> inserts = rawInserts(requests);
            assertEquals(2, inserts.size());
            assertNotEquals(inserts.get(0).body(), inserts.get(1).body(), "test must vary retry metadata");
            assertEquals(deduplicationToken(inserts.get(0)), deduplicationToken(inserts.get(1)),
                    "retry deduplication token must be stable even when sent_at changes");

            List<String> lines = inserts.getFirst().body().lines().toList();
            assertEquals(3, lines.size());
            for (int sequence = 0; sequence < lines.size(); sequence++) {
                var row = OBJECT_MAPPER.readTree(lines.get(sequence));
                assertEquals(sequence, row.path("item_sequence").asInt());
                assertEquals(1, row.path("chunk_index").asInt());
                assertEquals(3, row.path("chunk_item_count").asInt());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void insertsDistinctChunkGroupsSeparately() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(
                    chunkConfig(endpointUrl, 60_000, 60_000, 100, 1_000));
            adapter.send(chunkEvent("batch-two-chunks", 0, 1, 0));
            adapter.send(chunkEvent("batch-two-chunks", 1, 1, 1));
            adapter.close();

            List<CapturedRequest> inserts = rawInserts(requests);
            assertEquals(2, inserts.size());
            assertEquals(1, inserts.get(0).body().lines().count());
            assertEquals(1, inserts.get(1).body().lines().count());
            assertNotEquals(deduplicationToken(inserts.get(0)), deduplicationToken(inserts.get(1)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forceFlushesIncompleteGroupsOnMemoryPressureAndTimeout() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        ClickHouseOutputAdapter adapter = null;
        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            adapter = new ClickHouseOutputAdapter(chunkConfig(endpointUrl, 10, 50, 1, 10));
            adapter.send(chunkEvent("batch-memory", 0, 2, 0));
            adapter.send(chunkEvent("batch-timeout", 0, 2, 0));

            List<CapturedRequest> afterPressure = rawInserts(requests);
            assertEquals(1, afterPressure.size(), "second group must force-flush the oldest group");
            assertTrue(afterPressure.getFirst().body().contains("batch-memory"));

            waitForRawInsertCount(requests, 2, 2_000);
            List<CapturedRequest> afterTimeout = rawInserts(requests);
            assertEquals(2, afterTimeout.size());
            assertTrue(afterTimeout.get(1).body().contains("batch-timeout"));
        } finally {
            if (adapter != null) {
                adapter.close();
            }
            server.stop(0);
        }
    }

    @Test
    void closeForceFlushesIncompleteChunkGroup() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(
                    chunkConfig(endpointUrl, 60_000, 60_000, 100, 1_000));
            adapter.send(chunkEvent("batch-close", 0, 2, 0));
            assertEquals(0, rawInserts(requests).size());
            adapter.close();

            List<CapturedRequest> inserts = rawInserts(requests);
            assertEquals(1, inserts.size());
            assertTrue(inserts.getFirst().body().contains("batch-close"));
            assertEquals(1, inserts.getFirst().body().lines().count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void quarantinesIncompleteChunkWithoutWritingCanonicalTelemetry() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        Path dlq = tempDir.resolve("canonical-safety-dlq");
        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> config = Map.of("configParams", """
                    {"endpointUrl":"%s","database":"default","tableName":"chunk_events","batchSize":100,
                     "flushIntervalMs":60000,"incompleteGroupTimeoutMs":60000,"maxPendingGroups":100,
                     "maxPendingItems":1000,"maxPendingBytes":67108864,"incompleteChunkDlqDir":"%s",
                     "maxIncompleteChunkDlqBytes":134217728,"maxIncompleteChunkDlqRecords":1000,
                     "autoCreateSchema":false,"writeTelemetryTables":true}
                    """.formatted(endpointUrl, jsonPath(dlq)));
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(config);
            adapter.send(chunkEvent("batch-incomplete", 0, 2, 0));
            adapter.close();

            assertEquals(1, rawInserts(requests).size(), "raw audit insert remains best effort");
            assertTrue(requests.stream().noneMatch(request ->
                    request.query().contains("manager_metric_samples")
                            || request.query().contains("manager_state_snapshots")
                            || request.query().contains("manager_events")));
            try (var files = Files.list(dlq)) {
                List<Path> records = files.filter(path -> path.toString().endsWith(".json")).toList();
                assertEquals(1, records.size());
                String quarantined = Files.readString(records.getFirst());
                assertTrue(quarantined.contains("batch-incomplete"));
                assertTrue(quarantined.contains("expected_item_count"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesPendingByteLimitUsingSerializedUtf8Size() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        Path dlq = tempDir.resolve("byte-limit-dlq");
        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> config = Map.of("configParams", """
                    {"endpointUrl":"%s","database":"default","tableName":"chunk_events","batchSize":100,
                     "flushIntervalMs":60000,"incompleteGroupTimeoutMs":60000,"maxPendingGroups":100,
                     "maxPendingItems":1000,"maxPendingBytes":256,"incompleteChunkDlqDir":"%s",
                     "maxIncompleteChunkDlqBytes":1048576,"maxIncompleteChunkDlqRecords":10,
                     "autoCreateSchema":false,"writeTelemetryTables":false}
                    """.formatted(endpointUrl, jsonPath(dlq)));
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(config);
            LogEvent oversized = chunkEvent("batch-byte-limit", 0, 2, 0);
            oversized.setField("payload", Map.of("value", "한".repeat(512)));

            adapter.send(oversized);
            adapter.close();

            assertEquals(1, rawInserts(requests).size());
            try (var files = Files.list(dlq)) {
                assertEquals(1, files.filter(path -> path.toString().endsWith(".json")).count());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void flushesLegacyBufferBeforeChunkWhenSharedByteLimitWouldBeExceeded() throws Exception {
        List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        Path dlq = tempDir.resolve("legacy-chunk-byte-limit-dlq");
        try {
            LogEvent legacy = new LogEvent("legacy", "agent-01", "castrelyx-agent-item");
            legacy.setField("source_id", "agent-01");
            legacy.setField("item_kind", "event");
            legacy.setField("item_type", "legacy");
            legacy.setField("item_key", "legacy");
            legacy.setField("payload", Map.of("value", "l".repeat(256)));
            LogEvent chunk = chunkEvent("batch-after-legacy", 0, 2, 0);
            long legacyBytes = ClickHouseOutputAdapter.EventRecord.from(legacy, false).serializedSizeBytes();
            long chunkBytes = ClickHouseOutputAdapter.EventRecord.from(chunk, false).serializedSizeBytes();
            long byteLimit = Math.max(legacyBytes, chunkBytes) + 8;
            assertTrue(legacyBytes + chunkBytes > byteLimit);

            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> config = Map.of("configParams", """
                    {"endpointUrl":"%s","database":"default","tableName":"chunk_events","batchSize":100,
                     "flushIntervalMs":60000,"incompleteGroupTimeoutMs":60000,"maxPendingGroups":100,
                     "maxPendingItems":1000,"maxPendingBytes":%d,"incompleteChunkDlqDir":"%s",
                     "maxIncompleteChunkDlqBytes":1048576,"maxIncompleteChunkDlqRecords":10,
                     "autoCreateSchema":false,"writeTelemetryTables":false}
                    """.formatted(endpointUrl, byteLimit, jsonPath(dlq)));
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(config);

            adapter.send(legacy);
            assertEquals(0, rawInserts(requests).size());
            assertDoesNotThrow(() -> adapter.send(chunk));
            assertEquals(1, rawInserts(requests).size(), "legacy buffer must flush before retaining the chunk");
            adapter.close();

            assertEquals(2, rawInserts(requests).size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteChunkDlqPrunesOldestRecordsAndRejectsOversizedRecord() throws Exception {
        Path directory = tempDir.resolve("bounded-dlq");
        ClickHouseOutputAdapter.IncompleteChunkDlq dlq =
                new ClickHouseOutputAdapter.IncompleteChunkDlq(directory, 1_000_000, 2);
        String row = "{\"agent_id\":\"agent\",\"event_json\":\"value\"}";

        Path first = dlq.persist("agent", "batch-1", 0, 2, "timeout", List.of(row));
        Thread.sleep(2);
        dlq.persist("agent", "batch-2", 0, 2, "timeout", List.of(row));
        Thread.sleep(2);
        dlq.persist("agent", "batch-3", 0, 2, "timeout", List.of(row));

        assertTrue(Files.notExists(first));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.filter(path -> path.toString().endsWith(".json")).count());
        }

        ClickHouseOutputAdapter.IncompleteChunkDlq tiny =
                new ClickHouseOutputAdapter.IncompleteChunkDlq(tempDir.resolve("tiny-dlq"), 128, 10);
        assertThrows(IOException.class, () -> tiny.persist(
                "agent",
                "batch-large",
                0,
                2,
                "timeout",
                List.of("{\"value\":\"" + "x".repeat(1_000) + "\"}")));
    }

    private Map<String, String> chunkConfig(
            String endpointUrl,
            int flushIntervalMs,
            int incompleteGroupTimeoutMs,
            int maxPendingGroups,
            int maxPendingItems
    ) {
        return Map.of(
                "configParams", """
                        {"endpointUrl":"%s","database":"default","tableName":"chunk_events","batchSize":100,
                         "flushIntervalMs":%d,"incompleteGroupTimeoutMs":%d,"maxPendingGroups":%d,"maxPendingItems":%d,
                         "maxPendingBytes":67108864,"incompleteChunkDlqDir":"%s",
                         "maxIncompleteChunkDlqBytes":134217728,"maxIncompleteChunkDlqRecords":1000,
                         "autoCreateSchema":false,"writeTelemetryTables":false}
                        """.formatted(
                        endpointUrl,
                        flushIntervalMs,
                        incompleteGroupTimeoutMs,
                        maxPendingGroups,
                        maxPendingItems,
                        jsonPath(tempDir.resolve("chunk-dlq"))
                )
        );
    }

    private LogEvent chunkEvent(String batchId, int chunkIndex, int chunkItemCount, int itemSequence) {
        LogEvent event = new LogEvent("{\"type\":\"chunk\"}", "agent-01", "castrelyx-agent-item");
        event.setField("schema_version", "1.1");
        event.setField("source", "agent");
        event.setField("source_id", "agent-01");
        event.setField("tenant_id", "tenant-01");
        event.setField("batch_id", batchId);
        event.setField("chunk_index", chunkIndex);
        event.setField("chunk_item_count", chunkItemCount);
        event.setField("item_sequence", itemSequence);
        event.setField("item_id", batchId + ":" + itemSequence);
        event.setField("item_kind", "event");
        event.setField("item_type", "chunk");
        event.setField("item_key", "item-" + itemSequence);
        event.setField("observed_at", "2026-07-10T00:00:00Z");
        event.setField("sent_at", "2026-07-10T00:00:01Z");
        event.setField("payload", Map.of("value", itemSequence));
        return event;
    }

    private List<CapturedRequest> rawInserts(List<CapturedRequest> requests) {
        synchronized (requests) {
            return requests.stream()
                    .filter(request -> request.query().contains("INSERT+INTO+default.chunk_events"))
                    .toList();
        }
    }

    private String deduplicationToken(CapturedRequest request) {
        for (String part : request.query().split("&")) {
            if (part.startsWith("insert_deduplication_token=")) {
                return part.substring("insert_deduplication_token=".length());
            }
        }
        fail("insert_deduplication_token is missing from " + request.query());
        return "";
    }

    private void waitForRawInsertCount(List<CapturedRequest> requests, int expected, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (rawInserts(requests).size() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + expected + " raw inserts; got " + rawInserts(requests).size());
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private CapturedRequest requestContaining(List<CapturedRequest> requests, String queryPart) {
        return requests.stream()
                .filter(request -> request.query().contains(queryPart))
                .findFirst()
                .orElseThrow();
    }

    private void handle(HttpExchange exchange, List<CapturedRequest> requests) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestURI().getRawQuery(), body));
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    private record CapturedRequest(String query, String body) {
    }
}
