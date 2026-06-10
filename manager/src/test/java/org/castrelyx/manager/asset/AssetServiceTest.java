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
        "WAN router"));

    AssetSourceBinding agent = assetService.bindSource(created.id(), SourceType.AGENT, "agent-001", "edge-router", 90);
    AssetSourceBinding snmp = assetService.bindSource(created.id(), SourceType.SNMP, "192.168.10.1", "sysName=edge-router", 95);
    List<AssetSourceBinding> bindings = assetService.sources(created.id());

    assertThat(created.assetUid()).startsWith("manual-");
    assertThat(agent.sourceType()).isEqualTo(SourceType.AGENT);
    assertThat(snmp.sourceType()).isEqualTo(SourceType.SNMP);
    assertThat(bindings).extracting(AssetSourceBinding::sourceType)
        .containsExactlyInAnyOrder(SourceType.AGENT, SourceType.SNMP);
  }

  @Test
  void createsAndResolvesMergeCandidatesWithoutAutomaticMerge() {
    Asset primary = assetService.createManualAsset(new AssetCreateRequest("srv-01", AssetType.LINUX_SERVER, "10.1.0.10", null));
    Asset candidate = assetService.createManualAsset(new AssetCreateRequest("srv-01-copy", AssetType.LINUX_SERVER, "10.1.0.11", null));

    MergeCandidate mergeCandidate = assetService.createMergeCandidate(primary.id(), candidate.id(), "hostname and MAC matched", 88);
    assetService.acceptMergeCandidate(mergeCandidate.id());

    List<MergeCandidate> candidates = assetService.mergeCandidates();
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().status()).isEqualTo(MergeCandidateStatus.ACCEPTED);
  }
}
