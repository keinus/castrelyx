package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HuntExceptions {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final HuntScenarios catalogue;
  private final Clock clock;
  private final TransactionTemplate tx;

  public HuntExceptions(
      JdbcTemplate db,
      ObjectMapper json,
      HuntScenarios catalogue,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.db = db;
    this.json = json;
    this.catalogue = catalogue;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
  }

  public record Request(
      String jobId,
      @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CandidateIndex.class)
          Integer candidateIndex,
      String title,
      String reason,
      Instant validFrom,
      Instant expiresAt,
      String sourceKind) {
    public Request(
        String jobId,
        int candidateIndex,
        String title,
        String reason,
        Instant validFrom,
        Instant expiresAt) {
      this(jobId, candidateIndex, title, reason, validFrom, expiresAt, "FINDING");
    }
  }

  public static final class CandidateIndex
      extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(
        com.fasterxml.jackson.core.JsonParser parser,
        com.fasterxml.jackson.databind.DeserializationContext context)
        throws java.io.IOException {
      if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)
        throw com.fasterxml.jackson.databind.JsonMappingException.from(
            parser, "후보 인덱스는 정수로 지정하세요.");
      return parser.getIntValue();
    }
  }

  public record Decision(long version, String action, String reason) {}

  public record Cursor(Instant at, String id) {}

  public record Page(List<Map<String, Object>> items, Cursor nextCursor, boolean hasMore) {}

  private record Stored(
      String id,
      String state,
      long version,
      long requestedBy,
      Long approvedBy,
      Instant createdAt,
      Instant updatedAt,
      Instant approvedAt,
      String sha,
      String evidenceJson,
      Map<String, Object> payload) {}

  public Map<String, Object> request(
      Request input,
      HuntJobs.Job job,
      HuntQuery query,
      Map<String, String> aliases,
      AuthUser user) {
    writer(user);
    if (input == null || job == null || !Objects.equals(input.jobId(), job.id()))
      throw new IllegalArgumentException("시나리오 작업을 선택하세요.");
    if (!"COMPLETED".equals(job.state()))
      throw new IllegalStateException("완료된 시나리오 후보에서 예외를 요청하세요.");
    Map<String, Object> result = json.convertValue(job.result(), new TypeReference<>() {});
    if (result == null
        || !(result.get("scenario") instanceof Map<?, ?> scenario)
        || !"READY".equals(scenario.get("readiness"))
        || !Boolean.TRUE.equals(scenario.get("complete")))
      throw new IllegalArgumentException("완전한 실행 가능 결과에서 예외를 요청하세요.");
    var definition = catalogue.require(String.valueOf(scenario.get("id")));
    if (!definition.version().equals(scenario.get("version")))
      throw new IllegalStateException("정의가 변경되었습니다. 시나리오를 다시 실행하세요.");
    String sourceKind = input.sourceKind() == null ? "FINDING" : input.sourceKind();
    if (!Set.of("FINDING", "SUPPRESSED").contains(sourceKind))
      throw new IllegalArgumentException("후보의 원본 구분을 확인하세요.");
    Object sourceCandidates =
        scenario.get(sourceKind.equals("SUPPRESSED") ? "suppressed" : "findings");
    if (input.candidateIndex() == null
        || !(sourceCandidates instanceof List<?> findings)
        || input.candidateIndex() < 0
        || input.candidateIndex() >= findings.size())
      throw new IllegalArgumentException("시나리오 후보를 선택하세요.");
    Map<String, Object> selected =
        json.convertValue(findings.get(input.candidateIndex()), new TypeReference<>() {});
    if (selected == null) throw new IllegalArgumentException("후보 원문을 확인하세요.");
    Map<String, Object> candidate =
        sourceKind.equals("SUPPRESSED")
            ? json.convertValue(selected.get("finding"), new TypeReference<>() {})
            : selected;
    if (candidate == null) throw new IllegalArgumentException("억제된 원본 후보를 확인하세요.");
    var identity = HuntExceptionPolicy.identity(definition, candidate);
    Instant now = now();
    validateWindow(input.validFrom(), input.expiresAt(), now);
    Instant first = HuntExceptionPolicy.time(candidate.get("first_seen")),
        last = HuntExceptionPolicy.time(candidate.get("last_seen"));
    if (first == null || last == null || last.isBefore(first))
      throw new IllegalArgumentException("선택한 후보의 관측 구간을 확인하세요.");
    String title = bounded(input.title(), 240, "예외 제목"),
        reason = bounded(input.reason(), 4000, "요청 근거");
    var payload = new LinkedHashMap<String, Object>();
    payload.put("title", title);
    payload.put("reason", reason);
    payload.put("scenarioId", definition.id());
    payload.put("scenarioVersion", definition.version());
    payload.put("exceptionPolicyVersion", HuntExceptionPolicy.VERSION);
    payload.put(
        "scopeKey",
        HuntExceptionPolicy.scopeKey(json, definition.id(), definition.version(), query, aliases));
    payload.put("scope", identity);
    payload.put("validFrom", input.validFrom().toString());
    payload.put("expiresAt", input.expiresAt().toString());
    payload.put("sourceKind", sourceKind);
    payload.put(
        "source",
        Map.of(
            "jobId",
            job.id(),
            "candidateIndex",
            input.candidateIndex(),
            "asOf",
            job.asOf().toString(),
            "query",
            query,
            "aliases",
            new TreeMap<>(aliases),
            "candidate",
            candidate));
    String encoded = encode(payload);
    if (encoded.getBytes(StandardCharsets.UTF_8).length > 131072)
      throw new IllegalArgumentException("예외 근거가 128KiB를 초과합니다. 조회 범위를 좁혀 다시 실행하세요.");
    return tx.execute(
        status -> {
          var active =
              db.queryForList(
                  "select id from users where id=? and enabled=true for update", user.id());
          if (active.isEmpty()) throw new ForbiddenException("활성 요청자 계정이 필요합니다.");
          Integer pending =
              db.queryForObject(
                  "select count(*) from hunt_exceptions where requested_by=? and state='REQUESTED' and expires_at>?",
                  Integer.class,
                  user.id(),
                  ts(now));
          if (pending != null && pending >= 50)
            throw new IllegalStateException("진행 중인 예외 요청 50개를 먼저 검토하세요.");
          String id = UUID.randomUUID().toString();
          db.update(
              "insert into hunt_exceptions(id,scenario_id,scenario_version,scope_key,requested_by,state,version,valid_from,expires_at,created_at,updated_at,payload_json,evidence_sha256) values(?,?,?,?,?,'REQUESTED',1,?,?,?,?,?,?)",
              id,
              definition.id(),
              definition.version(),
              payload.get("scopeKey"),
              user.id(),
              ts(input.validFrom()),
              ts(input.expiresAt()),
              ts(now),
              ts(now),
              encoded,
              HuntWorkspace.hash(encoded));
          history(id, 1, "REQUESTED", reason, user, now);
          return detail(id, user);
        });
  }

  public Map<String, Object> decide(String id, Decision input, AuthUser user) {
    writer(user);
    if (input == null
        || input.action() == null
        || !Set.of("APPROVE", "REJECT", "REVOKE").contains(input.action()))
      throw new IllegalArgumentException("승인·반려·철회를 선택하세요.");
    String note = bounded(input.reason(), 4000, "판정 근거");
    return tx.execute(
        status -> {
          Stored row = locked(id);
          requireRead(row, user);
          if (row.version() != input.version())
            throw new IllegalStateException("예외가 변경되었습니다. 최신 버전을 불러오세요.");
          String next;
          Instant now = now();
          if (input.action().equals("REVOKE")) {
            if (user.role() != Role.ADMIN && row.requestedBy() != user.id())
              throw new ForbiddenException("요청자·관리자만 예외를 철회할 수 있습니다.");
            if (!Set.of("REQUESTED", "APPROVED").contains(row.state()))
              throw new IllegalStateException("이미 종료된 예외입니다.");
            next = "REVOKED";
          } else {
            if (user.role() != Role.ADMIN) throw new ForbiddenException("예외 판정에는 관리자 권한이 필요합니다.");
            if (row.requestedBy() == user.id())
              throw new ForbiddenException("요청자와 다른 관리자가 예외를 검토해야 합니다.");
            if (!row.state().equals("REQUESTED"))
              throw new IllegalStateException("대기 중인 예외만 판정할 수 있습니다.");
            if (!instant(row.payload(), "expiresAt").isAfter(now))
              throw new IllegalStateException("예외 기간이 만료되었습니다. 새 요청을 작성하세요.");
            var definition = catalogue.require(String.valueOf(row.payload().get("scenarioId")));
            if (!definition.version().equals(row.payload().get("scenarioVersion")))
              throw new IllegalStateException("시나리오 정의가 변경되었습니다. 새 실행에서 요청하세요.");
            next = input.action().equals("APPROVE") ? "APPROVED" : "REJECTED";
          }
          int changed;
          if (next.equals("APPROVED"))
            changed =
                db.update(
                    "update hunt_exceptions set state=?,version=version+1,approved_by=?,approved_at=?,updated_at=? where id=? and version=?",
                    next,
                    user.id(),
                    ts(now),
                    ts(now),
                    id,
                    row.version());
          else
            changed =
                db.update(
                    "update hunt_exceptions set state=?,version=version+1,updated_at=? where id=? and version=?",
                    next,
                    ts(now),
                    id,
                    row.version());
          if (changed != 1) throw new IllegalStateException("예외가 변경되었습니다. 최신 버전을 불러오세요.");
          history(id, row.version() + 1, next, note, user, now);
          return detail(id, user);
        });
  }

  public Page list(Cursor cursor, AuthUser user) {
    if (cursor != null
        && (cursor.at() == null || cursor.id() == null || !cursor.id().matches("[a-f0-9-]{36}")))
      throw new IllegalArgumentException("예외 목록 커서를 확인하세요.");
    String sql =
        "select * from hunt_exceptions where (requested_by=? or ?=true or (approved_by is not null and state in ('APPROVED','REVOKED')))";
    var args = new ArrayList<Object>(List.of(user.id(), user.role() == Role.ADMIN));
    if (cursor != null) {
      sql += " and (created_at<? or (created_at=? and id<?))";
      args.add(ts(cursor.at()));
      args.add(ts(cursor.at()));
      args.add(cursor.id());
    }
    var rows =
        db.query(sql + " order by created_at desc,id desc limit 51", this::row, args.toArray());
    var page = rows.stream().limit(50).toList();
    var items = page.stream().map(row -> summary(row, user)).toList();
    return new Page(
        items,
        rows.size() > 50 ? new Cursor(page.getLast().createdAt(), page.getLast().id()) : null,
        rows.size() > 50);
  }

  public Map<String, Object> detail(String id, AuthUser user) {
    var row = find(id);
    requireRead(row, user);
    var result = new LinkedHashMap<>(summary(row, user));
    result.put("evidence", row.payload());
    result.put("evidenceJson", row.evidenceJson());
    result.put(
        "history",
        db.queryForList(
            "select version,state,actor_id,note,created_at from hunt_exception_history where exception_id=? order by version",
            id));
    result.put("usage", usage(id, null, user));
    return result;
  }

  public Map<String, Object> usage(String id, Long before, AuthUser user) {
    requireRead(find(id), user);
    if (before != null && before <= 0) throw new IllegalArgumentException("적용 이력 커서를 확인하세요.");
    var args = new ArrayList<Object>();
    args.add(id);
    if (before != null) args.add(before);
    var rows =
        db.query(
            "select id,actor_id,detail_json,created_at from hunt_audit where resource_id=? and action='HUNT_EXCEPTION_APPLIED'"
                + (before == null ? "" : " and id<?")
                + " order by id desc limit 21",
            (rs, n) ->
                Map.<String, Object>of(
                    "id",
                    rs.getLong("id"),
                    "actorId",
                    rs.getLong("actor_id"),
                    "createdAt",
                    rs.getTimestamp("created_at").toInstant(),
                    "detail",
                    decode(rs.getString("detail_json"))),
            args.toArray());
    var items = rows.stream().limit(20).toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("items", items);
    result.put("hasMore", rows.size() > 20);
    result.put("nextCursor", rows.size() > 20 ? items.getLast().get("id") : null);
    return result;
  }

  public List<HuntExceptionPolicy.Grant> capture(
      String scenario, String version, String scopeKey, Instant asOf) {
    var rows =
        db.query(
            "select * from hunt_exceptions where scenario_id=? and scenario_version=? and scope_key=? and state='APPROVED' and valid_from<=? and expires_at>? and approved_at<=? order by created_at,id limit 501",
            this::row,
            scenario,
            version,
            scopeKey,
            ts(asOf),
            ts(asOf),
            ts(asOf));
    if (rows.size() > 500) throw new IllegalStateException("동일 범위의 유효 예외가 500개를 초과합니다. 예외를 정리하세요.");
    return rows.stream()
        .map(
            row -> {
              if (row.approvedBy() == null
                  || row.approvedAt() == null
                  || row.approvedBy() == row.requestedBy())
                throw new IllegalStateException("예외 승인 기록이 손상되었습니다.");
              return new HuntExceptionPolicy.Grant(
                  row.id(),
                  row.version(),
                  scenario,
                  version,
                  scopeKey,
                  scope(row),
                  instant(row.payload(), "validFrom"),
                  instant(row.payload(), "expiresAt"),
                  row.approvedBy(),
                  row.approvedAt(),
                  String.valueOf(row.payload().get("title")),
                  String.valueOf(row.payload().get("reason")),
                  row.sha());
            })
        .toList();
  }

  private Stored locked(String id) {
    return db.query("select * from hunt_exceptions where id=? for update", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("예외 요청을 찾을 수 없습니다."));
  }

  private Stored find(String id) {
    return db.query("select * from hunt_exceptions where id=?", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("예외 요청을 찾을 수 없습니다."));
  }

  private Stored row(ResultSet rs, int n) throws SQLException {
    String encoded = rs.getString("payload_json"), sha = rs.getString("evidence_sha256");
    if (!HuntWorkspace.hash(encoded).equals(sha))
      throw new IllegalStateException("예외 근거 해시가 일치하지 않습니다.");
    Map<String, Object> payload = decode(encoded);
    if (!Objects.equals(rs.getString("scenario_id"), payload.get("scenarioId"))
        || !Objects.equals(rs.getString("scenario_version"), payload.get("scenarioVersion"))
        || !Objects.equals(rs.getString("scope_key"), payload.get("scopeKey"))
        || !rs.getTimestamp("valid_from").toInstant().equals(instant(payload, "validFrom"))
        || !rs.getTimestamp("expires_at").toInstant().equals(instant(payload, "expiresAt")))
      throw new IllegalStateException("예외 범위 기록이 일치하지 않습니다.");
    return new Stored(
        rs.getString("id"),
        rs.getString("state"),
        rs.getLong("version"),
        rs.getLong("requested_by"),
        rs.getObject("approved_by") == null ? null : rs.getLong("approved_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
        sha,
        encoded,
        payload);
  }

  private Map<String, Object> summary(Stored row, AuthUser user) {
    var data = new LinkedHashMap<String, Object>();
    data.put("id", row.id());
    data.put("state", row.state());
    data.put(
        "effectiveState",
        Set.of("REQUESTED", "APPROVED").contains(row.state())
                && !instant(row.payload(), "expiresAt").isAfter(now())
            ? "EXPIRED"
            : row.state().equals("APPROVED") && now().isBefore(instant(row.payload(), "validFrom"))
                ? "SCHEDULED"
                : row.state());
    data.put("version", row.version());
    data.put("requestedBy", row.requestedBy());
    data.put("approvedBy", row.approvedBy());
    data.put("approvedAt", row.approvedAt());
    data.put("createdAt", row.createdAt());
    data.put("updatedAt", row.updatedAt());
    data.put("evidenceSha256", row.sha());
    data.put(
        "permissions",
        Map.of(
            "decide",
            user.role() == Role.ADMIN
                && row.requestedBy() != user.id()
                && row.state().equals("REQUESTED")
                && instant(row.payload(), "expiresAt").isAfter(now()),
            "revoke",
            user.role() != Role.VIEWER
                && (user.role() == Role.ADMIN || row.requestedBy() == user.id())
                && Set.of("REQUESTED", "APPROVED").contains(row.state())));
    for (String key :
        List.of(
            "title",
            "reason",
            "scenarioId",
            "scenarioVersion",
            "scopeKey",
            "scope",
            "validFrom",
            "expiresAt")) data.put(key, row.payload().get(key));
    return data;
  }

  private void history(
      String id, long version, String state, String note, AuthUser user, Instant now) {
    db.update(
        "insert into hunt_exception_history(exception_id,version,state,actor_id,note,created_at) values(?,?,?,?,?,?)",
        id,
        version,
        state,
        user.id(),
        note,
        ts(now));
    db.update(
        "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,?,?,?,?)",
        user.id(),
        "HUNT_EXCEPTION_" + state,
        id,
        encode(Map.of("version", version, "reason", note)),
        ts(now));
  }

  private static void requireRead(Stored row, AuthUser user) {
    if (user.role() != Role.ADMIN
        && row.requestedBy() != user.id()
        && !(row.approvedBy() != null && Set.of("APPROVED", "REVOKED").contains(row.state())))
      throw new ForbiddenException("검토 중인 다른 사용자의 예외 요청입니다.");
  }

  private static void writer(AuthUser user) {
    if (user.role() == Role.VIEWER) throw new ForbiddenException("조회 전용 사용자입니다.");
  }

  private static String bounded(String value, int max, String name) {
    if (value == null || value.isBlank() || value.trim().length() > max)
      throw new IllegalArgumentException(name + "를 1~" + max + "자로 작성하세요.");
    return value.trim();
  }

  private static void validateWindow(Instant from, Instant to, Instant now) {
    if (from == null
        || to == null
        || !from.equals(from.truncatedTo(ChronoUnit.MILLIS))
        || !to.equals(to.truncatedTo(ChronoUnit.MILLIS))
        || !from.isBefore(to)
        || !to.isAfter(now)
        || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0
        || from.isBefore(now.minus(Duration.ofDays(30)))
        || to.isAfter(now.plus(Duration.ofDays(30))))
      throw new IllegalArgumentException("예외 기간은 현재 시점 전후 30일 이내·최대 30일이며 밀리초 정밀도로 지정하세요.");
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }

  private static Instant instant(Map<String, Object> payload, String key) {
    return Instant.parse(String.valueOf(payload.get(key)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> scope(Stored row) {
    return (Map<String, Object>) row.payload().get("scope");
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("예외 근거를 읽을 수 없습니다.", e);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("예외 근거를 저장할 수 없습니다.", e);
    }
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }
}
