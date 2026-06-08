package org.keinus.logparser.domain.input.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class SnmpInputAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void runPollsConfiguredTargetsAndReturnsJsonEvent() throws Exception {
        InputAdapterConfig config = snmpConfig();
        SnmpInputAdapter.SnmpClient client = (target, oids, timeoutMs, retries) -> {
            assertEquals("192.0.2.10", target.host());
            assertEquals(161, target.port());
            assertEquals("public", target.community());
            assertEquals(List.of("1.3.6.1.2.1.1.5.0"), oids.stream().map(SnmpInputAdapter.SnmpOid::oid).toList());
            assertEquals(1500, timeoutMs);
            assertEquals(1, retries);
            return Map.of("sysName", "sw-core-01");
        };

        SnmpInputAdapter adapter = new SnmpInputAdapter(config, client);

        LogEvent event = adapter.run();

        assertNotNull(event);
        assertEquals("snmp-metrics", event.getMessageType());
        assertEquals("192.0.2.10", event.getSourceHost());

        Map<String, Object> payload = readPayload(event);
        assertEquals("snmp", payload.get("protocol"));
        assertEquals("success", payload.get("poll_status"));
        assertEquals("sw-core-01", payload.get("target_name"));
        assertEquals(Map.of("sysName", "sw-core-01"), payload.get("metrics"));
        assertNull(adapter.run());
    }

    @Test
    void runReturnsErrorEventWhenTargetPollFails() throws Exception {
        InputAdapterConfig config = snmpConfig();
        SnmpInputAdapter.SnmpClient client = (target, oids, timeoutMs, retries) -> {
            throw new java.io.IOException("timeout");
        };

        SnmpInputAdapter adapter = new SnmpInputAdapter(config, client);

        LogEvent event = adapter.run();

        assertNotNull(event);
        Map<String, Object> payload = readPayload(event);
        assertEquals("error", payload.get("poll_status"));
        assertEquals("timeout", payload.get("error_message"));
    }

    @Test
    void runPollsSnmpV3TargetWithSecurityConfig() throws Exception {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(10L);
        config.setType("SnmpInputAdapter");
        config.setMessagetype("snmp-v3-metrics");
        config.setTimeoutMs(2500);
        config.setWorkerThreads(1);
        config.setQueueSize(10);
        config.setConfigParams("""
                {
                  "intervalMs": 60000,
                  "retries": 2,
                  "targets": [
                    {
                      "name": "fw-edge-01",
                      "host": "192.0.2.20",
                      "version": "3",
                      "securityName": "poller",
                      "securityLevel": "authPriv",
                      "authProtocol": "SHA256",
                      "authPassphrase": "auth-secret",
                      "privProtocol": "AES128",
                      "privPassphrase": "priv-secret"
                    }
                  ],
                  "oids": [
                    {"name": "sysName", "oid": "1.3.6.1.2.1.1.5.0"}
                  ]
                }
                """);
        SnmpInputAdapter.SnmpClient client = (target, oids, timeoutMs, retries) -> {
            assertEquals("3", target.version());
            assertEquals("poller", target.securityName());
            assertEquals("authPriv", target.securityLevel());
            assertEquals("SHA256", target.authProtocol());
            assertEquals("auth-secret", target.authPassphrase());
            assertEquals("AES128", target.privProtocol());
            assertEquals("priv-secret", target.privPassphrase());
            assertEquals(2500, timeoutMs);
            assertEquals(2, retries);
            return Map.of("sysName", "fw-edge-01");
        };

        SnmpInputAdapter adapter = new SnmpInputAdapter(config, client);

        LogEvent event = adapter.run();

        assertNotNull(event);
        assertEquals("snmp-v3-metrics", event.getMessageType());
        Map<String, Object> payload = readPayload(event);
        assertEquals("3", payload.get("version"));
        assertEquals("authPriv", payload.get("security_level"));
        assertEquals("poller", payload.get("security_name"));
        assertEquals("success", payload.get("poll_status"));
    }

    private InputAdapterConfig snmpConfig() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(9L);
        config.setType("SnmpInputAdapter");
        config.setMessagetype("snmp-metrics");
        config.setTimeoutMs(1500);
        config.setWorkerThreads(2);
        config.setQueueSize(10);
        config.setConfigParams("""
                {
                  "intervalMs": 60000,
                  "retries": 1,
                  "targets": [
                    {"name": "sw-core-01", "host": "192.0.2.10", "port": 161, "community": "public"}
                  ],
                  "oids": [
                    {"name": "sysName", "oid": "1.3.6.1.2.1.1.5.0"}
                  ]
                }
                """);
        return config;
    }

    private Map<String, Object> readPayload(LogEvent event) throws Exception {
        return OBJECT_MAPPER.readValue(event.getOriginalText(), new TypeReference<>() {});
    }
}
