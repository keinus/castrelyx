package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable searches, investigations, rule drafts and evidence with optimistic concurrency. */
@Service
public class HuntWorkspace {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final HuntJobs jobs;
  private final HuntStorage storage;
  private final HuntCaseAccess cases;
  private final Clock clock;

  public HuntWorkspace(
      JdbcTemplate db,
      ObjectMapper json,
      HuntJobs jobs,
      HuntStorage storage,
      HuntCaseAccess cases,
      Clock clock) {
    this.db = db;
    this.json = json;
    this.jobs = jobs;
    this.storage = storage;
    this.cases = cases;
    this.clock = clock;
  }

  public record Resource(
      String id,
      String kind,
      long ownerId,
      boolean shared,
      String title,
      Map<String, Object> body,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Map<String, Boolean> casePermissions) {
    public Resource(
        String id,
        String kind,
        long ownerId,
        boolean shared,
        String title,
        Map<String, Object> body,
        long version,
        Instant createdAt,
        Instant updatedAt) {
      this(id, kind, ownerId, shared, title, body, version, createdAt, updatedAt, null);
    }
  }

  public record Save(String title, boolean shared, Map<String, Object> body, long version) {}

  public List<Resource> list(String kind, AuthUser user) {
    kind(kind);
    if (kind.equals("investigation")) {
      if (!cases.active(user)) throw new ForbiddenException("활성 계정이 필요합니다.");
      return db.query(
          "select r.*,w.assignee_id from hunt_resources r left join hunt_case_workflow w on w.investigation_id=r.id where r.kind='investigation' and (r.owner_id=? or r.shared=true or (?<> 'VIEWER' and exists(select 1 from hunt_case_workflow m where m.investigation_id=r.id and (m.assignee_id=? or m.reviewer_id=?)))) order by r.updated_at desc,r.id limit 200",
          (rs, n) -> {
            var item = resource(rs, n);
            boolean manager =
                user.role() != Role.VIEWER
                    && (item.ownerId() == user.id() || user.role() == Role.ADMIN);
            boolean editable =
                HuntCaseAccess.editable(item)
                    && user.role() != Role.VIEWER
                    && (manager
                        || Objects.equals(rs.getObject("assignee_id", Long.class), user.id()));
            return withCasePermissions(
                item, true, editable, manager && HuntCaseAccess.editable(item));
          },
          user.id(),
          user.role().name(),
          user.id(),
          user.id());
    }
    return db.query(
        "select * from hunt_resources where kind=? and (owner_id=? or shared=true) order by"
            + " updated_at desc limit 200",
        this::resource,
        kind,
        user.id());
  }

  public Resource get(String id, AuthUser user) {
    var resource =
        db.query("select * from hunt_resources where id=?", this::resource, id).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("저장된 항목을 찾을 수 없습니다."));
    if (resource.kind().equals("investigation")) cases.requireRead(resource, user);
    else if (resource.ownerId() != user.id() && !resource.shared())
      throw new ForbiddenException("공유되지 않은 항목입니다.");
    return exposeCase(resource, user);
  }

  @Transactional
  public Resource save(String kind, String id, Save input, AuthUser user) {
    return saveInternal(kind, id, input, user, false);
  }

  Resource saveRuleState(String id, Save input, AuthUser user) {
    return saveInternal("rule", id, input, user, true);
  }

  private Resource saveInternal(
      String kind, String id, Save input, AuthUser user, boolean transition) {
    mutate(user);
    kind(kind);
    validate(kind, input);
    if (kind.equals("rule")
        && !transition
        && !"DRAFT".equals(input.body().getOrDefault("state", "DRAFT")))
      throw new IllegalArgumentException("규칙 운영 상태는 전용 전환 기능에서 변경하세요.");
    Instant now = Instant.now(clock);
    boolean creating = id == null;
    if (id == null) {
      id = UUID.randomUUID().toString();
      db.update(
          "insert into"
              + " hunt_resources(id,kind,owner_id,shared,title,body_json,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,1,?,?)",
          id,
          kind,
          user.id(),
          input.shared(),
          input.title().trim(),
          encode(input.body()),
          ts(now),
          ts(now));
      if (kind.equals("investigation")) cases.initialize(id, user.id());
    } else {
      Resource previous =
          kind.equals("investigation") ? lockInvestigation(id, user) : get(id, user);
      if (!previous.kind().equals(kind)) throw new IllegalArgumentException("항목 종류가 일치하지 않습니다.");
      if (kind.equals("investigation")) {
        cases.requireEdit(previous, user);
        if (previous.shared() != input.shared() && !cases.manage(previous, user))
          throw new ForbiddenException("조사 공유 범위는 소유자·관리자만 변경할 수 있습니다.");
      } else if (previous.ownerId() != user.id() && user.role() != Role.ADMIN)
        throw new ForbiddenException("소유자만 변경할 수 있습니다.");
      if (kind.equals("rule")
          && !transition
          && Set.of("OBSERVE", "ACTIVE").contains(String.valueOf(previous.body().get("state"))))
        throw new IllegalStateException("규칙을 일시 중지한 후 새 초안으로 수정하세요.");
      if (db.update(
              "update hunt_resources set"
                  + " title=?,shared=?,body_json=?,version=version+1,updated_at=? where id=? and"
                  + " version=?",
              input.title().trim(),
              input.shared(),
              encode(input.body()),
              ts(now),
              id,
              input.version())
          != 1) throw new IllegalStateException("다른 사용자가 변경했습니다. 새로고침 후 다시 저장하세요.");
      if (kind.equals("investigation")) cases.edited(id, user.id());
    }
    if (kind.equals("lookup")) {
      db.update("delete from hunt_lookup_names where resource_id=?", id);
      try {
        db.update(
            "insert into hunt_lookup_names(name,resource_id) values(?,?)",
            input.title().trim(),
            id);
      } catch (DuplicateKeyException e) {
        throw new IllegalStateException("같은 이름의 룩업이 있습니다. 기존 항목을 수정하세요.");
      }
    }
    var savedDetails = new LinkedHashMap<String, Object>();
    savedDetails.put("title", input.title());
    savedDetails.put("shared", input.shared());
    if (kind.equals("investigation")) {
      savedDetails.put("previousVersion", creating ? 0 : input.version());
      savedDetails.put("caseVersion", creating ? 1 : input.version() + 1);
    }
    audit(user, "SAVE_" + kind, id, savedDetails);
    if (kind.equals("investigation")) {
      // The authorized update may remove its actor's access (for example, unsharing).
      // Return its committed result without turning a subsequent read denial into a rollback.
      return exposeCase(
          db.queryForObject("select * from hunt_resources where id=?", this::resource, id), user);
    }
    return get(id, user);
  }

  @Transactional
  public Map<String, Object> pin(
      String investigation, String jobId, String eventId, AuthUser user) {
    mutate(user);
    Resource target = lockInvestigation(investigation, user);
    if (!target.kind().equals("investigation")) throw new IllegalArgumentException("조사를 선택하세요.");
    cases.requireEdit(target, user);
    if (eventId == null || !eventId.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("잘못된 이벤트 ID입니다.");
    HuntJobs.Job job = jobs.get(jobId, user);
    if (!job.state().equals("COMPLETED")) throw new IllegalStateException("완료된 조회에서 증거를 고정하세요.");
    HuntQuery query = jobs.spec(jobId, user);
    var aliases = jobs.aliases(jobId, user);
    HuntSql compiler = new HuntSql(storage.database(), aliases, storage.materialized());
    String source = compiler.source(query, job.asOf()), where = compiler.where(query);
    var rows =
        storage.read(
            compiler.statement(
                "SELECT * FROM "
                    + source
                    + " WHERE "
                    + where
                    + " AND id="
                    + compiler.parameter(eventId, "String")
                    + " LIMIT 1 FORMAT JSONEachRow"),
            "hunt-" + jobId + "-evidence");
    if (rows.isEmpty()) throw new IllegalArgumentException("이 조회 범위에 해당 증거가 없거나 원본이 만료되었습니다.");
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("event", rows.getFirst());
    snapshot.put("query", query);
    snapshot.put("asOf", job.asOf());
    snapshot.put("schemaVersion", "hunt-2.0");
    snapshot.put("assetAliases", aliases);
    snapshot.put("scenarioContext", jobs.context(jobId, user));
    String encoded = encode(snapshot), hash = hash(encoded), id = UUID.randomUUID().toString();
    Instant now = Instant.now(clock);
    db.update(
        "delete from hunt_evidence where investigation_id=? and event_id=? and expires_at<=?",
        investigation,
        eventId,
        ts(now));
    var existing =
        db.query(
            "select * from hunt_evidence where investigation_id=? and event_id=?",
            this::evidenceRow,
            investigation,
            eventId);
    if (!existing.isEmpty()) {
      return Map.of(
          "id",
          existing.getFirst().get("id"),
          "alreadyPinned",
          true,
          "previousVersion",
          target.version(),
          "caseVersion",
          target.version());
    }
    db.update(
        "insert into"
            + " hunt_evidence(id,investigation_id,event_id,query_job_id,snapshot_json,sha256,created_by,created_at,expires_at)"
            + " values(?,?,?,?,?,?,?,?,?)",
        id,
        investigation,
        eventId,
        jobId,
        encoded,
        hash,
        user.id(),
        ts(now),
        ts(now.plus(365, ChronoUnit.DAYS)));
    writeCase(
        target,
        target.body(),
        user,
        "PIN_EVIDENCE",
        Map.of("evidenceId", id, "sha256", hash),
        true);
    return Map.of(
        "id",
        id,
        "sha256",
        hash,
        "previousVersion",
        target.version(),
        "caseVersion",
        target.version() + 1);
  }

  Resource lockInvestigation(String id, AuthUser user) {
    var resource =
        db.query("select * from hunt_resources where id=? for update", this::resource, id).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("조사를 찾을 수 없습니다."));
    if (!resource.kind().equals("investigation")) throw new IllegalArgumentException("조사를 선택하세요.");
    cases.requireRead(resource, user);
    return resource;
  }

  Resource writeCase(
      Resource previous,
      Map<String, Object> body,
      AuthUser actor,
      String action,
      Map<String, Object> details,
      boolean edited) {
    if (db.update(
            "update hunt_resources set body_json=?,version=version+1,updated_at=? where id=? and version=?",
            encode(body),
            ts(Instant.now(clock)),
            previous.id(),
            previous.version())
        != 1) throw new IllegalStateException("다른 사용자가 변경했습니다. 최신 기록을 불러오세요.");
    if (edited) cases.edited(previous.id(), actor.id());
    var auditDetails = new LinkedHashMap<String, Object>(details);
    auditDetails.put("previousVersion", previous.version());
    auditDetails.put("caseVersion", previous.version() + 1);
    audit(actor, action, previous.id(), auditDetails);
    return exposeCase(
        new Resource(
            previous.id(),
            previous.kind(),
            previous.ownerId(),
            previous.shared(),
            previous.title(),
            body,
            previous.version() + 1,
            previous.createdAt(),
            Instant.now(clock)),
        actor);
  }

  private Resource exposeCase(Resource resource, AuthUser user) {
    if (!resource.kind().equals("investigation")) return resource;
    boolean visible =
        resource.ownerId() == user.id() || resource.shared() || cases.member(resource.id(), user);
    return withCasePermissions(
        resource,
        visible,
        visible && cases.edit(resource, user),
        visible && cases.manage(resource, user) && HuntCaseAccess.editable(resource));
  }

  private Resource withCasePermissions(Resource item, boolean read, boolean edit, boolean manage) {
    return new Resource(
        item.id(),
        item.kind(),
        item.ownerId(),
        item.shared(),
        item.title(),
        item.body(),
        item.version(),
        item.createdAt(),
        item.updatedAt(),
        Map.of("read", read, "edit", edit, "manage", manage));
  }

  public List<Map<String, Object>> evidence(String id, AuthUser user) {
    get(id, user);
    return db.query(
        "select * from hunt_evidence where investigation_id=? and expires_at>? order by created_at",
        this::evidenceRow,
        id,
        ts(Instant.now(clock)));
  }

  Map<String, Object> evidenceRow(ResultSet rs, int n) throws SQLException {
    String snapshot = rs.getString("snapshot_json"), sha256 = rs.getString("sha256");
    verifySnapshot(snapshot, sha256);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", rs.getString("id"));
    row.put("eventId", rs.getString("event_id"));
    row.put("sha256", sha256);
    row.put("snapshot", decode(snapshot));
    row.put("snapshotJson", snapshot);
    row.put("createdAt", rs.getTimestamp("created_at").toInstant());
    row.put("expiresAt", rs.getTimestamp("expires_at").toInstant());
    return row;
  }

  static final class IntegrityException extends IllegalStateException {
    IntegrityException() {
      super("저장 snapshot의 해시가 일치하지 않습니다. 저장 자료를 확인하세요.");
    }
  }

  static void verifySnapshot(String snapshot, String sha256) {
    if (!hash(snapshot).equals(sha256)) throw new IntegrityException();
  }

  public List<Map<String, Object>> auditLog(String id, AuthUser user) {
    get(id, user);
    return db.query(
        "select actor_id,action,detail_json,created_at from hunt_audit where resource_id=? order by"
            + " id desc limit 200",
        (rs, n) ->
            Map.of(
                "actorId",
                rs.getLong("actor_id"),
                "action",
                rs.getString("action"),
                "detail",
                decode(rs.getString("detail_json")),
                "at",
                rs.getTimestamp("created_at").toInstant()),
        id);
  }

  public void audit(AuthUser user, String action, String id, Object detail) {
    db.update(
        "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at)"
            + " values(?,?,?,?,?)",
        user.id(),
        action,
        id,
        encode(detail),
        ts(Instant.now(clock)));
  }

  private void validate(String kind, Save input) {
    if (input == null
        || input.title() == null
        || input.title().isBlank()
        || input.title().length() > 240
        || input.body() == null
        || encode(input.body()).length() > 500_000)
      throw new IllegalArgumentException("제목과 저장 내용을 확인하세요.");
    HuntContent.validate(kind, input);
    if (kind.equals("saved-search")) {
      HuntQuery query = json.convertValue(input.body().get("query"), HuntQuery.class);
      if (query == null) throw new IllegalArgumentException("저장할 조회 조건이 필요합니다.");
      new HuntSql(storage.database()).events(query, Instant.now(clock));
      new HuntSql(storage.database()).groups(query, Instant.now(clock));
    }
    if (kind.equals("investigation")) {
      if (!Set.of("DRAFT", "INVESTIGATING")
          .contains(String.valueOf(input.body().getOrDefault("status", "DRAFT"))))
        throw new IllegalArgumentException("검토·종료·재개는 조사 협업의 전용 절차를 사용하세요.");
      if (input.body().keySet().stream()
          .anyMatch(
              Set.of("assigneeId", "reviewerId", "review", "reviewVersion", "lastEditorId")
                  ::contains)) throw new IllegalArgumentException("담당자·검토 정보는 전용 절차에서 변경하세요.");
      String verdict = String.valueOf(input.body().getOrDefault("verdict", "UNDETERMINED"));
      if (!Set.of("UNDETERMINED", "CONFIRMED", "REFUTED", "INSUFFICIENT").contains(verdict))
        throw new IllegalArgumentException("잘못된 조사 판정입니다.");
    }
    if (kind.equals("rule")
        && !Set.of("DRAFT", "OBSERVE", "ACTIVE", "PAUSED")
            .contains(String.valueOf(input.body().getOrDefault("state", "DRAFT"))))
      throw new IllegalArgumentException("잘못된 규칙 상태입니다.");
  }

  private Resource resource(ResultSet rs, int n) throws SQLException {
    return new Resource(
        rs.getString("id"),
        rs.getString("kind"),
        rs.getLong("owner_id"),
        rs.getBoolean("shared"),
        rs.getString("title"),
        decode(rs.getString("body_json")),
        rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static void kind(String kind) {
    if (!Set.of("saved-search", "investigation", "rule", "lookup", "indicator").contains(kind))
      throw new IllegalArgumentException("지원하지 않는 저장 종류입니다.");
  }

  public static void mutate(AuthUser user) {
    if (user.role() == Role.VIEWER) throw new ForbiddenException("조회 전용 사용자입니다.");
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("저장할 데이터를 확인하세요.");
    }
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("저장된 항목을 해석할 수 없습니다.");
    }
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }
}
