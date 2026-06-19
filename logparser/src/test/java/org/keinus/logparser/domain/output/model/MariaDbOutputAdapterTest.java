package org.keinus.logparser.domain.output.model;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MariaDbOutputAdapterTest {

    @Test
    void parsesConfigParamsAndResolvesCredentialsFromEnvironment() throws Exception {
        Map<String, String> rawConfig = Map.of(
                "configParams", """
                        {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
                        """
        );
        Map<String, String> env = Map.of(
                "CASTRELYX_DB_USER", "castrelyx",
                "CASTRELYX_DB_PASSWORD", "secret"
        );

        MariaDbOutputAdapter.MariaDbConfig config = MariaDbOutputAdapter.MariaDbConfig.from(rawConfig, env::get);

        assertEquals("jdbc:mariadb://mariadb:3306/castrelyx", config.jdbcUrl());
        assertEquals("castrelyx", config.username());
        assertEquals("secret", config.password());
        assertEquals("castrelyx_agent_events", config.tableName());
        assertEquals(100, config.batchSize());
        assertEquals(5000, config.flushIntervalMs());
        assertTrue(config.autoCreateSchema());
    }

    @Test
    void usesDefaultTableNameWhenConfigOmitsTableName() throws Exception {
        Map<String, String> rawConfig = Map.of(
                "configParams", """
                        {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD"}
                        """
        );
        Map<String, String> env = Map.of(
                "CASTRELYX_DB_USER", "castrelyx",
                "CASTRELYX_DB_PASSWORD", "secret"
        );

        MariaDbOutputAdapter.MariaDbConfig config = MariaDbOutputAdapter.MariaDbConfig.from(rawConfig, env::get);

        assertEquals("castrelyx_agent_events", config.tableName());
    }

    @Test
    void createsEventRecordFromLogEventFields() {
        LogEvent event = new LogEvent("{\"type\":\"health\"}", "agent-01", "castrelyx-agent-item");
        event.setField("source_id", "agent-01");
        event.setField("tenant_id", "tenant-01");
        event.setField("item_kind", "event");
        event.setField("item_type", "health");
        event.setField("item_key", "heartbeat");

        MariaDbOutputAdapter.EventRecord record = MariaDbOutputAdapter.EventRecord.from(event, false);

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

        MariaDbOutputAdapter.EventRecord record = MariaDbOutputAdapter.EventRecord.from(event, false);

        assertEquals("agent-01", record.agentId());
        assertEquals("tenant-01", record.tenantId());
        assertEquals("agent-01", record.sourceId());
        assertEquals("event", record.itemKind());
        assertEquals("health", record.itemType());
        assertEquals("heartbeat", record.itemKey());
        assertTrue(record.eventJson().contains("\"common\""));
    }
}
