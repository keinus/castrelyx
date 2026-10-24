package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.auth.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class HuntJobs {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final HuntStorage storage;
  private final HuntScenarioRunner scenarios;
  private final AssetService assets;
  private final Clock clock;
  private final HuntJobResults results;
  private final ExecutorService executor = Executors.newFixedThreadPool(3);
  private final Set<String> running = ConcurrentHashMap.newKeySet();

  public HuntJobs(
      JdbcTemplate db,
      ObjectMapper json,
      HuntStorage storage,
      HuntScenarioRunner scenarios,
      AssetService assets,
      Clock clock,
      HuntJobResults results) {
    this.db = db;
    this.json = json;
    this.storage = storage;
    this.scenarios = scenarios;
    this.assets = assets;
    this.clock = clock;
    this.results = results;
  }

  public record Job(
      String id, String state, Instant asOf, Instant createdAt, String error, Object result) {}

  public record Page(
      List<Map<String, Object>> items, HuntQuery.Cursor nextCursor, boolean hasMore) {}

  public Job create(HuntQuery query, AuthUser user) {
    return create(query, user, null);
  }

  public Job create(HuntQuery query, AuthUser user, String scenarioId) {
    if (user.role() == Role.VIEWER) {
      if (!query.text().isBlank())
        throw new ForbiddenException("원문 전체 검색에는 분석가 권한이 필요합니다. 필드 조건으로 검색하세요.");
      if (query.dimensions().stream().anyMatch(Set.of("command_line", "message")::contains))
        throw new ForbiddenException("원문 필드 집계에는 분석가 권한이 필요합니다.");
      rejectPrivateFilter(query.filter());
    }
    if (scenarioId != null) scenarios.validate(scenarioId, query);
    // Compile before queuing: malformed requests never turn into background failures.
    new HuntSql(storage.database()).events(query, Instant.now(clock));
    new HuntSql(storage.database()).groups(query, Instant.now(clock));
    synchronized (this) {
      Integer count =
          db.queryForObject(
              "select count(*) from hunt_query_jobs where owner_id=? and state in"
                  + " ('QUEUED','RUNNING')",
              Integer.class,
              user.id());
      if (count != null && count >= 2)
        throw new IllegalStateException("진행 중인 조회가 있습니다. 완료하거나 취소한 뒤 다시 실행하세요.");
      Integer total =
          db.queryForObject(
              "select count(*) from hunt_query_jobs where state in ('QUEUED','RUNNING')",
              Integer.class);
      if (total != null && total >= 20) throw new IllegalStateException("조회 대기열이 가득 찼습니다.");
      String id = UUID.randomUUID().toString();
      Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
      var aliases = assets.telemetryAliases();
      db.update(
          "insert into"
              + " hunt_query_jobs(id,owner_id,scenario_id,query_json,state,created_at,updated_at,as_of,expires_at,asset_aliases_json,scenario_context_json)"
              + " values(?,?,?,?,'QUEUED',?,?,?,?,?,?)",
          id,
          user.id(),
          scenarioId,
          encode(query.withoutCursor()),
          ts(now),
          ts(now),
          ts(now),
          ts(now.plus(1, ChronoUnit.DAYS)),
          encode(aliases),
          scenarioId == null ? null : encode(scenarios.capture(scenarioId, query, aliases, now)));
      return get(id, user);
    }
  }

  private static void rejectPrivateFilter(HuntQuery.Filter filter) {
    if (filter == null) return;
    if (filter.field() != null && Set.of("command_line", "message").contains(filter.field()))
      throw new ForbiddenException("원문 필드 조건에는 분석가 권한이 필요합니다.");
    if (filter.children() != null) filter.children().forEach(HuntJobs::rejectPrivateFilter);
  }

  public Job get(String id, AuthUser user) {
    return db
        .query(
            "select * from hunt_query_jobs where id=?",
            (rs, n) -> {
              if (rs.getLong("owner_id") != user.id())
                throw new ForbiddenException("다른 사용자의 조회에 접근할 수 없습니다.");
              if (!rs.getTimestamp("expires_at").toInstant().isAfter(Instant.now(clock)))
                throw new IllegalStateException("조회 결과가 만료되었습니다. 저장 검색으로 다시 실행하세요.");
              return new Job(
                  id,
                  rs.getString("state"),
                  rs.getTimestamp("as_of").toInstant(),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getString("error_message"),
                  decode(rs.getString("result_json"), Object.class));
            },
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("조회 작업을 찾을 수 없습니다."));
  }

  public HuntQuery spec(String id, AuthUser user) {
    get(id, user);
    return decode(
        db.queryForObject("select query_json from hunt_query_jobs where id=?", String.class, id),
        HuntQuery.class);
  }

  public Map<String, String> aliases(String id, AuthUser user) {
    get(id, user);
    return aliasMap(
        db.queryForObject(
            "select asset_aliases_json from hunt_query_jobs where id=?", String.class, id));
  }

  public HuntScenarioRunner.Context context(String id, AuthUser user) {
    get(id, user);
    return decode(
        db.queryForObject(
            "select scenario_context_json from hunt_query_jobs where id=?", String.class, id),
        HuntScenarioRunner.Context.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> aliasMap(String value) {
    return value == null ? Map.of() : decode(value, Map.class);
  }

  public Page page(String id, HuntQuery.Cursor cursor, AuthUser user) {
    Job job = get(id, user);
    if (!job.state().equals("COMPLETED")) throw new IllegalStateException("완료된 조회에서 페이지를 탐색하세요.");
    HuntQuery original = spec(id, user);
    HuntQuery query = original.withCursor(cursor);
    return events(query, job.asOf(), id, aliases(id, user));
  }

  public Map<String, Object> event(String id, String eventId, AuthUser user) {
    Job job = get(id, user);
    if (!"COMPLETED".equals(job.state())) throw new IllegalStateException("완료된 조회에서 근거를 확인하세요.");
    var rows =
        storage.read(
            new HuntSql(storage.database(), aliases(id, user), storage.materialized())
                .event(spec(id, user), job.asOf(), eventId),
            "hunt-" + id + "-detail");
    if (rows.isEmpty()) throw new IllegalArgumentException("조회 범위에 해당 근거가 없거나 원본이 만료되었습니다.");
    return rows.getFirst();
  }

  public Job cancel(String id, AuthUser user) {
    get(id, user);
    int changed =
        db.update(
            "update hunt_query_jobs set state='CANCELLED',updated_at=? where id=? and state in"
                + " ('QUEUED','RUNNING')",
            ts(Instant.now(clock)),
            id);
    if (changed > 0)
      try {
        storage.cancel(id);
      } catch (RuntimeException ignored) {
        /* read timeout still bounds a disconnected server */
      }
    return get(id, user);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    db.update(
        "update hunt_query_jobs set state='FAILED',error_message='서버가 재시작되었습니다. 다시"
            + " 실행하세요.',updated_at=? where state='RUNNING'",
        ts(Instant.now(clock)));
  }

  @Scheduled(fixedDelay = 500, initialDelay = 2000)
  public void dispatch() {
    if (running.size() >= 3) return;
    List<Map<String, Object>> jobs =
        db.queryForList(
            "select id,query_json,as_of,scenario_id,asset_aliases_json,scenario_context_json from"
                + " hunt_query_jobs where state='QUEUED' and expires_at>? order by created_at limit"
                + " 3",
            ts(Instant.now(clock)));
    for (var row : jobs) {
      String id = (String) row.get("id");
      if (running.size() >= 3) break;
      if (db.update(
              "update hunt_query_jobs set state='RUNNING',updated_at=? where id=? and"
                  + " state='QUEUED'",
              ts(Instant.now(clock)),
              id)
          != 1) continue;
      running.add(id);
      executor.submit(
          () -> {
            try {
              HuntQuery query = decode((String) row.get("query_json"), HuntQuery.class);
              Instant asOf = ((Timestamp) row.get("as_of")).toInstant();
              var aliases = aliasMap((String) row.get("asset_aliases_json"));
              var summary =
                  storage.read(
                      new HuntSql(storage.database(), aliases, storage.materialized())
                          .summary(query, asOf),
                      "hunt-" + id + "-summary");
              checkCancelled(id);
              var histogram =
                  storage.read(
                      new HuntSql(storage.database(), aliases, storage.materialized())
                          .histogram(query, asOf),
                      "hunt-" + id + "-histogram");
              checkCancelled(id);
              var groups =
                  storage.read(
                      new HuntSql(storage.database(), aliases, storage.materialized())
                          .groups(query, asOf),
                      "hunt-" + id + "-groups");
              checkCancelled(id);
              var result = new LinkedHashMap<String, Object>();
              result.put("summary", summary.isEmpty() ? Map.of() : summary.getFirst());
              result.put("histogram", histogram);
              result.put("groups", groupRows(groups.stream().limit(100).toList(), query));
              result.put("groupHasMore", groups.size() > 100);
              result.put("page", events(query, asOf, id, aliases));
              result.put("query", query);
              result.put("groupLimit", 100);
              if (query.includeRelations()) {
                checkCancelled(id);
                var observations =
                    storage.read(
                        new HuntSql(storage.database(), aliases, storage.materialized())
                            .relations(query, asOf),
                        "hunt-" + id + "-relations");
                long total =
                    summary.isEmpty()
                        ? 0
                        : Long.parseLong(String.valueOf(summary.getFirst().get("total")));
                result.put("relations", HuntRelations.build(observations, total));
                checkCancelled(id);
              }
              if (row.get("scenario_id") instanceof String scenarioId) {
                checkCancelled(id);
                result.put(
                    "scenario",
                    scenarios.run(
                        scenarioId,
                        query,
                        asOf,
                        id,
                        aliases,
                        decode(
                            (String) row.get("scenario_context_json"),
                            HuntScenarioRunner.Context.class)));
              }
              result.put(
                  "coverage",
                  Map.of(
                      "requestedFrom",
                      query.from(),
                      "requestedTo",
                      query.to(),
                      "asOf",
                      asOf,
                      "complete",
                      true,
                      "sampled",
                      false,
                      "schemaVersion",
                      "hunt-2.0",
                      "assetAliases",
                      aliases));
              results.complete(id, result);
            } catch (Exception e) {
              String error =
                  e instanceof IllegalStateException
                      ? e.getMessage()
                      : "조회 실행에 실패했습니다. 저장소와 조회 조건을 확인하세요.";
              db.update(
                  "update hunt_query_jobs set state='FAILED',error_message=?,updated_at=? where"
                      + " id=? and state='RUNNING'",
                  error.substring(0, Math.min(990, error.length())),
                  ts(Instant.now(clock)),
                  id);
            } finally {
              running.remove(id);
            }
          });
    }
  }

  private void checkCancelled(String id) {
    if (!"RUNNING"
        .equals(
            db.queryForObject("select state from hunt_query_jobs where id=?", String.class, id)))
      throw new IllegalStateException("조회가 취소되었습니다.");
  }

  private Page events(HuntQuery query, Instant asOf, String id, Map<String, String> aliases) {
    var rows =
        storage.read(
            new HuntSql(storage.database(), aliases, storage.materialized()).events(query, asOf),
            "hunt-" + id + "-events");
    boolean more = rows.size() > query.pageSize();
    var items = rows.stream().limit(query.pageSize()).toList();
    var last = items.isEmpty() ? null : items.getLast();
    HuntQuery.Cursor next =
        more
            ? new HuntQuery.Cursor(
                Instant.parse(String.valueOf(last.get("at"))), String.valueOf(last.get("id")))
            : null;
    return new Page(items, next, more);
  }

  private static List<Map<String, Object>> groupRows(
      List<Map<String, Object>> rows, HuntQuery query) {
    return rows.stream()
        .map(
            row -> {
              var copy = new LinkedHashMap<String, Object>(row);
              var dimensions = new LinkedHashMap<String, Object>();
              for (int i = 0; i < query.dimensions().size(); i++) {
                String alias = i == 0 ? "value" : "dimension_" + i;
                dimensions.put(query.dimensions().get(i), row.get(alias));
                if (i > 0) copy.remove(alias);
              }
              copy.put("dimensions", dimensions);
              return (Map<String, Object>) copy;
            })
        .toList();
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("저장할 데이터를 확인하세요.", e);
    }
  }

  public <T> T decode(String value, Class<T> type) {
    if (value == null) return null;
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("저장된 조회를 해석할 수 없습니다.", e);
    }
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }

  @PreDestroy
  public void stop() {
    executor.shutdownNow();
  }
}
