package org.keinus.logparser.domain.output.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseOutputAdapterTest {

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
        assertTrue(config.autoCreateSchema());
        assertTrue(config.writeTelemetryTables());
    }

    @Test
    void createsEventRecordFromLogEventFields() {
        LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
        event.setField("source_id", "agent-01");
        event.setField("tenant_id", "tenant-01");
        event.setField("item_kind", "event");
        event.setField("item_type", "health");
        event.setField("item_key", "heartbeat");

        ClickHouseOutputAdapter.EventRecord record = ClickHouseOutputAdapter.EventRecord.from(event, false);

        assertEquals("agent-01", record.agentId());
        assertEquals("tenant-01", record.tenantId());
        assertEquals("agent-01", record.sourceId());
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
        assertTrue(record.eventJson().contains("\"common\""));
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
            assertTrue(rawInsert.body().contains("\"agent_id\":\"agent-01\""));
            assertTrue(rawInsert.body().contains("\"tenant_id\":\"tenant-01\""));
            assertTrue(rawInsert.body().contains("\"event_json\""));
            assertEquals(3, rawInsert.body().lines().count());

            CapturedRequest metricInsert = requestContaining(requests, "INSERT+INTO+default.manager_metric_samples");
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
