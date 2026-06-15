package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.alert.DefaultAlertRuleSeeder;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetSourceBinding;
import org.castrelyx.manager.asset.SourceType;
import org.castrelyx.manager.config.ManagerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@ActiveProfiles("test")
@SpringBootTest
class TelemetrySyncWorkerTest {
  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  AssetService assetService;

  @Autowired
  TelemetryNormalizer normalizer;

  @Autowired
  DefaultAlertRuleSeeder alertRuleSeeder;

  FakeClickHouseClient clickHouseClient;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from alert_instances");
    jdbcTemplate.update("delete from alert_rules");
    jdbcTemplate.update("delete from sync_cursors");
    jdbcTemplate.update("delete from asset_merge_candidates");
    jdbcTemplate.update("delete from asset_source_bindings");
    jdbcTemplate.update("delete from assets");
    alertRuleSeeder.seed();
    clickHouseClient = new FakeClickHouseClient();
  }

  @Test
  void syncsRawRowsIntoAssetsCanonicalRowsCursorAndAlerts() {
    clickHouseClient.rawRows.add(new ClickHouseClient.RawTelemetryRow(
        Instant.parse("2026-06-09T10:00:00Z"),
        "agent-01",
        "asset",
        "identity",
        "identity",
        """
            {"asset_uid":"agent-01","hostname":"app-01","management_ip":"10.1.0.10","asset_type":"LINUX_SERVER"}
            """));
    clickHouseClient.rawRows.add(new ClickHouseClient.RawTelemetryRow(
        Instant.parse("2026-06-09T10:01:00Z"),
        "agent-01",
        "metric",
        "cpu",
        "cpu.total",
        """
            {"asset_uid":"agent-01","metric_name":"cpu.usage","metric_value":95.5,"unit":"percent"}
            """));
    clickHouseClient.rawRows.add(new ClickHouseClient.RawTelemetryRow(
        Instant.parse("2026-06-09T10:02:00Z"),
        "snmp-edge",
        "snmp",
        "poll",
        "edge-router",
        """
            {
              "target_host":"192.168.10.1",
              "target_name":"edge-router",
              "poll_status":"error",
              "metrics":[{"interface":"eth0","name":"ifInOctets","value":120000,"unit":"bytes"}]
            }
            """));

    TelemetrySyncWorker worker = new TelemetrySyncWorker(clickHouseClient, normalizer, assetService, jdbcTemplate);
    Map<String, Object> result = worker.syncOnce();

    assertThat(result).containsEntry("rawRows", 3).containsEntry("canonicalRows", 5);
    assertThat(clickHouseClient.schemaEnsured).isTrue();
    assertThat(clickHouseClient.insertedRecords).extracting(CanonicalTelemetryRecord::kind)
        .contains(CanonicalTelemetryRecord.Kind.METRIC, CanonicalTelemetryRecord.Kind.EVENT, CanonicalTelemetryRecord.Kind.STATE);

    Asset agentAsset = assetService.listAssets().stream()
        .filter(asset -> asset.assetUid().equals("agent-01"))
        .findFirst()
        .orElseThrow();
    assertThat(agentAsset.name()).isEqualTo("app-01");
    List<AssetSourceBinding> sources = assetService.sources(agentAsset.id());
    assertThat(sources).extracting(AssetSourceBinding::sourceType).contains(SourceType.AGENT);

    Integer alertCount = jdbcTemplate.queryForObject("select count(*) from alert_instances where status = 'ACTIVE'", Integer.class);
    assertThat(alertCount).isEqualTo(2);
    String cursor = jdbcTemplate.queryForObject(
        "select cursor_value from sync_cursors where name = 'clickhouse.raw.castrelyx_agent_events'",
        String.class);
    assertThat(cursor).contains("2026-06-09T10:02:00Z", "snmp-edge", "edge-router");
  }

  @Test
  void createsAgentAssetFromFirstNonIdentityTelemetry() {
    clickHouseClient.rawRows.add(new ClickHouseClient.RawTelemetryRow(
        Instant.parse("2026-06-11T12:00:00Z"),
        "nas",
        "metric",
        "disk",
        "root",
        """
            {"metric_name":"disk.usage","metric_value":72.4,"unit":"percent"}
            """));

    TelemetrySyncWorker worker = new TelemetrySyncWorker(clickHouseClient, normalizer, assetService, jdbcTemplate);
    worker.syncOnce();

    Asset agentAsset = assetService.listAssets().stream()
        .filter(asset -> asset.assetUid().equals("nas"))
        .findFirst()
        .orElseThrow();
    assertThat(agentAsset.name()).isEqualTo("nas");
    assertThat(assetService.sources(agentAsset.id()))
        .extracting(AssetSourceBinding::sourceId)
        .contains("nas");
  }

  @Test
  void drainsMultipleRawTelemetryBatchesInOneSync() {
    for (int index = 0; index < 1001; index++) {
      clickHouseClient.rawRows.add(new ClickHouseClient.RawTelemetryRow(
          Instant.parse("2026-06-11T12:00:00Z").plusSeconds(index),
          "agent-01",
          "metric",
          "runtime",
          "runtime-" + index,
          """
              {"metric_name":"agent.runtime.uptime","metric_value":1,"unit":"seconds"}
              """));
    }

    TelemetrySyncWorker worker = new TelemetrySyncWorker(clickHouseClient, normalizer, assetService, jdbcTemplate);
    Map<String, Object> result = worker.syncOnce();

    assertThat(result).containsEntry("rawRows", 1001).containsEntry("canonicalRows", 1001);
    assertThat(clickHouseClient.insertedRecords).hasSize(1001);
  }

  private static class FakeClickHouseClient extends ClickHouseClient {
    final List<RawTelemetryRow> rawRows = new ArrayList<>();
    final List<CanonicalTelemetryRecord> insertedRecords = new ArrayList<>();
    boolean schemaEnsured;
    int nextRow;

    FakeClickHouseClient() {
      super(new ManagerProperties(
          "12345678901234567890123456789012",
          "http://localhost:8780",
          new ManagerProperties.ClickHouse("http://localhost:18123", "castrelyx", "default", "", "castrelyx_agent_events")),
          RestClient.builder());
    }

    @Override
    public void ensureCanonicalTables() {
      schemaEnsured = true;
    }

    @Override
    public List<RawTelemetryRow> fetchRawTelemetryRows(String cursorValue, int limit) {
      if (nextRow >= rawRows.size()) {
        return List.of();
      }
      int end = Math.min(nextRow + limit, rawRows.size());
      List<RawTelemetryRow> batch = new ArrayList<>(rawRows.subList(nextRow, end));
      nextRow = end;
      return batch;
    }

    @Override
    public void insertCanonicalRecords(List<CanonicalTelemetryRecord> records) {
      insertedRecords.addAll(records);
    }
  }
}
