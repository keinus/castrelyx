package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.output.model.ClickHouseOutputAdapter;
import org.keinus.logparser.domain.parse.model.JsonParser;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="TELEMETRY_TEST_CLICKHOUSE_URL", matches=".+")
class StructuredTelemetryClickHouseIntegrationTest {
    private final String endpoint = System.getenv("TELEMETRY_TEST_CLICKHOUSE_URL");
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void storesAgentAndSnmpStructuredRowsInTheSameTableWithTheSameAsset() throws Exception {
        String db = "structured_qa_" + UUID.randomUUID().toString().replace("-", "");
        query("CREATE DATABASE " + db);
        try {
            var service = new StructuredTransformService(mock(MappingRepository.class), new ConditionEvaluator(),
                    new StructuredEventSerializer(json), new TelemetryTransformService(
                    new TelemetryAssetBindings(Map.of("192.168.50.22", "nas"))));
            var config = Map.of("configParams", json.writeValueAsString(Map.of("endpointUrl", endpoint,
                    "database", db, "tableName", "raw", "autoCreateSchema", true,
                    "writeTelemetryTables", true, "batchSize", 100, "flushIntervalMs", 60000)));
            try (var output = new ClickHouseOutputAdapter(config)) {
                var agent = new LogEvent("{}", "nas", "agent");
                agent.setField("source_id", "nas").setField("item_kind", "metric");
                agent.setField("payload", Map.of("metric_name", "host.network.rx_bytes", "value", 100,
                        "unit", "bytes", "interface", "eth0"));
                var snmp = new LogEvent(json.writeValueAsString(Map.of("protocol", "snmp", "operation", "poll",
                        "target_host", "192.168.50.22", "metrics", Map.of("ifInOctets", Map.of(
                        "value", 200, "interface", "eth0", "syntax", "Counter32")))), "192.168.50.22", "snmp");
                assertThat(new JsonParser().parse(snmp)).isTrue();
                assertThat(service.applyToLogEvent(agent)).isTrue();
                assertThat(service.applyToLogEvent(snmp)).isTrue();
                output.send(agent);
                output.send(snmp);
                // An unstructured poll must not trigger hidden normalization in the output.
                var rawOnly = new LogEvent("{}", "192.168.50.22", "snmp");
                rawOnly.setField("protocol", "snmp").setField("target_host", "192.168.50.22")
                        .setField("metrics", Map.of("ifInOctets", Map.of("value", 999)));
                output.send(rawOnly);
            }
            String rows = query("SELECT asset_uid, source_type, metric_name, metric_value FROM " + db
                    + ".manager_metric_samples ORDER BY source_type FORMAT JSONEachRow");
            var parsed = rows.lines().map(line -> {
                try { return json.readTree(line); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(parsed).hasSize(2).allSatisfy(row -> {
                assertThat(row.path("asset_uid").asText()).isEqualTo("nas");
                assertThat(row.path("metric_name").asText()).isEqualTo("host.network.rx_bytes");
            });
            assertThat(parsed).extracting(row -> row.path("source_type").asText()).containsExactly("AGENT", "SNMP");
        } finally { query("DROP DATABASE " + db); }
    }

    private String query(String sql) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint))
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return response.body();
    }
}
