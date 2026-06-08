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
        assertEquals("castrelyx.castrelyx_agent_events", config.tableReference());
        assertEquals(100, config.batchSize());
        assertEquals(5000, config.flushIntervalMs());
        assertTrue(config.autoCreateSchema());
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
    void sendsCreateSchemaAndJsonEachRowBatchToClickHouseHttpEndpoint() throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> rawConfig = Map.of(
                    "configParams", """
                            {"endpointUrl":"%s","database":"default","tableName":"castrelyx_agent_events","batchSize":1,"flushIntervalMs":60000,"autoCreateSchema":true}
                            """.formatted(endpointUrl)
            );
            ClickHouseOutputAdapter adapter = new ClickHouseOutputAdapter(rawConfig);
            LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
            event.setField("source_id", "agent-01");
            event.setField("tenant_id", "tenant-01");
            event.setField("item_kind", "event");
            event.setField("item_type", "health");
            event.setField("item_key", "heartbeat");

            adapter.send(event);
            adapter.close();

            assertEquals(2, requests.size());
            assertTrue(requests.get(0).query().contains("CREATE+TABLE+IF+NOT+EXISTS+default.castrelyx_agent_events"));
            assertEquals("", requests.get(0).body());
            assertTrue(requests.get(1).query().contains("INSERT+INTO+default.castrelyx_agent_events"));
            assertTrue(requests.get(1).query().contains("FORMAT+JSONEachRow"));
            assertTrue(requests.get(1).body().contains("\"agent_id\":\"agent-01\""));
            assertTrue(requests.get(1).body().contains("\"tenant_id\":\"tenant-01\""));
            assertTrue(requests.get(1).body().contains("\"event_json\""));
            assertTrue(requests.get(1).body().endsWith("\n"));
        } finally {
            server.stop(0);
        }
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
