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
  void syncOnceKeepsCanonicalOwnershipInLogparserAndSyncsObservedAgents() {
    clickHouseClient.observedSources.add(new ClickHouseClient.ObservedAgentSource(
        "agent-01",
        Instant.parse("2026-06-09T10:00:00Z"),
        """
            {"asset_uid":"agent-01","hostname":"app-01","management_ip":"10.1.0.10","asset_type":"LINUX_SERVER"}
            """));
    TelemetrySyncWorker worker = new TelemetrySyncWorker(clickHouseClient, normalizer, assetService, jdbcTemplate);
    Map<String, Object> result = worker.syncOnce();

    assertThat(result)
        .containsEntry("owner", "logparser")
        .containsEntry("rawRows", 0)
        .containsEntry("canonicalRows", 0)
        .containsEntry("observedAgents", 1);
    assertThat(clickHouseClient.schemaEnsured).isTrue();
    assertThat(clickHouseClient.rawFetchCalls).isZero();
    assertThat(clickHouseClient.canonicalInsertCalls).isZero();

    Asset agentAsset = assetService.listAssets().stream()
        .filter(asset -> asset.assetUid().equals("agent-01"))
        .findFirst()
        .orElseThrow();
    assertThat(agentAsset.name()).isEqualTo("app-01");
    List<AssetSourceBinding> sources = assetService.sources(agentAsset.id());
    assertThat(sources).extracting(AssetSourceBinding::sourceType).contains(SourceType.AGENT);
    assertThat(sources).extracting(AssetSourceBinding::sourceKey).contains("transformed-agent");
    Integer cursorCount = jdbcTemplate.queryForObject("select count(*) from sync_cursors", Integer.class);
    assertThat(cursorCount).isZero();
  }

  @Test
  void syncObservedAgentsAcceptsLegacyNestedIdentityPayload() {
    clickHouseClient.observedSources.add(new ClickHouseClient.ObservedAgentSource(
        "nas",
        Instant.parse("2026-06-11T12:00:00Z"),
        """
            {"additionalAttributes":{"payload":{"asset_uid":"nas","hostname":"nas-01","management_ip":"10.1.0.20","asset_type":"LINUX_SERVER"}}}
            """));

    TelemetrySyncWorker worker = new TelemetrySyncWorker(clickHouseClient, normalizer, assetService, jdbcTemplate);
    Map<String, Object> result = worker.syncObservedAgents();

    assertThat(result).containsEntry("agents", 1);

    Asset agentAsset = assetService.listAssets().stream()
        .filter(asset -> asset.assetUid().equals("nas"))
        .findFirst()
        .orElseThrow();
    assertThat(agentAsset.name()).isEqualTo("nas-01");
    assertThat(assetService.sources(agentAsset.id()))
        .extracting(AssetSourceBinding::sourceId)
        .contains("nas");
  }

  private static class FakeClickHouseClient extends ClickHouseClient {
    final List<ObservedAgentSource> observedSources = new ArrayList<>();
    boolean schemaEnsured;
    int rawFetchCalls;
    int canonicalInsertCalls;

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
      rawFetchCalls++;
      return List.of();
    }

    @Override
    public void insertCanonicalRecords(List<CanonicalTelemetryRecord> records) {
      canonicalInsertCalls++;
    }

    @Override
    public List<ObservedAgentSource> fetchObservedAgentSources() {
      return observedSources;
    }
  }
}
