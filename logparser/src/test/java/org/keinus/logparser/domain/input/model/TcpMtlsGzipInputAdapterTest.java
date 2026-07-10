package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class TcpMtlsGzipInputAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    void convertsBatchItemsToLogEvents() throws Exception {
        JsonNode batch = OBJECT_MAPPER.readTree("""
                {
                  "schema_version": "1.1",
                  "batch_id": "batch-01",
                  "chunk_index": 0,
                  "chunk_count": 1,
                  "source": "agent",
                  "source_id": "agent-01",
                  "tenant_id": "tenant-01",
                  "observed_at": "2026-06-07T00:00:00Z",
                  "sent_at": "2026-06-07T00:00:01Z",
                  "items": [
                    {"item_id":"batch-01:0","sequence":0,"kind":"event","type":"health","key":"heartbeat","payload":{"status":"ok","observed_at":"2026-06-07T00:00:00.500Z","process-id":1234}}
                  ]
                }
                """);

        List<LogEvent> events = TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-01", "castrelyx-agent-item");

        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("castrelyx-agent-item", event.getMessageType());
        assertEquals("agent-01", event.getSourceHost());
        assertEquals("1.1", event.getField("schema_version"));
        assertEquals("batch-01", event.getField("batch_id"));
        assertEquals(0, event.getField("chunk_index"));
        assertEquals(1, event.getField("chunk_count"));
        assertEquals(1, event.getField("chunk_item_count"));
        assertEquals("agent", event.getField("source"));
        assertEquals("agent-01", event.getField("source_id"));
        assertEquals("tenant-01", event.getField("tenant_id"));
        assertEquals("event", event.getField("item_kind"));
        assertEquals("health", event.getField("item_type"));
        assertEquals("heartbeat", event.getField("item_key"));
        assertEquals("batch-01:0", event.getField("item_id"));
        assertEquals(0, event.getField("item_sequence"));
        assertEquals("ok", event.getField("payload_status"));
        assertEquals("2026-06-07T00:00:00.500Z", event.getField("payload_observed_at"));
        assertEquals(1234, event.getField("payload_process_id"));
        assertTrue(event.getOriginalText().contains("\"type\":\"health\""));
    }

    @Test
    void rejectsBatchWhenSourceIdDoesNotMatchClientCertificateCn() throws Exception {
        JsonNode batch = OBJECT_MAPPER.readTree("""
                {"source_id":"agent-01","items":[]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-02", "castrelyx-agent-item"));

        assertTrue(error.getMessage().contains("source_id"));
    }

    @Test
    void rejectsBatchWithNonArrayItems() throws Exception {
        JsonNode batch = OBJECT_MAPPER.readTree("""
                {"source_id":"agent-01","items":{}}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-01", "castrelyx-agent-item"));

        assertTrue(error.getMessage().contains("items"));
    }

    @Test
    void validatesSchema11ChunkAndItemIdentityMetadata() throws Exception {
        List<String> invalidBatches = List.of(
                """
                        {"schema_version":"1.1","source_id":"agent-01","chunk_index":0,"chunk_count":1,"items":[]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":0,"items":[]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":1,"chunk_count":1,"items":[]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":-1,"chunk_count":1,"items":[]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":1,"items":[{"item_id":" ","sequence":0}]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":1,"items":[{"item_id":"batch:0","sequence":-1}]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":1,"items":[{"item_id":"batch:0","sequence":0},{"item_id":"batch:1","sequence":0}]}
                        """,
                """
                        {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":1}
                        """
        );

        for (String raw : invalidBatches) {
            JsonNode batch = OBJECT_MAPPER.readTree(raw);
            assertThrows(IllegalArgumentException.class,
                    () -> TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-01", "castrelyx-agent-item"),
                    raw);
        }
    }

    @Test
    void rejectsOversizedBatchAndItemIdentifiersAndUsesFixedLengthRecentKey() throws Exception {
        JsonNode oversizedBatchId = OBJECT_MAPPER.readTree("""
                {"schema_version":"1.1","source_id":"agent-01","batch_id":"%s","chunk_index":0,"chunk_count":1,"items":[]}
                """.formatted("x".repeat(129)));
        assertThrows(IllegalArgumentException.class,
                () -> TcpMtlsGzipInputAdapter.toLogEvents(
                        oversizedBatchId,
                        "agent-01",
                        "castrelyx-agent-item"));

        JsonNode oversizedItemId = OBJECT_MAPPER.readTree("""
                {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch","chunk_index":0,"chunk_count":1,
                 "items":[{"item_id":"%s","sequence":0}]}
                """.formatted("i".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> TcpMtlsGzipInputAdapter.toLogEvents(
                        oversizedItemId,
                        "agent-01",
                        "castrelyx-agent-item"));

        JsonNode valid = OBJECT_MAPPER.readTree("""
                {"schema_version":"1.1","source_id":"agent-01","batch_id":"batch-sensitive-value","chunk_index":3,"chunk_count":4,
                 "items":[{"item_id":"batch-sensitive-value:0","sequence":0}]}
                """);
        List<LogEvent> events = TcpMtlsGzipInputAdapter.toLogEvents(
                valid,
                "agent-01",
                "castrelyx-agent-item");
        String recentKey = TcpMtlsGzipInputAdapter.recentBatchKey(events);

        assertNotNull(recentKey);
        assertEquals(64, recentKey.length());
        assertFalse(recentKey.contains("batch-sensitive-value"));
    }

    @Test
    void boundsConcurrentConnectionsAndRebindsListenerWhenPkcs12Changes() throws Exception {
        Path keyStore = tempDir.resolve("server.p12");
        Path trustStore = tempDir.resolve("truststore.p12");
        char[] password = "secret".toCharArray();
        writeEmptyPkcs12(keyStore, password);
        writeEmptyPkcs12(trustStore, password);

        InputAdapterConfig config = new InputAdapterConfig();
        config.setPort(0);
        config.setQueueSize(10);
        config.setWorkerThreads(9);
        config.setTimeoutMs(1_000);
        config.setMessagetype("castrelyx-agent-item");
        config.setConfigParams("""
                {"keyStorePath":"%s","keyStorePasswordEnv":"KEY_PASSWORD",
                 "trustStorePath":"%s","trustStorePasswordEnv":"TRUST_PASSWORD",
                 "maxConnections":2,"tlsReloadIntervalMs":1}
                """.formatted(jsonPath(keyStore), jsonPath(trustStore)));

        try (TcpMtlsGzipInputAdapter adapter = new TcpMtlsGzipInputAdapter(config, ignored -> "secret")) {
            assertEquals(2, adapter.configuredMaxConnections());
            assertTrue(adapter.listeningPort() > 0);
            long generation = adapter.listenerGeneration();
            byte[] previous = Files.readAllBytes(keyStore);

            writeEmptyPkcs12(keyStore, password);

            assertFalse(java.util.Arrays.equals(previous, Files.readAllBytes(keyStore)));
            assertTrue(adapter.reloadTlsListenerIfChanged());
            assertEquals(generation + 1, adapter.listenerGeneration());
            assertTrue(adapter.listeningPort() > 0);
            assertFalse(adapter.reloadTlsListenerIfChanged(), "unchanged stores must not rebind repeatedly");
        }
    }

    @Test
    void keepsSchema10MetadataOptionalWhileExposingChunkItemCount() throws Exception {
        JsonNode batch = OBJECT_MAPPER.readTree("""
                {
                  "schema_version":"1.0",
                  "source_id":"agent-01",
                  "items":[{"kind":"event","type":"legacy","key":"one","payload":{"ok":true}}]
                }
                """);

        List<LogEvent> events = TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-01", "castrelyx-agent-item");

        assertEquals(1, events.size());
        assertNull(events.getFirst().getField("batch_id"));
        assertNull(events.getFirst().getField("chunk_index"));
        assertEquals(1, events.getFirst().getField("chunk_item_count"));
    }

    @Test
    void rejectsCompleteBatchWithoutPartiallyEnqueueing() {
        LinkedBlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>(1);
        List<LogEvent> events = List.of(
                new LogEvent("one", "agent-01", "agent"),
                new LogEvent("two", "agent-01", "agent"));

        boolean accepted = TcpMtlsGzipInputAdapter.enqueueBatchAtomically(
                queue, events, "batch-01:0", new Object(), new LinkedHashMap<>());

        assertFalse(accepted);
        assertTrue(queue.isEmpty());
    }

    @Test
    void acceptsDuplicateBatchWithoutEnqueueingItTwice() {
        LinkedBlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>(4);
        List<LogEvent> events = List.of(new LogEvent("one", "agent-01", "agent"));
        Object lock = new Object();
        LinkedHashMap<String, Boolean> recent = new LinkedHashMap<>();

        assertTrue(TcpMtlsGzipInputAdapter.enqueueBatchAtomically(queue, events, "batch-01:0", lock, recent));
        assertTrue(TcpMtlsGzipInputAdapter.enqueueBatchAtomically(queue, events, "batch-01:0", lock, recent));
        assertEquals(1, queue.size());
    }

    @Test
    void writesAckAsValidJsonAndPreservesProtocolFields() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        TcpMtlsGzipInputAdapter.writeAck(output);

        String response = output.toString(StandardCharsets.UTF_8);
        JsonNode parsed = OBJECT_MAPPER.readTree(response);
        assertTrue(response.endsWith("\n"));
        assertEquals("accepted", parsed.get("status").asText());
        assertEquals(1, parsed.size());
    }

    @Test
    void writesNackControlCharactersAsValidJsonAndPreservesProtocolFields() throws Exception {
        String message = "first line\nsecond\t\"quoted\"\\path\r\b\f\u0000\u001f";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        TcpMtlsGzipInputAdapter.writeNack(output, "bad_\tframe", message);

        String response = output.toString(StandardCharsets.UTF_8);
        JsonNode parsed = OBJECT_MAPPER.readTree(response);
        assertTrue(response.endsWith("\n"));
        assertEquals("error", parsed.get("status").asText());
        assertEquals("bad_\tframe", parsed.get("code").asText());
        assertEquals(message, parsed.get("message").asText());
        assertEquals(3, parsed.size());
    }

    private static void writeEmptyPkcs12(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        try (var output = Files.newOutputStream(path)) {
            store.store(output, password);
        }
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }
}
