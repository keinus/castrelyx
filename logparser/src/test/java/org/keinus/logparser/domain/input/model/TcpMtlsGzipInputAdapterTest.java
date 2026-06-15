package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TcpMtlsGzipInputAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void convertsBatchItemsToLogEvents() throws Exception {
        JsonNode batch = OBJECT_MAPPER.readTree("""
                {
                  "schema_version": "1.0",
                  "source": "agent",
                  "source_id": "agent-01",
                  "tenant_id": "tenant-01",
                  "observed_at": "2026-06-07T00:00:00Z",
                  "sent_at": "2026-06-07T00:00:01Z",
                  "items": [
                    {"kind":"event","type":"health","key":"heartbeat","payload":{"status":"ok","observed_at":"2026-06-07T00:00:00.500Z","process-id":1234}}
                  ]
                }
                """);

        List<LogEvent> events = TcpMtlsGzipInputAdapter.toLogEvents(batch, "agent-01", "castrelyx-agent-item");

        assertEquals(1, events.size());
        LogEvent event = events.get(0);
        assertEquals("castrelyx-agent-item", event.getMessageType());
        assertEquals("agent-01", event.getSourceHost());
        assertEquals("1.0", event.getField("schema_version"));
        assertEquals("agent", event.getField("source"));
        assertEquals("agent-01", event.getField("source_id"));
        assertEquals("tenant-01", event.getField("tenant_id"));
        assertEquals("event", event.getField("item_kind"));
        assertEquals("health", event.getField("item_type"));
        assertEquals("heartbeat", event.getField("item_key"));
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
}
