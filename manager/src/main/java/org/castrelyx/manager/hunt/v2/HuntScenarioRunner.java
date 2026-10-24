package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HuntScenarioRunner {
  private final HuntScenarios catalogue;
  private final HuntStorage storage;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final HuntExceptions exceptions;

  public HuntScenarioRunner(
      HuntScenarios catalogue, HuntStorage storage, JdbcTemplate db, ObjectMapper json) {
    this(catalogue, storage, db, json, null);
  }

  @Autowired
  public HuntScenarioRunner(
      HuntScenarios catalogue,
      HuntStorage storage,
      JdbcTemplate db,
      ObjectMapper json,
      HuntExceptions exceptions) {
    this.catalogue = catalogue;
    this.storage = storage;
    this.db = db;
    this.json = json;
    this.exceptions = exceptions;
  }

  public record Context(
      String scenarioVersion,
      Map<String, List<String>> lookups,
      String exceptionScope,
      List<HuntExceptionPolicy.Grant> exceptions,
      String exceptionPolicyVersion) {
    public Context(String scenarioVersion, Map<String, List<String>> lookups) {
      this(scenarioVersion, lookups, null, List.of(), HuntExceptionPolicy.VERSION);
    }

    public Context(
        String scenarioVersion,
        Map<String, List<String>> lookups,
        String exceptionScope,
        List<HuntExceptionPolicy.Grant> exceptions) {
      this(scenarioVersion, lookups, exceptionScope, exceptions, HuntExceptionPolicy.VERSION);
    }

    public Context {
      exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }
  }

  public Context capture(String id) {
    return new Context(catalogue.require(id).version(), lookups());
  }

  public Context capture(String id, HuntQuery query, Map<String, String> aliases, Instant asOf) {
    var definition = catalogue.require(id);
    String scope = HuntExceptionPolicy.scopeKey(json, id, definition.version(), query, aliases);
    return new Context(
        definition.version(),
        lookups(),
        scope,
        exceptions == null ? List.of() : exceptions.capture(id, definition.version(), scope, asOf));
  }

  public void validate(String id, HuntQuery query) {
    catalogue.require(id);
    if (!query.text().isEmpty())
      throw new IllegalArgumentException("시나리오에는 자유 검색어 대신 자산·주소 범위를 사용하세요.");
    validateScope(query.filter());
  }

  public static void validateScope(HuntQuery.Filter filter) {
    if (filter == null) return;
    if (Set.of("and", "or", "not").contains(String.valueOf(filter.op()))) {
      if (filter.children() != null) filter.children().forEach(HuntScenarioRunner::validateScope);
      return;
    }
    if (filter.field() == null
        || !Set.of("asset_uid", "src_ip", "dst_ip", "source_id", "source_type", "realm", "actor")
            .contains(filter.field()))
      throw new IllegalArgumentException(
          "시나리오 범위에는 자산·주소·계정·원천 조건만 사용할 수 있습니다. 이벤트 결과 조건은 연관 근거를 누락시킬 수 있습니다.");
  }

  public Map<String, Object> run(
      String id,
      HuntQuery query,
      Instant asOf,
      String job,
      Map<String, String> aliases,
      Context context) {
    var definition = catalogue.require(id);
    if (context == null || !definition.version().equals(context.scenarioVersion()))
      throw new IllegalStateException("시나리오 버전이 변경되었습니다. 새 실행을 시작하세요.");
    if (!context.exceptions().isEmpty()
        && !HuntExceptionPolicy.VERSION.equals(context.exceptionPolicyVersion()))
      throw new IllegalStateException("승인 예외 정책 버전이 변경되었습니다. 새 실행을 시작하세요.");
    var lookups = context.lookups();
    var missingLookups =
        definition.requiredLookups().stream()
            .filter(name -> !lookups.containsKey(name) || lookups.get(name).isEmpty())
            .toList();
    HuntSql compiler = new HuntSql(storage.database(), aliases, storage.materialized());
    HuntQuery history =
        new HuntQuery(
            query.from().minusSeconds(14 * 86400L),
            query.from(),
            null,
            "",
            "event_type",
            "count",
            200,
            null);
    var statsSql =
        new StringBuilder(
            "SELECT count() AS total,min(observed_at) AS oldest,max(observed_at) AS newest");
    for (String field : definition.requiredFields())
      statsSql
          .append(",countIf(")
          .append(
              HuntFields.require(field).type().equals("number")
                  ? "isNotNull(" + field + ")"
                  : field + "!=''")
          .append(") AS has_")
          .append(field);
    // Current visibility and historical continuity are separate checks. Old data cannot make an
    // empty selection ready.
    String current = compiler.source(query, asOf), prior = compiler.source(history, asOf);
    statsSql
        .append(" FROM ")
        .append(current)
        .append(" WHERE ")
        .append(compiler.where(query))
        .append(" FORMAT JSONEachRow");
    var rows = storage.read(compiler.statement(statsSql.toString()), "hunt-" + job + "-readiness");
    var stats = rows.isEmpty() ? Map.<String, Object>of() : rows.getFirst();
    var observations = new LinkedHashMap<>(stats);
    var missingFields =
        definition.requiredFields().stream()
            .filter(f -> number(stats.get("has_" + f)) == 0)
            .toList();
    String readiness =
        number(stats.get("total")) == 0
            ? "UNAVAILABLE"
            : !missingFields.isEmpty()
                ? "PARTIAL"
                : !missingLookups.isEmpty() ? "NEEDS_CONFIGURATION" : "READY";
    if (readiness.equals("READY") && definition.baseline()) {
      var baseline =
          storage.read(
              compiler.statement(
                  "SELECT uniqExact(toDate(observed_at)) AS days,min(observed_at) AS"
                      + " oldest,max(observed_at) AS newest FROM "
                      + prior
                      + " WHERE "
                      + compiler.where(query)
                      + " FORMAT JSONEachRow"),
              "hunt-" + job + "-baseline");
      var continuity = baseline.isEmpty() ? Map.<String, Object>of() : baseline.getFirst();
      if (number(continuity.get("days")) < 14) readiness = "WARMING_UP";
      observations.put("baseline", continuity);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("version", definition.version());
    result.put("readiness", readiness);
    result.put("context", context);
    result.put("missingFields", missingFields);
    result.put("missingLookups", missingLookups);
    result.put("observations", observations);
    result.put("findings", List.of());
    result.put("suppressed", List.of());
    result.put("complete", false);
    if (!readiness.equals("READY")) return result;
    var findings =
        storage.read(
            catalogue.compile(
                definition,
                query,
                asOf,
                lookups,
                new HuntSql(storage.database(), aliases, storage.materialized())),
            "hunt-" + job + "-scenario");
    var evaluated =
        HuntExceptionPolicy.apply(
            definition,
            context.exceptionScope(),
            asOf,
            context.exceptions(),
            findings.stream().limit(100).toList());
    result.put("findings", evaluated.findings());
    result.put("suppressed", evaluated.suppressed());
    result.put("exceptionPolicyVersion", HuntExceptionPolicy.VERSION);
    result.put("hasMore", findings.size() > 100);
    result.put("complete", findings.size() <= 100);
    result.put("caveat", "조건에 일치한 조사 후보입니다. 승인 작업·원천 가시성·오탐 여부를 확인하세요.");
    return result;
  }

  private Map<String, List<String>> lookups() {
    var result = new HashMap<String, List<String>>();
    db.query(
        "select kind,title,body_json from hunt_resources where shared=true and kind in"
            + " ('lookup','indicator')",
        rs -> {
          try {
            Map<String, Object> body =
                json.readValue(rs.getString("body_json"), new TypeReference<>() {});
            if (rs.getString("kind").equals("indicator")) {
              Object expires = body.get("expiresAt");
              if (!Boolean.FALSE.equals(body.get("enabled"))
                  && expires != null
                  && Instant.parse(String.valueOf(expires)).isAfter(Instant.now())
                  && body.get("value") instanceof String value) {
                result.computeIfAbsent("indicators", k -> new ArrayList<>()).add(value);
                result
                    .computeIfAbsent("indicator_" + body.get("type"), k -> new ArrayList<>())
                    .add(value);
              }
            } else if (body.get("values") instanceof List<?> values) {
              result.put(rs.getString("title"), values.stream().map(String::valueOf).toList());
            }
          } catch (Exception e) {
            throw new IllegalStateException("룩업 또는 IOC 형식을 확인하세요.");
          }
        });
    return result;
  }

  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }
}
