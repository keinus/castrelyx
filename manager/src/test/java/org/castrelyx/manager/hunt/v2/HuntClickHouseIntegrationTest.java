package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.telemetry.CanonicalTelemetrySchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "HUNT_TEST_CLICKHOUSE_URL", matches = ".+")
class HuntClickHouseIntegrationTest {
  private record PredicateCase(String name, HuntQuery.Filter filter, Set<String> expected) {}

  private static HuntQuery.Filter term(String op, String field, String... values) {
    return new HuntQuery.Filter(op, field, List.of(values), null);
  }

  private static HuntQuery.Filter group(String op, HuntQuery.Filter... children) {
    return new HuntQuery.Filter(op, null, null, List.of(children));
  }

  static Stream<Arguments> predicates() {
    return Stream.of(
            new PredicateCase("eq", term("eq", "actor", "alice"), Set.of("r1")),
            new PredicateCase(
                "neq", term("neq", "actor", "alice"), Set.of("r2", "r3", "r4", "r5", "r6")),
            new PredicateCase("contains", term("contains", "domain", "ALPHA"), Set.of("r1", "r5")),
            new PredicateCase("prefix", term("prefix", "domain", "alpha."), Set.of("r1")),
            new PredicateCase("in", term("in", "actor", "alice", "bob"), Set.of("r1", "r2")),
            new PredicateCase(
                "not_in", term("not_in", "actor", "alice", "bob"), Set.of("r3", "r4", "r5", "r6")),
            new PredicateCase("exists", term("exists", "actor"), Set.of("r1", "r2", "r3", "r5")),
            new PredicateCase(
                "not_exists", group("not", term("exists", "actor")), Set.of("r4", "r6")),
            new PredicateCase(
                "ipv4", term("cidr", "src_ip", "198.51.100.0/24"), Set.of("r1", "r2")),
            new PredicateCase("ipv6", term("cidr", "src_ip", "2001:db8::/32"), Set.of("r4")),
            new PredicateCase(
                "ipv4_any", term("cidr", "src_ip", "0.0.0.0/0"), Set.of("r1", "r2", "r3")),
            new PredicateCase("ipv6_any", term("cidr", "src_ip", "::/0"), Set.of("r4")),
            new PredicateCase("number_gt", term("gt", "dst_port", "443"), Set.of("r3", "r5")),
            new PredicateCase(
                "number_gte", term("gte", "dst_port", "443"), Set.of("r2", "r3", "r5")),
            new PredicateCase("number_lt", term("lt", "dst_port", "443"), Set.of("r1")),
            new PredicateCase("number_lte", term("lte", "dst_port", "443"), Set.of("r1", "r2")),
            new PredicateCase("number_in", term("in", "dst_port", "22", "443"), Set.of("r1", "r2")),
            new PredicateCase(
                "number_not_in", term("not_in", "dst_port", "22"), Set.of("r2", "r3", "r5")),
            new PredicateCase(
                "number_missing", group("not", term("exists", "dst_port")), Set.of("r4", "r6")),
            new PredicateCase(
                "nested",
                group(
                    "and",
                    group(
                        "or", term("in", "actor", "alice", "bob"), term("gte", "dst_port", "8080")),
                    group("not", term("eq", "outcome", "failure"))),
                Set.of("r2", "r3", "r5")),
            new PredicateCase("quoted_data", term("eq", "actor", "alice' OR 1=1 --"), Set.of()))
        .flatMap(
            test ->
                Stream.of(false, true)
                    .map(
                        physical ->
                            Arguments.of(test.name(), test.filter(), test.expected(), physical)));
  }

  @ParameterizedTest(name = "{0} physical={3}")
  @MethodSource("predicates")
  void typedLogicalPredicatesMatchIndependentExpectedEventSets(
      String name, HuntQuery.Filter filter, Set<String> expected, boolean physical)
      throws Exception {
    var payloads =
        List.of(
            Map.of(
                "actor",
                "alice",
                "src_ip",
                "198.51.100.1",
                "dest_port",
                22,
                "query",
                "alpha.example",
                "outcome",
                "failure"),
            Map.of(
                "actor",
                "bob",
                "src_ip",
                "198.51.100.2",
                "dest_port",
                443,
                "query",
                "beta.example",
                "outcome",
                "success"),
            Map.of(
                "actor", "charlie", "src_ip", "10.0.0.1", "dest_port", 8080, "outcome", "success"),
            Map.of("src_ip", "2001:db8::1"),
            Map.of("actor", "ALICE", "dest_port", 8443, "query", "Alpha.example"),
            Map.of("src_ip", "not-an-ip", "message", "invalid source address"));
    for (int i = 0; i < payloads.size(); i++) {
      var payload = new LinkedHashMap<String, Object>(payloads.get(i));
      payload.put("dedup_key", "r" + (i + 1));
      insert("fixture", now.minusSeconds(10), payload);
    }
    if (physical) execute(HuntReadModel.install(database, storage.readModel(true)));
    var query =
        new HuntQuery(now.minusSeconds(60), now, filter, "", "event_type", "count", 200, null);
    var rows = read(new HuntSql(database, Map.of(), physical).events(query, now));
    var actual = new HashSet<String>();
    for (var row : rows)
      actual.add(json.readTree(String.valueOf(row.get("event_json"))).path("dedup_key").asText());
    assertThat(actual).isEqualTo(expected);
    assertThat(
            ((Number)
                    read(new HuntSql(database, Map.of(), physical).summary(query, now))
                        .getFirst()
                        .get("total"))
                .intValue())
        .isEqualTo(expected.size());
  }

  private final String endpoint = System.getenv("HUNT_TEST_CLICKHOUSE_URL");
  private final String database = "hunt_test_" + UUID.randomUUID().toString().replace("-", "");
  private final ObjectMapper json = new ObjectMapper();
  private HuntStorage storage;
  private Instant now;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void compoundGroupsAggregateAllEventsAndKeepMissingNumericDimensions(boolean physical)
      throws Exception {
    for (var payload :
        List.of(
            Map.<String, Object>of(
                "dedup_key",
                "one",
                "actor",
                "alice",
                "dest_port",
                443,
                "bytes_sent",
                100,
                "bytes_received",
                10),
            Map.<String, Object>of(
                "dedup_key",
                "two",
                "actor",
                "alice",
                "dest_port",
                443,
                "bytes_sent",
                200,
                "bytes_received",
                20),
            Map.<String, Object>of(
                "dedup_key",
                "three",
                "actor",
                "alice",
                "dest_port",
                22,
                "bytes_sent",
                40,
                "bytes_received",
                4),
            Map.<String, Object>of(
                "dedup_key",
                "four",
                "actor",
                "bob",
                "dest_port",
                443,
                "bytes_sent",
                400,
                "bytes_received",
                40),
            Map.<String, Object>of("dedup_key", "missing", "actor", "bob", "bytes_sent", 5)))
      insert("network.flow", now.minusSeconds(10), payload);
    insert(
        "network.flow",
        now.minusSeconds(10),
        Map.of(
            "dedup_key",
            "two",
            "actor",
            "alice",
            "dest_port",
            443,
            "bytes_sent",
            200,
            "bytes_received",
            20));
    if (physical) execute(HuntReadModel.install(database, storage.readModel(true)));
    var query =
        new HuntQuery(
            now.minusSeconds(60),
            now,
            null,
            "",
            "actor",
            "bytes_sent",
            1,
            null,
            List.of("actor", "dst_port"),
            false);
    var groups = read(new HuntSql(database, Map.of(), physical).groups(query, now));
    assertThat(groups).hasSize(4);
    assertThat(groups.get(0)).containsEntry("value", "bob");
    assertThat(Double.parseDouble(String.valueOf(groups.get(0).get("dimension_1")))).isEqualTo(443);
    assertThat(((Number) groups.get(0).get("bytesSent")).intValue()).isEqualTo(400);
    assertThat(((Number) groups.get(1).get("count")).intValue()).isEqualTo(2);
    assertThat(((Number) groups.get(1).get("bytesReceived")).intValue()).isEqualTo(30);
    assertThat(((Number) groups.get(1).get("hostCount")).intValue()).isEqualTo(1);
    assertThat(groups.get(3).get("dimension_1")).isNull();
    assertThat(
            ((Number)
                    read(new HuntSql(database, Map.of(), physical).summary(query, now))
                        .getFirst()
                        .get("total"))
                .intValue())
        .isEqualTo(5);
    assertThat(read(new HuntSql(database, Map.of(), physical).events(query, now))).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void graphUsesRealSourceFieldsAndKeepsSensorNatAndStoredAtBoundaries(boolean physical)
      throws Exception {
    String parent = "11111111-1111-4111-8111-111111111111",
        child = "22222222-2222-4222-8222-222222222222";
    var birth =
        Map.<String, Object>of(
            "dedup_key",
            "child",
            "fields",
            Map.of("ProcessGuid", child, "ParentProcessGuid", parent, "Image", "child.exe"));
    insert(
        "process.start",
        now.minusSeconds(40),
        Map.of(
            "dedup_key", "parent", "fields", Map.of("ProcessGuid", parent, "Image", "parent.exe")));
    insert("process.start", now.minusSeconds(30), birth);
    insert("process.start", now.minusSeconds(30), birth);
    var network = new LinkedHashMap<String, Object>();
    network.put("dedup_key", "net");
    network.put("ProcessGuid", child);
    network.put("src_ip", "2001:db8::1");
    network.put("src_port", 55123);
    network.put("dest_ip", "198.51.100.1");
    network.put("dest_port", 443);
    network.put("proto", "tcp");
    network.put("flow_id", new java.math.BigInteger("18446744073709551615"));
    insert("process.network", now.minusSeconds(20), network);
    insertFrom(
        "host",
        "other-sensor",
        "process.start",
        now.minusSeconds(30),
        Map.of("dedup_key", "other-child", "ProcessGuid", child));
    var translated = new LinkedHashMap<>(network);
    translated.remove("ProcessGuid");
    translated.put("src_ip", "203.0.113.9");
    translated.put("dedup_key", "translated");
    insertFrom("host", "other-sensor", "network.flow", now.minusSeconds(20), translated);
    translated.put("dedup_key", "changed-tuple");
    insert("network.flow", now.minusSeconds(20), translated);
    var late = new LinkedHashMap<>(network);
    late.put("dedup_key", "late");
    late.put("stored_at", now.plusSeconds(60).toString());
    insert("process.network", now.minusSeconds(10), late);
    if (physical) execute(HuntReadModel.install(database, storage.readModel(true)));
    var query =
        new HuntQuery(now.minusSeconds(60), now, null, "", "actor", "count", 1, null, null, true);
    var rows =
        read(new HuntSql(database, Map.of("host", "mapped-asset"), physical).relations(query, now));
    assertThat(rows).hasSize(6);
    var graph = HuntRelations.build(rows, 6);
    @SuppressWarnings("unchecked")
    var nodes = (List<Map<String, Object>>) graph.get("nodes");
    @SuppressWarnings("unchecked")
    var edges = (List<Map<String, Object>>) graph.get("edges");
    assertThat(nodes).hasSize(11);
    assertThat(edges).hasSize(8);
    var indexed = new HashMap<String, Map<String, Object>>();
    nodes.forEach(node -> indexed.put(String.valueOf(node.get("id")), node));
    for (var edge : edges) {
      var from = (Map<?, ?>) indexed.get(edge.get("from")).get("attributes");
      var to = (Map<?, ?>) indexed.get(edge.get("to")).get("attributes");
      assertThat(from.get("source_id")).isEqualTo(to.get("source_id"));
      assertThat(from.get("observed_asset_uid")).isEqualTo("host");
      assertThat(from.get("asset_uid")).isEqualTo("mapped-asset");
    }
    assertThat(nodes.stream().filter(node -> "CONNECTION".equals(node.get("kind"))))
        .hasSize(3)
        .allSatisfy(
            node ->
                assertThat(((Map<?, ?>) node.get("attributes")).get("connection_uid"))
                    .isEqualTo("18446744073709551615"));
    String evidence =
        String.valueOf(
            rows.stream()
                .filter(row -> "process.network".equals(row.get("event_type")))
                .findFirst()
                .orElseThrow()
                .get("id"));
    assertThat(
            read(
                new HuntSql(database, Map.of("host", "mapped-asset"), physical)
                    .event(query, now, evidence)))
        .hasSize(1);
    var excluded =
        new HuntQuery(
            query.from(),
            query.to(),
            term("eq", "actor", "not-present"),
            "",
            "actor",
            "count",
            1,
            null);
    assertThat(read(new HuntSql(database, Map.of(), physical).event(excluded, now, evidence)))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void internalNetworkScenarioIgnoresMissingAndMalformedAddressesEvenForTheDefaultRoute(
      boolean physical) throws Exception {
    insert(
        "zeek.conn",
        now.minusSeconds(10),
        Map.of(
            "dedup_key", "good", "src_ip", "10.0.0.1", "dest_ip", "10.0.0.2", "dest_port", 3389));
    insert(
        "zeek.conn",
        now.minusSeconds(10),
        Map.of("dedup_key", "missing", "src_ip", "10.0.0.1", "dest_port", 3389));
    insert(
        "zeek.conn",
        now.minusSeconds(10),
        Map.of(
            "dedup_key", "bad", "src_ip", "10.0.0.1", "dest_ip", "not-an-ip", "dest_port", 3389));
    if (physical) execute(HuntReadModel.install(database, storage.readModel(true)));
    var catalogue = new HuntScenarios();
    var result =
        read(
            catalogue.compile(
                catalogue.require("B04"),
                query(),
                now,
                Map.of("internal_networks", List.of("0.0.0.0/0")),
                new HuntSql(database, Map.of(), physical)));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst()).containsEntry("dst_ip", "10.0.0.2");
  }

  @BeforeEach
  void setup() throws Exception {
    execute("CREATE DATABASE " + database);
    execute(CanonicalTelemetrySchema.createEvents(database));
    storage =
        new HuntStorage(
            new ManagerProperties(
                "test",
                "http://localhost",
                new ManagerProperties.ClickHouse(
                    endpoint, database, "huntqa", "huntqa-local-only", "unused")),
            json);
    now = Instant.now().minusSeconds(1);
  }

  @AfterEach
  void cleanup() throws Exception {
    execute("DROP DATABASE IF EXISTS " + database);
  }

  @Test
  void fullRangeFindsAnEventOlderThanTenThousandOtherEvents() throws Exception {
    execute(
        "INSERT INTO "
            + database
            + ".manager_events SELECT now64(3)-INTERVAL 20000"
            + " SECOND+toIntervalSecond(number),'sensor','AGENT','qa','zeek.conn','INFO',concat('{\"dedup_key\":\"',toString(number),'\",\"actor\":\"',if(number=0,'needle','other'),'\",\"src_ip\":\"10.0.0.1\",\"dest_ip\":\"10.0.0.2\"}')"
            + " FROM numbers(10050)");
    var query =
        new HuntQuery(
            now.minusSeconds(25000),
            now,
            new HuntQuery.Filter("eq", "actor", List.of("needle"), null),
            "",
            "actor",
            "count",
            200,
            null);
    var rows = read(new HuntSql(database).events(query, now));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("actor")).isEqualTo("needle");
    var summary = read(new HuntSql(database).summary(query, now));
    assertThat(((Number) summary.getFirst().get("total")).longValue()).isEqualTo(1);
    var all = new HuntQuery(query.from(), query.to(), null, "", "actor", "count", 2, null);
    assertThat(read(new HuntSql(database).events(all, now))).hasSize(3);
    assertThat(
            ((Number) read(new HuntSql(database).summary(all, now)).getFirst().get("total"))
                .longValue())
        .isEqualTo(10050);
    assertThat(read(new HuntSql(database).histogram(all, now))).isNotEmpty();
    assertThat(read(new HuntSql(database).groups(all, now))).hasSize(2);
  }

  @Test
  void deduplicatesReplaysButKeepsDifferentWindowsRecordsWithSameEventCode() throws Exception {
    for (int i = 0; i < 3; i++)
      insert(
          "auth.login.failure",
          now.minusSeconds(30),
          Map.of(
              "event_id",
              4625,
              "dedup_key",
              "record-" + i,
              "actor",
              "alice",
              "src_ip",
              "10.1.1.1"));
    insert(
        "auth.login.failure",
        now.minusSeconds(30),
        Map.of("event_id", 4625, "dedup_key", "record-1", "actor", "alice", "src_ip", "10.1.1.1"));
    var query = query();
    assertThat(
            ((Number) read(new HuntSql(database).summary(query, now)).getFirst().get("total"))
                .longValue())
        .isEqualTo(3);
  }

  @Test
  void allScenarioQueriesAreAcceptedByTheSupportedClickHouseVersion() throws Exception {
    for (int i = 0; i < 6; i++)
      insert(
          "auth.login.failure",
          now.minusSeconds(100 - i * 5),
          Map.of(
              "dedup_key",
              "failure-" + i,
              "actor",
              "alice",
              "src_ip",
              "10.1.1.1",
              "outcome",
              "failure"));
    insert(
        "auth.login.success",
        now.minusSeconds(10),
        Map.of(
            "dedup_key", "success", "actor", "alice", "src_ip", "10.1.1.1", "outcome", "success"));
    var catalogue = new HuntScenarios();
    var lookups =
        Map.of(
            "trusted_scanners",
            List.of("10.0.0.1"),
            "internal_networks",
            List.of("10.0.0.0/8"),
            "approved_dns",
            List.of("10.0.0.53"),
            "approved_services",
            List.of("443"),
            "business_hours",
            List.of("9-18"),
            "trusted_processes",
            List.of("/usr/sbin/sshd"),
            "protected_paths",
            List.of("/data/"),
            "indicators",
            List.of("malicious.example"));
    var errors = new ArrayList<String>();
    for (var scenario : catalogue.all())
      try {
        var findings =
            read(catalogue.compile(scenario, query(), now, lookups, new HuntSql(database)));
        if (scenario.id().equals("A04")) assertThat(findings).isNotEmpty();
      } catch (Exception | AssertionError e) {
        errors.add(scenario.id() + ": " + e.getMessage());
      }
    assertThat(errors).isEmpty();
  }

  @Test
  void beaconUsesRollingWindowAndDoesNotAccumulateSparseConnectionsAcrossTheWholeSelection()
      throws Exception {
    for (int i = 0; i < 25; i++)
      insert(
          "zeek.conn",
          now.minusSeconds(900 - i * 30),
          Map.of(
              "dedup_key",
              "regular-" + i,
              "src_ip",
              "10.0.0.1",
              "dest_ip",
              "198.51.100.1",
              "dest_port",
              443));
    for (int i = 0; i < 20; i++)
      insert(
          "zeek.conn",
          now.minusSeconds(3500 - i * 180),
          Map.of(
              "dedup_key",
              "sparse-" + i,
              "src_ip",
              "10.0.0.2",
              "dest_ip",
              "198.51.100.1",
              "dest_port",
              443));
    var catalogue = new HuntScenarios();
    var findings =
        read(
            catalogue.compile(
                catalogue.require("D01"), query(), now, Map.of(), new HuntSql(database)));
    assertThat(findings).hasSize(1);
    assertThat(findings.getFirst().get("src_ip")).isEqualTo("10.0.0.1");
  }

  @Test
  void knownHostCanStillFanOutToNewDestinationsAndAccountSequenceUsesStableSid() throws Exception {
    insert(
        "zeek.conn",
        now.minusSeconds(2 * 86400),
        Map.of(
            "dedup_key",
            "old",
            "src_ip",
            "10.0.0.1",
            "dest_ip",
            "198.51.100.200",
            "bytes_sent",
            1000000));
    for (int i = 0; i < 21; i++)
      insert(
          "zeek.conn",
          now.minusSeconds(100 - i),
          Map.of(
              "dedup_key",
              "new-" + i,
              "src_ip",
              "10.0.0.1",
              "dest_ip",
              "198.51.100." + (i + 1),
              "bytes_sent",
              1000000));
    var catalogue = new HuntScenarios();
    assertThat(
            read(
                catalogue.compile(
                    catalogue.require("E04"), query(), now, Map.of(), new HuntSql(database))))
        .hasSize(1);
    insert(
        "account.create",
        now.minusSeconds(90),
        Map.of("dedup_key", "account", "account_id", "S-1-5-21-123", "actor", "new-user"));
    insert(
        "account.privilege.add",
        now.minusSeconds(30),
        Map.of("dedup_key", "privilege", "account_id", "S-1-5-21-123", "actor", "admin"));
    assertThat(
            read(
                catalogue.compile(
                    catalogue.require("G06"), query(), now, Map.of(), new HuntSql(database))))
        .hasSize(1);
    // Events with an absent correlation identity must never all join into a synthetic session.
    insert(
        "auth.login.success", now.minusSeconds(70), Map.of("dedup_key", "login", "actor", "alice"));
    insert("task.create", now.minusSeconds(20), Map.of("dedup_key", "task", "actor", "alice"));
    assertThat(
            read(
                catalogue.compile(
                    catalogue.require("B06"), query(), now, Map.of(), new HuntSql(database))))
        .isEmpty();
  }

  @Test
  void insertionCutoffAndTupleCursorExcludeLateArrivalsWithoutSkippingSameTimeRecords()
      throws Exception {
    for (int i = 0; i < 5; i++)
      insert(
          "auth.login.success",
          now.minusSeconds(30),
          Map.of("event_uid", "same-time-" + i, "stored_at", now.minusSeconds(10).toString()));
    insert(
        "auth.login.success",
        now.minusSeconds(35),
        Map.of("event_uid", "late", "stored_at", now.plusSeconds(10).toString()));
    var original = new HuntQuery(query().from(), query().to(), null, "", "actor", "count", 2, null);
    var seen = new HashSet<String>();
    HuntQuery current = original;
    for (int page = 0; page < 3; page++) {
      var rows = read(new HuntSql(database).events(current, now));
      var items = rows.stream().limit(2).toList();
      items.forEach(item -> assertThat(seen.add(String.valueOf(item.get("id")))).isTrue());
      var last = items.getLast();
      current =
          new HuntQuery(
              original.from(),
              original.to(),
              null,
              "",
              "actor",
              "count",
              2,
              new HuntQuery.Cursor(
                  Instant.parse(String.valueOf(last.get("at"))), String.valueOf(last.get("id"))));
    }
    assertThat(seen).hasSize(5);
  }

  @Test
  void registeredAssetAliasesPreserveTheNativeEvidenceIdentity() throws Exception {
    insert(
        "auth.login.success",
        now.minusSeconds(30),
        Map.of("event_uid", "one-native-record", "actor", "alice"));
    var original = read(new HuntSql(database).events(query(), now)).getFirst();
    var scope =
        new HuntQuery(
            query().from(),
            query().to(),
            new HuntQuery.Filter("eq", "asset_uid", List.of("registered"), null),
            "",
            "asset_uid",
            "count",
            200,
            null);
    var mapped = read(new HuntSql(database, Map.of("host", "registered")).events(scope, now));
    assertThat(mapped).hasSize(1);
    assertThat(mapped.getFirst())
        .containsEntry("asset_uid", "registered")
        .containsEntry("observed_asset_uid", "host")
        .containsEntry("id", original.get("id"));
  }

  private HuntQuery query() {
    return new HuntQuery(now.minusSeconds(3600), now, null, "", "event_type", "count", 200, null);
  }

  private List<Map<String, Object>> read(HuntSql.Statement statement) {
    try {
      return storage.read(statement, "hunt-" + UUID.randomUUID() + "-test");
    } catch (RuntimeException failure) {
      // Test fixtures contain no operational logs. Include the server diagnostic only in test
      // output.
      try {
        String parameters =
            statement.parameters().entrySet().stream()
                .map(
                    e ->
                        "param_"
                            + e.getKey()
                            + "="
                            + java.net.URLEncoder.encode(
                                e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        var response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/?" + parameters))
                        .header("X-ClickHouse-User", "huntqa")
                        .header("X-ClickHouse-Key", "huntqa-local-only")
                        .POST(HttpRequest.BodyPublishers.ofString(statement.sql()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        throw new AssertionError(response.body(), failure);
      } catch (AssertionError error) {
        throw error;
      } catch (Exception diagnostic) {
        throw failure;
      }
    }
  }

  private void insert(String type, Instant at, Map<String, Object> payload) throws Exception {
    insertFrom("host", "qa", type, at, payload);
  }

  private void insertFrom(
      String asset, String source, String type, Instant at, Map<String, Object> payload)
      throws Exception {
    var row = new LinkedHashMap<String, Object>();
    row.put("observed_at", at.toString().replace('T', ' ').replace("Z", ""));
    row.put("asset_uid", asset);
    row.put("source_type", "AGENT");
    row.put("source_id", source);
    row.put("event_type", type);
    row.put("severity", "INFO");
    row.put("event_json", json.writeValueAsString(payload));
    execute(
        "INSERT INTO "
            + database
            + ".manager_events FORMAT JSONEachRow\n"
            + json.writeValueAsString(row));
  }

  private void execute(String sql) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(endpoint))
                    .header("X-ClickHouse-User", "huntqa")
                    .header("X-ClickHouse-Key", "huntqa-local-only")
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalStateException(response.body());
  }
}
