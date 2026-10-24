package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.castrelyx.manager.hunt.v2.HuntScenarioFixtures.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.config.ManagerProperties;
import org.castrelyx.manager.telemetry.CanonicalTelemetrySchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;

/** Real storage and readiness checks, with one independent dataset per behavioural case. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "HUNT_TEST_CLICKHOUSE_URL", matches = ".+")
class HuntScenarioAcceptanceTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final String database =
      "hunt_acceptance_" + UUID.randomUUID().toString().replace("-", "");
  private final String endpoint = System.getenv("HUNT_TEST_CLICKHOUSE_URL");
  private final HuntScenarios catalogue = new HuntScenarios();
  private HuntStorage storage;
  private HuntScenarioRunner runner;
  private Path evidence;
  private boolean physicalMode;
  private EmbeddedDatabase exceptionDatabase;
  private JdbcTemplate exceptionDb;
  private HuntExceptions exceptions;
  private HuntJobResults jobResults;
  private Instant exceptionNow = Instant.now();

  @BeforeEach
  void defaultLayout() {
    physicalMode = true;
  }

  @BeforeAll
  void start() throws Exception {
    execute("CREATE DATABASE " + database);
    execute(CanonicalTelemetrySchema.createEvents(database));
    storage =
        new HuntStorage(
            new ManagerProperties(
                "fixture",
                "http://localhost",
                new ManagerProperties.ClickHouse(
                    endpoint, database, "huntqa", "huntqa-local-only", "unused")),
            json) {
          @Override
          public boolean materialized() {
            return physicalMode;
          }

          @Override
          public List<Map<String, Object>> read(HuntSql.Statement statement, String queryId) {
            try {
              return super.read(statement, queryId);
            } catch (IllegalStateException failure) {
              try {
                String parameters =
                    statement.parameters().entrySet().stream()
                        .map(
                            e ->
                                "param_"
                                    + e.getKey()
                                    + "="
                                    + java.net.URLEncoder.encode(
                                        e.getValue(), StandardCharsets.UTF_8))
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
                throw new IllegalStateException(
                    "Synthetic fixture SQL diagnostic: " + response.body(), failure);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure;
              } catch (java.io.IOException diagnostic) {
                throw failure;
              }
            }
          }
        };
    execute(HuntReadModel.install(database, storage.readModel(true)));
    runner = new HuntScenarioRunner(catalogue, storage, mock(JdbcTemplate.class), json);
    exceptionDatabase =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName(
                "scenario-exceptions-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
            .addScript("classpath:db/migration/V10__Hunt_approved_exceptions.sql")
            .build();
    exceptionDb = new JdbcTemplate(exceptionDatabase);
    exceptionDb.execute("create table users(id bigint primary key,enabled boolean not null)");
    exceptionDb.update("insert into users(id,enabled) values(1,true),(2,true)");
    exceptionDb.execute(
        "create table hunt_audit(id bigint primary key auto_increment,actor_id bigint,action varchar(60),resource_id varchar(100),detail_json longtext,created_at timestamp(3))");
    exceptionDb.execute(
        "create table hunt_query_jobs(id varchar(36) primary key,owner_id bigint,state varchar(30),as_of timestamp(3),result_json longtext,updated_at timestamp(3))");
    Clock exceptionClock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return Clock.fixed(exceptionNow, zone);
          }

          public Instant instant() {
            return exceptionNow;
          }
        };
    var exceptionTx = new DataSourceTransactionManager(exceptionDatabase);
    exceptions = new HuntExceptions(exceptionDb, json, catalogue, exceptionClock, exceptionTx);
    jobResults = new HuntJobResults(exceptionDb, json, exceptionClock, exceptionTx);
    String directory = System.getenv("HUNT_ACCEPTANCE_RESULTS_DIR");
    if (directory != null && !directory.isBlank()) {
      evidence = Path.of(directory).toAbsolutePath();
      Files.createDirectories(evidence.resolve("fixtures"));
    }
  }

  @AfterAll
  void stop() throws Exception {
    try {
      execute("DROP DATABASE IF EXISTS " + database);
    } finally {
      if (exceptionDatabase != null) exceptionDatabase.shutdown();
    }
  }

  Stream<Arguments> approvedCases() {
    String selection = System.getenv("HUNT_ACCEPTANCE_SCENARIOS");
    Set<String> selected =
        selection == null || selection.isBlank() ? Set.of() : Set.of(selection.split(","));
    return catalogue.all().stream()
        .filter(s -> selected.isEmpty() || selected.contains(s.id()))
        .flatMap(s -> Stream.of(false, true).map(physical -> Arguments.of(s.id(), physical)));
  }

  @ParameterizedTest(name = "{0} / APPROVED / physical={1}")
  @MethodSource("approvedCases")
  void approvedNormalWorkIsScopedAuditedAndReappearsAfterExpiryOrRevocation(
      String id, boolean physical) throws Exception {
    physicalMode = physical;
    execute("TRUNCATE TABLE " + database + ".manager_events");
    exceptionDb.update("delete from hunt_exception_history");
    exceptionDb.update("delete from hunt_exceptions");
    exceptionDb.update("delete from hunt_audit");
    exceptionDb.update("delete from hunt_query_jobs");
    var report = new LinkedHashMap<String, Object>();
    report.put("id", id);
    report.put("variant", "APPROVED");
    report.put("layout", physical ? "physical" : "json");
    report.put("startedAt", Instant.now().toString());
    try {
      var definition = catalogue.require(id);
      var fixture = build(id, false, Instant.now().minusSeconds(3600));
      String source = id + "-APPROVED";
      Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      exceptionNow = asOf;
      var raw = rows(fixture, definition, Variant.SUSPICIOUS, source, asOf);
      var spec = query(fixture, source);
      String sourceJob = UUID.randomUUID().toString();
      if (evidence != null)
        Files.writeString(
            evidence
                .resolve("fixtures")
                .resolve(source + (physical ? "-physical" : "-json") + ".json"),
            encode(
                Map.of(
                    "scenario",
                    id,
                    "variant",
                    "APPROVED",
                    "query",
                    spec,
                    "asOf",
                    asOf,
                    "lookups",
                    fixture.lookups(),
                    "rows",
                    raw)));
      execute(
          "INSERT INTO "
              + database
              + ".manager_events FORMAT JSONEachRow\n"
              + String.join("\n", raw.stream().map(this::encode).toList()));
      var unapproved =
          runner.run(
              id,
              spec,
              asOf,
              sourceJob,
              Map.of(),
              new HuntScenarioRunner.Context(definition.version(), fixture.lookups()));
      assertThat(unapproved).containsEntry("readiness", "READY").containsEntry("complete", true);
      assertThat((List<?>) unapproved.get("findings")).hasSize(1);
      var job =
          new HuntJobs.Job(
              sourceJob,
              "COMPLETED",
              asOf,
              asOf,
              null,
              Map.of("query", spec, "scenario", unapproved));
      var analyst = new AuthUser(1, "qa-analyst", "QA", Role.OPERATOR);
      var administrator = new AuthUser(2, "qa-admin", "QA", Role.ADMIN);
      var request =
          exceptions.request(
              new HuntExceptions.Request(
                  sourceJob,
                  0,
                  "Approved QA work " + id,
                  "Reviewed legitimate work fixture",
                  spec.from().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                  asOf.plusSeconds(3600)),
              job,
              spec,
              Map.of(),
              analyst);
      String scope = (String) request.get("scopeKey");
      assertThat(exceptions.capture(id, definition.version(), scope, asOf)).isEmpty();
      var approved =
          exceptions.decide(
              (String) request.get("id"),
              new HuntExceptions.Decision(
                  1, "APPROVE", "Independent administrator reviewed exact scope"),
              administrator);
      var grants = exceptions.capture(id, definition.version(), scope, asOf);
      assertThat(grants).hasSize(1);
      var captured =
          new HuntScenarioRunner.Context(definition.version(), fixture.lookups(), scope, grants);
      String approvedJob = UUID.randomUUID().toString();
      var suppressed = runner.run(id, spec, asOf, approvedJob, Map.of(), captured);
      assertThat(suppressed).containsEntry("readiness", "READY").containsEntry("complete", true);
      assertThat((List<?>) suppressed.get("findings")).isEmpty();
      assertThat((List<?>) suppressed.get("suppressed")).hasSize(1);
      exceptionDb.update(
          "insert into hunt_query_jobs(id,owner_id,state,as_of) values(?,1,'RUNNING',?)",
          approvedJob,
          java.sql.Timestamp.from(asOf));
      jobResults.complete(approvedJob, Map.of("query", spec, "scenario", suppressed));
      jobResults.complete(approvedJob, Map.of("query", spec, "scenario", suppressed));
      assertThat(
              exceptionDb.queryForObject(
                  "select count(*) from hunt_audit where action='HUNT_EXCEPTION_APPLIED'",
                  Integer.class))
          .isEqualTo(1);
      @SuppressWarnings("unchecked")
      var candidate = (Map<String, Object>) ((List<?>) unapproved.get("findings")).getFirst();
      assertThat(
              HuntExceptionPolicy.apply(
                      definition, "0".repeat(64), asOf, grants, List.of(candidate))
                  .findings())
          .hasSize(1);
      exceptionNow = asOf.plusSeconds(3600);
      var expired = exceptions.capture(id, definition.version(), scope, exceptionNow);
      assertThat(expired).isEmpty();
      assertThat(
              HuntExceptionPolicy.apply(
                      definition, scope, exceptionNow, expired, List.of(candidate))
                  .findings())
          .hasSize(1);
      exceptionNow = asOf;
      var revoked =
          exceptions.decide(
              (String) approved.get("id"),
              new HuntExceptions.Decision(2, "REVOKE", "Maintenance ended; resume investigation"),
              analyst);
      assertThat(exceptions.capture(id, definition.version(), scope, asOf)).isEmpty();
      assertThat(
              HuntExceptionPolicy.apply(
                      definition, scope, asOf, captured.exceptions(), List.of(candidate))
                  .suppressed())
          .hasSize(1);
      assertThat(revoked.get("evidenceJson")).isEqualTo(request.get("evidenceJson"));
      assertThat(HuntWorkspace.hash((String) request.get("evidenceJson")))
          .isEqualTo(request.get("evidenceSha256"));
      assertThat(
              exceptionDb.queryForObject(
                  "select count(*) from hunt_exception_history", Integer.class))
          .isEqualTo(3);
      report.put(
          "expected",
          Map.of(
              "pendingFindings",
              1,
              "approvedFindings",
              0,
              "suppressed",
              1,
              "appliedAudits",
              1,
              "wrongScopeFindings",
              1,
              "expiredFindings",
              1,
              "revokedNewGrants",
              0,
              "frozenContextSuppressed",
              1));
      report.put("request", request);
      report.put("approved", approved);
      report.put("result", suppressed);
      report.put("revoked", revoked);
      report.put("status", "PASS");
      report.put(
          "scope",
          "Original and approved phases execute real ClickHouse SQL. Expiry/revocation/scope matcher checks reuse the exact original SQL candidate with real DB grants and production policy; no actual clock wait, sensor ingestion or scheduled rule notification is claimed.");
    } catch (Exception | AssertionError failure) {
      report.put("status", "FAIL");
      report.put("error", failure.toString());
      throw failure;
    } finally {
      if (evidence != null)
        Files.writeString(
            evidence.resolve("scenario-approved-results.jsonl"),
            encode(report) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }
  }

  Stream<Arguments> cases() {
    String selection = System.getenv("HUNT_ACCEPTANCE_SCENARIOS");
    Set<String> selected =
        selection == null || selection.isBlank() ? Set.of() : Set.of(selection.split(","));
    return catalogue.all().stream()
        .filter(s -> selected.isEmpty() || selected.contains(s.id()))
        .flatMap(
            s ->
                Arrays.stream(Variant.values())
                    .filter(
                        v ->
                            (v != Variant.OBSERVED_REVERSED && v != Variant.UNRELATED_CONTEXT)
                                || ORDERED.contains(s.id()))
                    .flatMap(
                        v ->
                            Stream.of(false, true)
                                .map(physical -> Arguments.of(s.id(), v, physical))));
  }

  @ParameterizedTest(name = "{0} / {1} / physical={2}")
  @MethodSource("cases")
  void behaviourMatchesAcceptancePlan(String id, Variant variant, boolean physical)
      throws Exception {
    physicalMode = physical;
    long began = System.nanoTime();
    var report = new LinkedHashMap<String, Object>();
    report.put("id", id);
    report.put("variant", variant.name());
    report.put("layout", physical ? "physical" : "json");
    report.put("startedAt", Instant.now().toString());
    try {
      execute("TRUNCATE TABLE " + database + ".manager_events");
      var definition = catalogue.require(id);
      var fixture = build(id, variant == Variant.BENIGN, Instant.now().minusSeconds(3600));
      String source = id + "-" + variant.name();
      Instant asOf = Instant.now();
      var rows = rows(fixture, definition, variant, source, asOf);
      var spec = query(fixture, source);
      if (evidence != null)
        Files.writeString(
            evidence
                .resolve("fixtures")
                .resolve(source + (physical ? "-physical" : "-json") + ".json"),
            json.writeValueAsString(
                Map.of(
                    "scenario",
                    id,
                    "variant",
                    variant.name(),
                    "query",
                    spec,
                    "asOf",
                    asOf,
                    "lookups",
                    fixture.lookups(),
                    "rows",
                    rows)));
      execute(
          "INSERT INTO "
              + database
              + ".manager_events FORMAT JSONEachRow\n"
              + String.join("\n", rows.stream().map(this::encode).toList()));
      var result =
          runner.run(
              id,
              spec,
              asOf,
              UUID.randomUUID().toString(),
              Map.of(),
              new HuntScenarioRunner.Context(definition.version(), fixture.lookups()));
      report.put("actual", result);
      if (variant == Variant.MISSING || variant == Variant.LATE) {
        String state =
            variant == Variant.LATE || definition.requiredFields().isEmpty()
                ? "UNAVAILABLE"
                : "PARTIAL";
        report.put("expected", Map.of("readiness", state, "complete", false, "findings", 0));
        assertThat(result).containsEntry("readiness", state).containsEntry("complete", false);
        assertThat((List<?>) result.get("findings")).isEmpty();
      } else {
        int expected = variant == Variant.BENIGN || variant == Variant.OBSERVED_REVERSED ? 0 : 1;
        report.put(
            "expected", Map.of("readiness", "READY", "complete", true, "findings", expected));
        assertThat(result).containsEntry("readiness", "READY").containsEntry("complete", true);
        var findings = (List<?>) result.get("findings");
        assertThat(findings).as(id + " " + variant).hasSize(expected);
        if (expected == 1) {
          @SuppressWarnings("unchecked")
          var finding = (Map<String, Object>) findings.getFirst();
          definition
              .keys()
              .forEach(
                  key -> {
                    Object expectedKey =
                        key.equals("asset_uid")
                            ? fixture.current().getFirst().asset()
                            : key.equals("registered_domain")
                                ? "example.com"
                                : fixture.current().getFirst().fields().get(key);
                    assertThat(finding).containsKey(key);
                    if (expectedKey instanceof Number n)
                      assertThat(((Number) finding.get(key)).doubleValue())
                          .isEqualTo(n.doubleValue());
                    else
                      assertThat(finding.get(key))
                          .as(id + " identity " + key)
                          .isEqualTo(expectedKey);
                  });
          Instant first =
              fixture
                  .anchor()
                  .minusSeconds(
                      fixture.current().stream().mapToLong(Event::ageSeconds).max().orElseThrow())
                  .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
          Instant last =
              fixture
                  .anchor()
                  .minusSeconds(
                      fixture.current().stream().mapToLong(Event::ageSeconds).min().orElseThrow())
                  .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
          assertThat(eventTime(finding.get("first_seen")))
              .as(id + " matching episode start")
              .isEqualTo(first);
          assertThat(eventTime(finding.get("last_seen")))
              .as(id + " matching episode end")
              .isEqualTo(last);
          // Replay must not multiply either count, distinct cardinality, or bytes.
          if (variant == Variant.REPLAY) {
            execute("TRUNCATE TABLE " + database + ".manager_events");
            var original = rows(fixture, definition, Variant.SUSPICIOUS, source, asOf);
            execute(
                "INSERT INTO "
                    + database
                    + ".manager_events FORMAT JSONEachRow\n"
                    + String.join("\n", original.stream().map(this::encode).toList()));
            var single =
                runner.run(
                    id,
                    spec,
                    asOf,
                    UUID.randomUUID().toString(),
                    Map.of(),
                    new HuntScenarioRunner.Context(definition.version(), fixture.lookups()));
            assertThat(findings).isEqualTo(single.get("findings"));
          }
        }
      }
      report.put("status", "PASS");
    } catch (Exception | AssertionError failure) {
      report.put("status", "FAIL");
      report.put("error", String.valueOf(failure.getMessage()));
      throw failure;
    } finally {
      report.put("durationMs", (System.nanoTime() - began) / 1_000_000);
      if (evidence != null)
        Files.writeString(
            evidence.resolve("scenario-results.jsonl"),
            encode(report) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }
  }

  @ParameterizedTest(name = "{0} / TWO_DISJOINT / physical={1}")
  @MethodSource("approvedCases")
  void twoDisjointEpisodesRemainSeparate(String id, boolean physical) throws Exception {
    physicalMode = physical;
    var report = new LinkedHashMap<String, Object>();
    report.put("id", id);
    report.put("variant", "TWO_DISJOINT");
    report.put("layout", physical ? "physical" : "json");
    report.put("startedAt", Instant.now().toString());
    try {
      execute("TRUNCATE TABLE " + database + ".manager_events");
      var definition = catalogue.require(id);
      // Keep both copies in the past and retain the same local hour for business-hour scenarios.
      var single = build(id, false, Instant.now().minusSeconds(2 * 86400L + 3600));
      long separation = 86400L;
      var current = new ArrayList<Event>(single.current());
      single.current().forEach(event -> current.add(event.ago(event.ageSeconds() - separation)));
      var fixture = new Fixture(single.anchor(), current, single.history(), single.lookups());
      String source = id + "-TWO_DISJOINT";
      var original = query(single, source);
      var spec =
          new HuntQuery(
              original.from(),
              original.to().plusSeconds(separation),
              original.filter(),
              "",
              "event_type",
              "count",
              200,
              null);
      Instant asOf = Instant.now();
      var rows = rows(fixture, definition, Variant.SUSPICIOUS, source, asOf);
      if (evidence != null)
        Files.writeString(
            evidence
                .resolve("fixtures")
                .resolve(source + (physical ? "-physical" : "-json") + ".json"),
            json.writeValueAsString(
                Map.of(
                    "scenario",
                    id,
                    "variant",
                    "TWO_DISJOINT",
                    "query",
                    spec,
                    "asOf",
                    asOf,
                    "rows",
                    rows,
                    "lookups",
                    fixture.lookups())));
      execute(
          "INSERT INTO "
              + database
              + ".manager_events FORMAT JSONEachRow\n"
              + String.join("\n", rows.stream().map(this::encode).toList()));
      var result =
          runner.run(
              id,
              spec,
              asOf,
              UUID.randomUUID().toString(),
              Map.of(),
              new HuntScenarioRunner.Context(definition.version(), fixture.lookups()));
      report.put(
          "expected",
          Map.of(
              "readiness", "READY", "complete", true, "findings", 2, "shiftSeconds", separation));
      report.put("actual", result);
      assertThat(result.get("readiness")).isEqualTo("READY");
      assertThat(result.get("complete")).isEqualTo(true);
      @SuppressWarnings("unchecked")
      var candidates = (List<Map<String, Object>>) result.get("findings");
      assertThat(candidates).hasSize(2);
      var ordered =
          candidates.stream()
              .sorted(
                  Comparator.comparing(value -> HuntExceptionPolicy.time(value.get("first_seen"))))
              .toList();
      for (String field : List.of("first_seen", "last_seen"))
        assertThat(HuntExceptionPolicy.time(ordered.get(1).get(field)))
            .isEqualTo(HuntExceptionPolicy.time(ordered.get(0).get(field)).plusSeconds(separation));
      report.put("status", "PASS");
    } catch (Exception | AssertionError failure) {
      report.put("status", "FAIL");
      report.put("error", failure.toString());
      throw failure;
    } finally {
      if (evidence != null)
        Files.writeString(
            evidence.resolve("scenario-episode-results.jsonl"),
            encode(report) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }
  }

  Stream<Arguments> episodeBoundaryCases() {
    String selection = System.getenv("HUNT_ACCEPTANCE_SCENARIOS");
    Set<String> selected =
        selection == null || selection.isBlank() ? Set.of() : Set.of(selection.split(","));
    return Stream.of(
            Arguments.of("A01", "SAME_TIME", 1),
            Arguments.of("A01", "GAP_EQUAL", 1),
            Arguments.of("A01", "GAP_PLUS_MILLISECOND", 2),
            Arguments.of("D01", "DISJOINT_BEACONS", 2))
        .filter(args -> selected.isEmpty() || selected.contains(args.get()[0]))
        .flatMap(
            args ->
                Stream.of(false, true)
                    .map(
                        physical ->
                            Arguments.of(args.get()[0], args.get()[1], args.get()[2], physical)));
  }

  @ParameterizedTest(name = "{0} / {1} / physical={3}")
  @MethodSource("episodeBoundaryCases")
  void episodeBoundariesUseExactObservedMilliseconds(
      String id, String name, int expected, boolean physical) throws Exception {
    physicalMode = physical;
    var report = new LinkedHashMap<String, Object>();
    report.put("id", id);
    report.put("variant", name);
    report.put("layout", physical ? "physical" : "json");
    report.put("expected", Map.of("findings", expected));
    try {
      execute("TRUNCATE TABLE " + database + ".manager_events");
      Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
          anchor = asOf.minusSeconds(7200);
      var definition = catalogue.require(id);
      String source = id + "-EPISODE-" + name;
      var values = new ArrayList<Long>();
      if (name.equals("SAME_TIME")) {
        for (int i = 0; i < 6; i++) values.add(0L);
      } else if (id.equals("D01")) {
        for (long offset : List.of(0L, 2460000L))
          for (int i = 0; i < 20; i++) values.add(offset + i * 10000L);
      } else {
        long second = name.equals("GAP_EQUAL") ? 305000L : 305001L;
        for (long offset : List.of(0L, second))
          for (int i = 0; i < 6; i++) values.add(offset + i * 1000L);
      }
      var event =
          new Event(
              id.equals("D01") ? "zeek.conn" : "auth.login.failure",
              0,
              "episode-host",
              Map.of(
                  "src_ip",
                  "192.0.2.216",
                  "dst_ip",
                  "198.51.100.216",
                  "dst_port",
                  443,
                  "protocol",
                  "tcp",
                  "actor",
                  "episode-user",
                  "realm",
                  "episode-realm"));
      var rows = new ArrayList<Map<String, Object>>();
      int index = 0;
      for (long offset : values)
        rows.add(
            row(event, anchor.plusMillis(offset), source, index++, asOf.minusSeconds(1), null));
      var spec =
          new HuntQuery(
              anchor.minusMillis(1),
              asOf,
              new HuntQuery.Filter("eq", "source_id", List.of(source), null),
              "",
              "event_type",
              "count",
              200,
              null);
      if (evidence != null)
        Files.writeString(
            evidence
                .resolve("fixtures")
                .resolve(source + (physical ? "-physical" : "-json") + ".json"),
            json.writeValueAsString(
                Map.of(
                    "scenario", id, "variant", name, "query", spec, "asOf", asOf, "rows", rows)));
      execute(
          "INSERT INTO "
              + database
              + ".manager_events FORMAT JSONEachRow\n"
              + String.join("\n", rows.stream().map(this::encode).toList()));
      var result =
          runner.run(
              id,
              spec,
              asOf,
              UUID.randomUUID().toString(),
              Map.of(),
              new HuntScenarioRunner.Context(definition.version(), Map.of()));
      report.put("actual", result);
      assertThat(result.get("readiness")).isEqualTo("READY");
      assertThat(result.get("complete")).isEqualTo(true);
      assertThat((List<?>) result.get("findings")).hasSize(expected);
      report.put("status", "PASS");
    } catch (Exception | AssertionError failure) {
      report.put("status", "FAIL");
      report.put("error", failure.toString());
      throw failure;
    } finally {
      if (evidence != null)
        Files.writeString(
            evidence.resolve("scenario-episode-boundary-results.jsonl"),
            encode(report) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }
  }

  private List<Map<String, Object>> rows(
      Fixture fixture,
      HuntScenarios.Scenario definition,
      Variant variant,
      String source,
      Instant asOf) {
    var result = new ArrayList<Map<String, Object>>();
    int sequence = 0;
    for (Event event : fixture.history())
      result.add(row(event, fixture.anchor(), source, sequence++, asOf.minusSeconds(1), null));
    for (Event event : fixture.current()) {
      if (variant == Variant.MISSING && definition.requiredFields().isEmpty()) continue;
      String missing = variant == Variant.MISSING ? definition.requiredFields().getFirst() : null;
      if (variant == Variant.OBSERVED_REVERSED) {
        long oldest = fixture.current().stream().mapToLong(Event::ageSeconds).max().orElseThrow(),
            newest = fixture.current().stream().mapToLong(Event::ageSeconds).min().orElseThrow();
        event = event.ago(oldest + newest - event.ageSeconds());
      }
      result.add(
          row(
              event,
              fixture.anchor(),
              source,
              sequence++,
              variant == Variant.LATE ? asOf.plusSeconds(10) : asOf.minusSeconds(1),
              missing));
    }
    if (variant == Variant.UNRELATED_CONTEXT) {
      Event first = fixture.current().getFirst();
      result.add(
          row(
              new Event("collector.heartbeat", 6000, first.asset(), first.fields()),
              fixture.anchor(),
              source,
              sequence++,
              asOf.minusSeconds(1),
              null));
      result.add(
          row(
              new Event("collector.heartbeat", 0, first.asset(), first.fields()),
              fixture.anchor(),
              source,
              sequence++,
              asOf.minusSeconds(1),
              null));
    }
    if (variant == Variant.REPLAY) result.addAll(new ArrayList<>(result));
    if (variant == Variant.ARRIVAL_REVERSED) Collections.reverse(result);
    return result;
  }

  private Map<String, Object> row(
      Event event, Instant anchor, String source, int sequence, Instant stored, String missing) {
    var payload = new LinkedHashMap<String, Object>();
    event
        .fields()
        .forEach(
            (key, value) -> {
              if (!key.equals(missing)) payload.put(payloadName(key), value);
            });
    payload.put("event_uid", source + "-" + sequence);
    payload.put("stored_at", stored.toString());
    return Map.of(
        "observed_at",
        anchor.minusSeconds(event.ageSeconds()).toString().replace('T', ' ').replace("Z", ""),
        "asset_uid",
        event.asset(),
        "source_type",
        "AGENT",
        "source_id",
        source,
        "event_type",
        event.type(),
        "severity",
        "INFO",
        "event_json",
        encode(payload));
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Instant eventTime(Object value) {
    String text = String.valueOf(value).replace(' ', 'T');
    return Instant.parse(text.endsWith("Z") ? text : text + "Z");
  }

  @ParameterizedTest
  @ValueSource(strings = {"F02", "F03"})
  void oneEventCannotProveTwoOrderedStages(String id) throws Exception {
    execute("TRUNCATE TABLE " + database + ".manager_events");
    Instant anchor = Instant.now().minusSeconds(3600), asOf = Instant.now();
    Event one =
        proc(id.equals("F02") ? "/bin/bash" : "powershell.exe")
            .with("parent_name", "/usr/sbin/nginx")
            .with("command_line", "powershell -EncodedCommand ZQBjAGgAbwA=")
            .with("dst_ip", "198.51.100.8");
    execute(
        "INSERT INTO "
            + database
            + ".manager_events FORMAT JSONEachRow\n"
            + encode(row(one, anchor, "single-event", 0, asOf.minusSeconds(1), null)));
    var fixture = new Fixture(anchor, List.of(one), List.of(), Map.of());
    var result =
        runner.run(
            id,
            query(fixture, "single-event"),
            asOf,
            UUID.randomUUID().toString(),
            Map.of(),
            new HuntScenarioRunner.Context(catalogue.require(id).version(), Map.of()));
    assertThat((List<?>) result.get("findings"))
        .as("one row cannot prove a later network event")
        .isEmpty();
  }

  @Test
  void slidingWindowDoesNotRoundAwaySubsecondBoundaries() throws Exception {
    execute("TRUNCATE TABLE " + database + ".manager_events");
    Instant
        anchor =
            Instant.now()
                .minusSeconds(3600)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plusMillis(500),
        asOf = Instant.now();
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 5; i++) {
      var row =
          new LinkedHashMap<>(
              row(
                  auth(false).ago(0),
                  anchor,
                  "millisecond-boundary",
                  i,
                  asOf.minusSeconds(1),
                  null));
      if (i < 4)
        row.put(
            "observed_at",
            anchor.minusMillis(300001).toString().replace('T', ' ').replace("Z", ""));
      rows.add(row);
    }
    execute(
        "INSERT INTO "
            + database
            + ".manager_events FORMAT JSONEachRow\n"
            + String.join("\n", rows.stream().map(this::encode).toList()));
    var fixture = new Fixture(anchor, List.of(), List.of(), Map.of());
    var result =
        runner.run(
            "A01",
            query(fixture, "millisecond-boundary"),
            asOf,
            UUID.randomUUID().toString(),
            Map.of(),
            new HuntScenarioRunner.Context(catalogue.require("A01").version(), Map.of()));
    assertThat((List<?>) result.get("findings"))
        .as("four failures 300.001 seconds earlier are outside a five minute window")
        .isEmpty();
  }

  private void execute(String sql) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(70))
                    .header("X-ClickHouse-User", "huntqa")
                    .header("X-ClickHouse-Key", "huntqa-local-only")
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalStateException(response.body());
  }
}
