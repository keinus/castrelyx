package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.output.model.ClickHouseOutputAdapter;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ROUTER_TEST_CLICKHOUSE_URL", matches=".+")
class RouterClickHouseIntegrationTest {
    @TempDir Path dir;
    private final String endpoint = System.getenv("ROUTER_TEST_CLICKHOUSE_URL");
    @Test void writesRealRawAndCanonicalRowsAndDeduplicatesReplay() throws Exception {
        String database = "router_qa_" + UUID.randomUUID().toString().replace("-", "");
        query("CREATE DATABASE " + database);
        var inbox = new RouterInbox(dir.resolve("inbox"));
        try {
            var options = Map.of("endpointUrl", endpoint, "database", database,
                "tableName", "castrelyx_router_events", "autoCreateSchema", true,
                "writeTelemetryTables", true, "incompleteChunkDlqDir", dir.resolve("chunks").toString());
            try (var output = new ClickHouseOutputAdapter(Map.of("configParams", new ObjectMapper().writeValueAsString(options)))) {
                String raw = new ObjectMapper().writeValueAsString(Map.of("id", "replay-1", "at", Instant.now().toString(),
                    "kind", "job.committed", "hash", "original-hash", "detail", "한글 감사 로그"));
                for (int attempt=0; attempt<2; attempt++) {
                    inbox.append(RouterPipelineTest.wire("ssw-1", "audit", raw), "ssw-1", "asset-1");
                    var event = inbox.poll();
                    RouterPipelineTest.parse(event);
                    output.send(event);
                    assertNull(inbox.poll());
                }
                assertEquals("1", query("SELECT count() FROM " + database + ".castrelyx_router_events").trim());
                assertEquals("1", query("SELECT count() FROM " + database + ".manager_events").trim());
                String row = query("SELECT * FROM " + database + ".manager_events FORMAT JSONEachRow");
                assertTrue(row.contains("SECURITY_ROUTER"));
                assertTrue(row.contains("original-hash"));
                assertTrue(row.contains("asset-1"));
            }
        } finally {
            inbox.close();
            query("DROP DATABASE " + database);
        }
    }
    private String query(String sql) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint))
            .POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
