package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="ROUTER_TEST_CLICKHOUSE_URL", matches=".+")
class RouterClickHouseIntegrationTest {
  private final String endpoint = System.getenv("ROUTER_TEST_CLICKHOUSE_URL");
  @Test void queriesActualClickHouseWithLogicalDedupRawSearchAndPaging() throws Exception {
    String database = "router_qa_" + UUID.randomUUID().toString().replace("-", "");
    query("CREATE DATABASE " + database);
    try {
      query(CanonicalTelemetrySchema.createEvents(database));
      var json = new ObjectMapper();
      var payload = Map.of("event_id", "ev-1", "log_family", "zeek", "raw", Map.of("uid", "C1"), "message", "router event");
      var row = new LinkedHashMap<String,Object>(Map.of("observed_at", ClickHouseClient.formatDateTime64(Instant.now()),
          "asset_uid", "asset-1", "source_type", "SECURITY_ROUTER", "source_id", "ssw-1", "event_type", "zeek.notice",
          "severity", "WARNING", "event_json", json.writeValueAsString(payload)));
      String router = json.writeValueAsString(row);
      row.put("source_type", "AGENT"); row.put("source_id", "agent-1"); row.put("event_json", "{\"message\":\"agent event\"}");
      query("INSERT INTO " + database + ".manager_events FORMAT JSONEachRow\n" + router + "\n" + router + "\n" + json.writeValueAsString(row));
      var client = new ClickHouseClient(new ManagerProperties("test", "http://localhost",
          new ManagerProperties.ClickHouse(endpoint, database, "default", "", "unused_raw")), RestClient.builder());
      var first = client.queryAssetLogEvents("1h", "ALL", "asset-1", "ALL", "", 1, 0);
      assertThat((List<?>)first.get("items")).hasSize(1);
      assertThat(first).containsEntry("hasMore", true);
      var second = client.queryAssetLogEvents("1h", "ALL", "asset-1", "ALL", "", 1, 1);
      assertThat((List<?>)second.get("items")).hasSize(1);
      assertThat(second).containsEntry("hasMore", false);
      var matching = client.queryAssetLogEvents("1h", "WARNING", "asset-1", "zeek", "C1", 100, 0);
      assertThat((List<?>)matching.get("items")).hasSize(1);
      assertThat((List<?>)client.queryAssetLogEvents("1h", "ALL", "other-asset", "ALL", "", 100, 0).get("items")).isEmpty();
      assertThat((List<?>)client.queryAssetLogEvents("1h", "ALL", "asset-1", "ALL", "a\\'b", 100, 0).get("items")).isEmpty();
    } finally { query("DROP DATABASE " + database); }
  }
  private String query(String sql) throws Exception {
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint))
        .POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
    return response.body();
  }
}
