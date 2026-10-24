package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class HuntRelationsTest {
  private static final Instant BASE = Instant.parse("2026-10-22T00:00:00Z");
  private static final String PARENT = "11111111-1111-4111-8111-111111111111";
  private static final String CHILD = "22222222-2222-4222-8222-222222222222";

  private static Map<String, Object> event(int id, String type, int second, Object... values) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", String.format("%064x", id));
    row.put("at", BASE.plusSeconds(second).toString());
    row.put("event_type", type);
    row.put("observed_asset_uid", "observer");
    row.put("asset_uid", "mapped-asset");
    row.put("source_type", "AGENT");
    row.put("source_id", "sensor-a");
    for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> result, String key) {
    return (List<Map<String, Object>>) result.get(key);
  }

  private static List<Map<String, Object>> kind(
      Map<String, Object> result, String key, String kind) {
    return items(result, key).stream().filter(item -> kind.equals(item.get("kind"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributes(Map<String, Object> node) {
    return (Map<String, Object>) node.get("attributes");
  }

  @Test
  void missingConnectionFieldsAndConflictingProcessStartsCannotAppearComplete() {
    var cases =
        List.of(
            List.of(event(1, "process.network", 0, "process_guid", PARENT)),
            List.of(event(1, "network.flow", 0, "src_ip", "10.0.0.1")),
            List.of(
                event(1, "process.start", 0, "process_guid", PARENT),
                event(2, "process.start", 1, "process_guid", PARENT)),
            List.of(
                event(
                    1,
                    "process.start",
                    0,
                    "process_guid",
                    PARENT,
                    "process_started_at",
                    BASE.plusSeconds(10).toString())));
    for (var rows : cases) {
      var graph = HuntRelations.build(rows, rows.size());
      assertThat(((Map<?, ?>) graph.get("coverage")).get("complete")).isEqualTo(false);
      assertThat((Map<?, ?>) graph.get("issues")).isNotEmpty();
    }
  }

  @Test
  void representativeEvidenceIsCappedWithoutReducingTheActualEvidenceCount() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 50; i++) rows.add(event(i, "file.read", i, "process_guid", PARENT));
    var graph = HuntRelations.build(rows, 50);
    var node = items(graph, "nodes").getFirst();
    assertThat((List<?>) node.get("eventIds")).hasSize(20);
    assertThat(node.get("evidenceCount")).isEqualTo(50);
  }

  @Test
  void denseObservedConnectionsRespectTheEdgeLimitWithoutDanglingNodes() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int process = 0; process < 50; process++)
      for (int flow = 0; flow < 50; flow++)
        rows.add(
            event(
                rows.size() + 1,
                "process.network",
                1,
                "process_guid",
                new UUID(1, process + 1).toString(),
                "boot_id",
                "boot",
                "connection_uid",
                "flow-" + flow,
                "connection_started_at",
                BASE.toString(),
                "src_ip",
                "10.0.0.1",
                "src_port",
                50000 + flow,
                "dst_ip",
                "198.51.100.1",
                "dst_port",
                443,
                "protocol",
                "tcp"));
    var graph = HuntRelations.build(rows, 2500);
    assertThat(items(graph, "nodes")).hasSize(151);
    assertThat(items(graph, "edges")).hasSize(2000);
    var ids = new HashSet<>();
    items(graph, "nodes").forEach(node -> ids.add(node.get("id")));
    assertThat(items(graph, "edges"))
        .allSatisfy(
            edge -> {
              assertThat(ids).contains(edge.get("from"), edge.get("to"));
            });
    var coverage = (Map<?, ?>) graph.get("coverage");
    assertThat(coverage.get("omittedEdges")).isEqualTo(600);
    assertThat(coverage.get("complete")).isEqualTo(false);
  }

  @Test
  void explicitGuidParentAndProcessNetworkKeepIndependentEvidenceAndIgnoreReplayOrder() {
    var rows =
        new ArrayList<>(
            List.of(
                event(1, "process.start", 0, "process_guid", PARENT, "process_name", "parent.exe"),
                event(
                    2,
                    "process.start",
                    1,
                    "process_guid",
                    "{" + CHILD.toUpperCase(Locale.ROOT) + "}",
                    "parent_guid",
                    PARENT,
                    "process_name",
                    "child.exe"),
                event(
                    3,
                    "process.network",
                    2,
                    "process_guid",
                    CHILD,
                    "src_ip",
                    "10.0.0.1",
                    "src_port",
                    55123,
                    "dst_ip",
                    "198.51.100.1",
                    "dst_port",
                    443,
                    "protocol",
                    "tcp"),
                event(4, "process.exit", 3, "process_guid", CHILD)));
    var result = HuntRelations.build(rows, 4);
    assertThat(items(result, "nodes")).hasSize(5);
    assertThat(kind(result, "edges", "PARENT_OF"))
        .singleElement()
        .extracting(item -> item.get("eventIds"))
        .isEqualTo(List.of(String.format("%064x", 2)));
    assertThat(kind(result, "edges", "PROCESS_NETWORK")).hasSize(1);
    assertThat(items(result, "edges")).hasSize(4);
    rows.add(rows.get(2));
    Collections.reverse(rows);
    assertThat(HuntRelations.build(rows, 4)).isEqualTo(result);
  }

  @Test
  void processStartedAfterChildOrExitedBeforeChildCannotBecomeItsParent() {
    for (boolean exited : List.of(false, true)) {
      var rows = new ArrayList<Map<String, Object>>();
      rows.add(event(1, "process.start", exited ? 0 : 3, "process_guid", PARENT));
      rows.add(event(2, "process.start", 2, "process_guid", CHILD, "parent_guid", PARENT));
      if (exited) rows.add(event(3, "process.exit", 1, "process_guid", PARENT));
      var result = HuntRelations.build(rows, rows.size());
      assertThat(kind(result, "edges", "PARENT_OF")).isEmpty();
      assertThat(result.get("issues")).asString().contains("PROCESS_LIFETIME_CONFLICT");
    }
  }

  @Test
  void contradictoryLateParentStartsAndMultipleParentsDoNotLeaveEarlierAcceptedEdges() {
    var rows =
        List.of(
            event(
                1,
                "process.start",
                3,
                "process_guid",
                CHILD,
                "parent_guid",
                PARENT,
                "parent_started_at",
                BASE.toString()),
            event(
                2,
                "process.network",
                4,
                "process_guid",
                CHILD,
                "parent_guid",
                PARENT,
                "parent_started_at",
                BASE.plusSeconds(1).toString()));
    assertThat(kind(HuntRelations.build(rows, 2), "edges", "PARENT_OF")).isEmpty();
    var other = new LinkedHashMap<>(rows.get(1));
    other.put("parent_guid", "33333333-3333-4333-8333-333333333333");
    assertThat(kind(HuntRelations.build(List.of(rows.get(0), other), 2), "edges", "PARENT_OF"))
        .isEmpty();
  }

  @Test
  void sourceParentReferenceHasExplicitUnobservedStatusAndCyclesHaveNoCausalEdges() {
    var row = event(1, "process.start", 1, "process_guid", CHILD, "parent_guid", PARENT);
    var result = HuntRelations.build(List.of(row), 1);
    assertThat(kind(result, "edges", "PARENT_OF")).hasSize(1);
    assertThat(
            kind(result, "nodes", "PROCESS").stream()
                .filter(n -> Boolean.TRUE.equals(n.get("referenceOnly"))))
        .hasSize(1);
    assertThat(result.get("coverage")).asString().contains("complete=false");
    var cycle =
        HuntRelations.build(
            List.of(
                event(1, "process.start", 0, "process_guid", CHILD, "parent_guid", PARENT),
                event(2, "process.start", 0, "process_guid", PARENT, "parent_guid", CHILD)),
            2);
    assertThat(kind(cycle, "edges", "PARENT_OF")).isEmpty();
    assertThat(cycle.get("issues")).asString().contains("PARENT_CYCLE");
  }

  @Test
  void pidReuseBootAndExplicitStartSeparateGenerationsAndPostExitNetworkIsNotAttributed() {
    var rows =
        List.of(
            event(
                1,
                "process.start",
                0,
                "boot_id",
                "boot-a",
                "process_pid",
                "77",
                "process_started_at",
                BASE.toString()),
            event(
                2,
                "process.exit",
                1,
                "boot_id",
                "boot-a",
                "process_pid",
                "0x4d",
                "process_started_at",
                BASE.toString()),
            event(
                3,
                "process.start",
                2,
                "boot_id",
                "boot-a",
                "process_pid",
                "77",
                "process_started_at",
                BASE.plusSeconds(2).toString()),
            event(
                4,
                "process.start",
                2,
                "boot_id",
                "boot-b",
                "process_pid",
                "77",
                "process_started_at",
                BASE.plusSeconds(2).toString()),
            event(
                5,
                "process.network",
                3,
                "boot_id",
                "boot-a",
                "process_pid",
                "77",
                "process_started_at",
                BASE.toString(),
                "connection_uid",
                "old"),
            event(
                6,
                "process.network",
                3,
                "boot_id",
                "boot-a",
                "process_pid",
                "77",
                "process_started_at",
                BASE.plusSeconds(2).toString(),
                "connection_uid",
                "new"),
            event(7, "process.start", 3, "process_pid", "77"));
    var result = HuntRelations.build(rows, 7);
    assertThat(kind(result, "nodes", "PROCESS")).hasSize(3);
    assertThat(kind(result, "edges", "PROCESS_NETWORK")).hasSize(1);
    String process =
        String.valueOf(kind(result, "edges", "PROCESS_NETWORK").getFirst().get("from"));
    var node =
        items(result, "nodes").stream()
            .filter(n -> process.equals(n.get("id")))
            .findFirst()
            .orElseThrow();
    assertThat(node.get("startedAt")).isEqualTo(BASE.plusSeconds(2).toString());
    assertThat(attributes(node)).containsEntry("boot_id", "boot-a");
    assertThat(result.get("issues"))
        .asString()
        .contains("PROCESS_IDENTITY_MISSING", "PROCESS_LIFETIME_CONFLICT");
  }

  @Test
  void sharedMappedAssetDoesNotJoinDifferentObserverOrSensorProcessIdentities() {
    var rows =
        List.of(
            event(1, "process.start", 0, "process_guid", PARENT),
            event(
                2,
                "process.network",
                1,
                "process_guid",
                PARENT,
                "source_id",
                "sensor-b",
                "connection_uid",
                "uid"));
    var result = HuntRelations.build(rows, 2);
    assertThat(kind(result, "nodes", "PROCESS")).hasSize(2);
    String from = String.valueOf(kind(result, "edges", "PROCESS_NETWORK").getFirst().get("from"));
    var owner =
        items(result, "nodes").stream()
            .filter(n -> from.equals(n.get("id")))
            .findFirst()
            .orElseThrow();
    assertThat(attributes(owner)).containsEntry("source_id", "sensor-b");
  }

  @Test
  void natDifferentTuplesAndSensorsRemainSeparateDespiteSameFlowAndCommunityIds() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 4; i++)
      rows.add(
          event(
              i + 1,
              "network.flow",
              i + 1,
              "source_id",
              i == 2 ? "sensor-b" : "sensor-a",
              "connection_uid",
              "18446744073709551615",
              "community_id",
              "1:shared",
              "boot_id",
              "boot",
              "connection_started_at",
              BASE.toString(),
              "src_ip",
              i == 3 ? "203.0.113.9" : "10.0.0.1",
              "src_port",
              55123,
              "dst_ip",
              "198.51.100.1",
              "dst_port",
              443,
              "protocol",
              "tcp"));
    var result = HuntRelations.build(rows, 4);
    assertThat(kind(result, "nodes", "CONNECTION")).hasSize(3);
    assertThat(kind(result, "edges", "OBSERVED_FROM")).hasSize(3);
    assertThat(kind(result, "nodes", "CONNECTION").stream().map(HuntRelationsTest::attributes))
        .allSatisfy(a -> assertThat(a).containsEntry("connection_uid", "18446744073709551615"));
    assertThat(items(result, "edges")).noneMatch(e -> "COMMUNITY_ID".equals(e.get("kind")));
  }

  @Test
  void identifiersWithoutSessionLifetimeDoNotMergeRecordsAndCommunityIdAloneDoesNotConnect() {
    var rows =
        List.of(
            event(1, "network.flow", 0, "connection_uid", "reused"),
            event(2, "network.flow", 10, "connection_uid", "reused"),
            event(3, "network.flow", 20, "community_id", "1:shared"));
    var result = HuntRelations.build(rows, 3);
    assertThat(kind(result, "nodes", "CONNECTION")).hasSize(2);
    assertThat(kind(result, "nodes", "ENDPOINT")).isEmpty();
    assertThat(items(result, "edges")).isEmpty();
  }

  @Test
  void limitsAreExplicitAndEveryRenderedEdgeHasBothEndpoints() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 1200; i++)
      rows.add(event(i, "process.start", i, "process_guid", new UUID(1, i).toString()));
    var result = HuntRelations.build(rows, 1200);
    assertThat(items(result, "nodes")).hasSize(1000);
    assertThat(result.get("coverage")).asString().contains("omittedNodes=200", "complete=false");
    rows.clear();
    for (int i = 1; i <= HuntRelations.INPUT_LIMIT + 1; i++) rows.add(event(i, "message", 0));
    var limited = HuntRelations.build(rows, 30000);
    assertThat(limited.get("coverage"))
        .asString()
        .contains("truncated=true", "evaluatedEvents=10000");
  }
}
