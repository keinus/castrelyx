package org.castrelyx.manager.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AssetServiceTest {
  @Autowired
  AssetService assetService;

  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from alert_instances");
    jdbcTemplate.update("delete from alert_rules");
    jdbcTemplate.update("delete from asset_merge_candidates");
    jdbcTemplate.update("delete from asset_source_bindings");
    jdbcTemplate.update("delete from assets");
    jdbcTemplate.update("delete from users");
    authProvider.createLocalUser("admin", "password", "Administrator", Role.ADMIN);
  }

  @Test
  void createsManualAssetAndBindsAgentAndSnmpSources() {
    Asset created = assetService.createManualAsset(new AssetCreateRequest(
        "edge-router",
        AssetType.ROUTER,
        "192.168.10.1",
        "Seoul HQ / Rack A",
        "WAN router"));

    AssetSourceBinding agent = assetService.bindSource(created.id(), SourceType.AGENT, "agent-001", "edge-router", 90);
    AssetSourceBinding snmp = assetService.bindSource(created.id(), SourceType.SNMP, "192.168.10.1", "sysName=edge-router", 95);
    List<AssetSourceBinding> bindings = assetService.sources(created.id());

    assertThat(created.assetUid()).startsWith("manual-");
    assertThat(created.location()).isEqualTo("Seoul HQ / Rack A");
    assertThat(agent.sourceType()).isEqualTo(SourceType.AGENT);
    assertThat(snmp.sourceType()).isEqualTo(SourceType.SNMP);
    assertThat(bindings).extracting(AssetSourceBinding::sourceType)
        .containsExactlyInAnyOrder(SourceType.AGENT, SourceType.SNMP);
  }

  @Test
  void createsAndResolvesMergeCandidatesWithoutAutomaticMerge() {
    Asset primary = assetService.createManualAsset(new AssetCreateRequest("srv-01", AssetType.LINUX_SERVER, "10.1.0.10", null, null));
    Asset candidate = assetService.createManualAsset(new AssetCreateRequest("srv-01-copy", AssetType.LINUX_SERVER, "10.1.0.11", null, null));

    MergeCandidate mergeCandidate = assetService.createMergeCandidate(primary.id(), candidate.id(), "hostname and MAC matched", 88);
    assetService.acceptMergeCandidate(mergeCandidate.id());

    List<MergeCandidate> candidates = assetService.mergeCandidates();
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().status()).isEqualTo(MergeCandidateStatus.ACCEPTED);
  }

  @Test
  void updatesEditableFieldsOnly() {
    Asset created = assetService.createManualAsset(new AssetCreateRequest(
        "nas",
        AssetType.LINUX_SERVER,
        "192.168.50.21",
        "Seoul HQ",
        "NAS storage"));

    Asset updated = assetService.updateEditableFields(created.id(), new AssetUpdateRequest(
        "nas-main",
        "Seoul HQ / Rack B",
        "Primary NAS"));

    assertThat(updated.name()).isEqualTo("nas-main");
    assertThat(updated.location()).isEqualTo("Seoul HQ / Rack B");
    assertThat(updated.description()).isEqualTo("Primary NAS");
    assertThat(updated.assetType()).isEqualTo(AssetType.LINUX_SERVER);
    assertThat(updated.managementIp()).isEqualTo("192.168.50.21");
  }

  @Test
  void deletesAssetAndDetachesRelatedRows() {
    Asset primary = assetService.createManualAsset(new AssetCreateRequest("srv-01", AssetType.LINUX_SERVER, "10.1.0.10", "Seoul HQ", null));
    Asset candidate = assetService.createManualAsset(new AssetCreateRequest("srv-01-copy", AssetType.LINUX_SERVER, "10.1.0.11", "Seoul HQ", null));
    assetService.bindSource(primary.id(), SourceType.AGENT, "agent-001", "srv-01", 90);
    assetService.createMergeCandidate(primary.id(), candidate.id(), "hostname and MAC matched", 88);
    jdbcTemplate.update("""
        insert into alert_rules(id, name, rule_type, severity, expression_json, enabled, created_at)
        values (99, 'cpu', 'threshold', 'WARNING', '{}', true, current_timestamp)
        """);
    jdbcTemplate.update("""
        insert into alert_instances(rule_id, asset_id, severity, status, title, first_seen_at, last_seen_at)
        values (99, ?, 'WARNING', 'ACTIVE', 'CPU high', current_timestamp, current_timestamp)
        """, primary.id());

    assetService.deleteAsset(primary.id());

    Integer assetCount = jdbcTemplate.queryForObject("select count(*) from assets where id = ?", Integer.class, primary.id());
    Integer bindingCount = jdbcTemplate.queryForObject("select count(*) from asset_source_bindings where asset_id = ?", Integer.class, primary.id());
    Integer mergeCount = jdbcTemplate.queryForObject("select count(*) from asset_merge_candidates where primary_asset_id = ? or candidate_asset_id = ?", Integer.class, primary.id(), primary.id());
    Long detachedAlertCount = jdbcTemplate.queryForObject("select count(*) from alert_instances where asset_id is null", Long.class);
    assertThat(assetCount).isZero();
    assertThat(bindingCount).isZero();
    assertThat(mergeCount).isZero();
    assertThat(detachedAlertCount).isEqualTo(1L);
  }
}
