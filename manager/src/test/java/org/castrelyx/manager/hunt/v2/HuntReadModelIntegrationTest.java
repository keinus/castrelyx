package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.telemetry.CanonicalTelemetrySchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "HUNT_TEST_CLICKHOUSE_URL", matches = ".+")
class HuntReadModelIntegrationTest {
  private final String endpoint = System.getenv("HUNT_TEST_CLICKHOUSE_URL");
  private final String database = "hunt_model_" + UUID.randomUUID().toString().replace("-", "");
  private final ObjectMapper json = new ObjectMapper();
  private HuntStorage storage;
  private Instant now;

  @BeforeEach
  void start() throws Exception {
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
    now = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  @AfterEach
  void stop() throws Exception {
    execute("DROP DATABASE IF EXISTS " + database);
  }

  @Test
  void legacyPartialAndIncompatibleSchemasNeverEnablePhysicalReads() throws Exception {
    assertThat(storage.readModel(true).ready()).isFalse();
    var first = HuntReadModel.columns().getFirst();
    execute(
        "ALTER TABLE "
            + HuntReadModel.table(database)
            + " ADD COLUMN "
            + first.name()
            + " String MATERIALIZED '' COMMENT 'wrong'");
    var schema = storage.readModel(true);
    assertThat(schema.ready()).isFalse();
    assertThat(schema.incompatible()).containsExactly(first.name());
    assertThatThrownBy(() -> HuntReadModel.install(database, schema))
        .isInstanceOf(IllegalStateException.class);
    assertThat(storage.materialized()).isFalse();
    insert("auth.login.success", "legacy", Map.of("actor", "alice"));
    assertThat(read(new HuntSql(database).events(query(), now))).hasSize(1);
  }

  @Test
  void operationsExposeRealPartitionsAndDurableMutationCompletion() throws Exception {
    insert("auth.login.success", "operation", Map.of("actor", "alice"));
    var audit = mock(HuntWorkspace.class);
    var operations = new HuntReadModelOperations(storage, audit);
    var admin = new AuthUser(1, "admin", "admin", Role.ADMIN);
    var installed = operations.install(admin);
    assertThat(installed.schema().ready()).isTrue();
    assertThat(installed.queryMode()).isEqualTo("PHYSICAL");
    assertThat(installed.partitions()).hasSize(1);
    String partition = String.valueOf(installed.partitions().getFirst().get("partition"));
    operations.backfill(partition, admin);
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    HuntReadModelOperations.Status status;
    do {
      status = operations.status(admin);
      if (status.pendingMutations() == 0) break;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    assertThat(status.pendingMutations()).isZero();
    assertThat(status.mutations())
        .hasSize(1)
        .allSatisfy(row -> assertThat(((Number) row.get("is_done")).intValue()).isEqualTo(1));
    assertThat(read(new HuntSql(database, Map.of(), true).summary(query(), now)).getFirst())
        .containsEntry("total", 1);
    verify(audit).audit(eq(admin), eq("READ_MODEL_BACKFILL_SUBMITTED"), eq("storage"), anyMap());
  }

  @Test
  void oldNewAndRepeatedBackfillHaveIdenticalValuesIdsAliasesAndCutoffs() throws Exception {
    insert(
        "zeek.dns",
        "dns",
        Map.of(
            "data",
            Map.of(
                "src_ip",
                "10.0.0.1",
                "query",
                "sub.example.com",
                "answers",
                List.of("1.1.1.1", "::1"),
                "ttl",
                12)));
    insert(
        "process.start",
        "process",
        Map.of(
            "fields",
            Map.of(
                "Image",
                "C:\\Temp\\worker.exe",
                "ProcessGuid",
                "guid-1",
                "CommandLine",
                "worker --test")));
    insert(
        "auth.login.success",
        "login",
        Map.of("payload", Map.of("actor", "alice", "src_port", 1234)));
    insert(
        "auth.login.success",
        "login",
        Map.of("payload", Map.of("actor", "alice", "src_port", 1234))); // replay
    insert(
        "audit.cleared",
        "late",
        Map.of(
            "stored_at", now.plusSeconds(60).toString(), "provider", "Microsoft-Windows-Eventlog"));
    Map<String, String> aliases = Map.of("sensor-old", "asset-current");
    var expected = read(new HuntSql(database, aliases).events(query(), now));
    assertThat(expected).hasSize(3);
    assertThat(expected)
        .allSatisfy(
            row ->
                assertThat(row)
                    .containsEntry("asset_uid", "asset-current")
                    .containsEntry("observed_asset_uid", "sensor-old"));
    String install = HuntReadModel.install(database, storage.readModel(true));
    execute(install);
    assertThat(storage.readModel(true).ready()).isTrue();
    assertThat(storage.materialized()).isTrue();
    org.springframework.test.util.ReflectionTestUtils.setField(
        storage, "physicalReadEnabled", false);
    assertThat(storage.materialized()).isFalse();
    org.springframework.test.util.ReflectionTestUtils.setField(
        storage, "physicalReadEnabled", true);
    assertThat(HuntReadModel.install(database, storage.readModel(true))).isEmpty();
    var physical = new HuntSql(database, aliases, true).events(query(), now);
    assertThat(physical.sql())
        .doesNotContain("JSON_VALUE", "JSONExtract")
        .contains("raw.hunt2_id", "raw.hunt2_stored_at");
    assertThat(read(physical))
        .isEqualTo(expected); // older parts compute new defaults before backfill
    String partition =
        now.atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    String backfill =
        HuntReadModel.materialize(database, partition)
            .replace("mutations_sync=0", "mutations_sync=1");
    execute(backfill);
    assertThat(read(new HuntSql(database, aliases, true).events(query(), now))).isEqualTo(expected);
    execute(backfill); // no extra rows or new identities
    assertThat(read(new HuntSql(database, aliases, true).events(query(), now))).isEqualTo(expected);
    insert("auth.login.success", "new", Map.of("actor", "bob"));
    for (var compiler :
        List.of(new HuntSql(database, aliases), new HuntSql(database, aliases, true))) {
      assertThat(read(compiler.events(query(), now))).hasSize(4);
    }
    assertThat(read(new HuntSql(database, aliases, true).summary(query(), now)))
        .isEqualTo(read(new HuntSql(database, aliases).summary(query(), now)));
    assertThat(read(new HuntSql(database, aliases, true).groups(query(), now)))
        .isEqualTo(read(new HuntSql(database, aliases).groups(query(), now)));
    assertThat(read(new HuntSql(database, aliases, true).histogram(query(), now)))
        .isEqualTo(read(new HuntSql(database, aliases).histogram(query(), now)));
    var rows = read(new HuntSql(database, aliases, true).events(query(), now));
    var cursor =
        new HuntQuery.Cursor(
            Instant.parse(String.valueOf(rows.get(1).get("at"))),
            String.valueOf(rows.get(1).get("id")));
    var next = new HuntQuery(query().from(), query().to(), null, "", "actor", "count", 2, cursor);
    assertThat(read(new HuntSql(database, aliases, true).events(next, now)))
        .isEqualTo(read(new HuntSql(database, aliases).events(next, now)));
    var materialized =
        storage.read(
            new HuntSql.Statement(
                "SELECT count() AS count FROM system.parts_columns WHERE database={db:String} AND table='manager_events' AND active AND column='hunt2_actor' FORMAT JSONEachRow",
                Map.of("db", database)),
            "hunt-" + UUID.randomUUID() + "-verify");
    assertThat(((Number) materialized.getFirst().get("count")).longValue()).isPositive();
  }

  private HuntQuery query() {
    return new HuntQuery(
        now.minusSeconds(3600), now.plusSeconds(1), null, "", "actor", "count", 200, null);
  }

  private List<Map<String, Object>> read(HuntSql.Statement statement) {
    return storage.read(statement, "hunt-" + UUID.randomUUID() + "-verify");
  }

  private void insert(String type, String key, Map<String, Object> fields) throws Exception {
    var payload = new LinkedHashMap<String, Object>(fields);
    payload.put("dedup_key", key);
    var row =
        Map.of(
            "observed_at",
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneOffset.UTC)
                .format(now.minusSeconds(10)),
            "asset_uid",
            "sensor-old",
            "source_type",
            "AGENT",
            "source_id",
            "qa",
            "event_type",
            type,
            "event_json",
            json.writeValueAsString(payload));
    execute(
        "INSERT INTO "
            + HuntReadModel.table(database)
            + " FORMAT JSONEachRow\n"
            + json.writeValueAsString(row));
  }

  private void execute(String sql) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(endpoint))
                    .header("X-ClickHouse-User", "huntqa")
                    .header("X-ClickHouse-Key", "huntqa-local-only")
                    .timeout(Duration.ofSeconds(70))
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage("%s", response.body()).isEqualTo(200);
  }
}
