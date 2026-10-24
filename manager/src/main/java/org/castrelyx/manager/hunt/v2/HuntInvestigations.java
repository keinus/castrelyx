package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned peer review of a frozen case and its exact active evidence manifest. */
@Service
public class HuntInvestigations {
  private final HuntWorkspace workspace;
  private final HuntCaseAccess access;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public HuntInvestigations(
      HuntWorkspace workspace,
      HuntCaseAccess access,
      JdbcTemplate db,
      ObjectMapper json,
      Clock clock) {
    this.workspace = workspace;
    this.access = access;
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  public record Assign(long version, Long assigneeId, Long reviewerId) {}

  public record Submit(long version, String note) {}

  public record Decide(long version, long reviewId, String decision, String note) {}

  public record Review(
      long id,
      long caseVersion,
      long submittedBy,
      long reviewerId,
      String state,
      String requestNote,
      String decisionNote,
      String sha256,
      Instant createdAt,
      Instant decidedAt,
      Long decidedBy) {}

  public record ReviewPage(List<Review> items, Long nextBefore) {}

  public record Workflow(
      long version,
      HuntCaseAccess.Analyst assignee,
      HuntCaseAccess.Analyst reviewer,
      Map<String, Boolean> permissions,
      List<String> reviewReadiness,
      Review pendingReview,
      boolean evidenceChanged,
      ReviewPage history) {}

  public Workflow workflow(String id, AuthUser user) {
    var resource = requireCase(id, user);
    var assignment = access.assignment(id);
    var pending = pending(id);
    var readiness = readiness(resource, assignment, user);
    boolean decide = canDecide(resource, assignment, pending, user);
    boolean changed = pending != null && !sameEvidence(id, pending.id());
    return new Workflow(
        resource.version(),
        access.describe(assignment.assigneeId()),
        access.describe(assignment.reviewerId()),
        Map.of(
            "edit",
            access.edit(resource, user),
            "manage",
            access.manage(resource, user) && HuntCaseAccess.editable(resource),
            "requestReview",
            access.edit(resource, user) && readiness.isEmpty(),
            "decide",
            decide,
            "approve",
            decide && !changed,
            "reopen",
            access.manage(resource, user) && !HuntCaseAccess.editable(resource)),
        readiness,
        pending,
        changed,
        reviews(id, null, user));
  }

  @Transactional
  public HuntWorkspace.Resource assign(String id, Assign input, AuthUser user) {
    var resource = workspace.lockInvestigation(id, user);
    version(resource, input.version());
    if (!access.manage(resource, user))
      throw new ForbiddenException("담당자 지정은 조사 소유자·접근 가능한 관리자만 할 수 있습니다.");
    if (!HuntCaseAccess.editable(resource))
      throw new IllegalStateException("분석을 재개한 뒤 담당자를 변경하세요.");
    if (input.assigneeId() != null && Objects.equals(input.assigneeId(), input.reviewerId()))
      throw new IllegalArgumentException("담당자와 검토자는 서로 달라야 합니다.");
    access.analyst(input.assigneeId());
    access.analyst(input.reviewerId());
    var before = access.assignment(id);
    db.update(
        "update hunt_case_workflow set assignee_id=?,reviewer_id=? where investigation_id=?",
        input.assigneeId(),
        input.reviewerId(),
        id);
    var detail = new LinkedHashMap<String, Object>();
    detail.put("previousAssigneeId", before.assigneeId());
    detail.put("previousReviewerId", before.reviewerId());
    detail.put("assigneeId", input.assigneeId());
    detail.put("reviewerId", input.reviewerId());
    return workspace.writeCase(
        resource, resource.body(), user, "ASSIGN_INVESTIGATION", detail, false);
  }

  @Transactional
  public HuntWorkspace.Resource submit(String id, Submit input, AuthUser user) {
    var resource = workspace.lockInvestigation(id, user);
    version(resource, input.version());
    access.requireEdit(resource, user);
    String note = note(input.note());
    var assignment = access.assignment(id);
    var reasons = readiness(resource, assignment, user);
    if (!reasons.isEmpty()) throw new IllegalStateException(String.join(" ", reasons));
    if (pending(id) != null) throw new IllegalStateException("진행 중인 검토 요청이 있습니다.");
    var body = new LinkedHashMap<String, Object>(resource.body());
    body.put("status", "REVIEW");
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("version", resource.version() + 1);
    snapshot.put("title", resource.title());
    snapshot.put("ownerId", resource.ownerId());
    snapshot.put("shared", resource.shared());
    snapshot.put("body", body);
    snapshot.put("evidence", manifest(id));
    String encoded = encode(snapshot), hash = HuntWorkspace.hash(encoded);
    Instant now = Instant.now(clock);
    db.update(
        "insert into hunt_case_reviews(investigation_id,case_version,submitted_by,reviewer_id,state,request_note,snapshot_json,sha256,created_at) values(?,?,?,?,'REQUESTED',?,?,?,?)",
        id,
        resource.version() + 1,
        user.id(),
        assignment.reviewerId(),
        note,
        encoded,
        hash,
        Timestamp.from(now));
    var review = pending(id);
    return workspace.writeCase(
        resource,
        body,
        user,
        "REQUEST_CASE_REVIEW",
        Map.of(
            "reviewId",
            review.id(),
            "reviewerId",
            review.reviewerId(),
            "sha256",
            hash,
            "note",
            note),
        false);
  }

  @Transactional
  public HuntWorkspace.Resource decide(String id, Decide input, AuthUser user) {
    var resource = workspace.lockInvestigation(id, user);
    version(resource, input.version());
    var assignment = access.assignment(id);
    var pending = pending(id);
    if (!canDecide(resource, assignment, pending, user))
      throw new ForbiddenException("해당 버전의 독립 검토자만 판정할 수 있습니다.");
    if (pending.id() != input.reviewId() || pending.caseVersion() != resource.version())
      throw new IllegalStateException("검토 대상 버전이 바뀌었습니다. 최신 기록을 불러오세요.");
    if (!Set.of("APPROVED", "CHANGES_REQUESTED").contains(String.valueOf(input.decision())))
      throw new IllegalArgumentException("검토 결과를 선택하세요.");
    String note = note(input.note());
    if (input.decision().equals("APPROVED") && !sameEvidence(id, pending.id()))
      throw new IllegalStateException("제출된 근거가 만료되거나 변경됐습니다. 보완 요청 후 근거를 다시 확인하세요.");
    db.update(
        "update hunt_case_reviews set state=?,decision_note=?,decided_at=?,decided_by=? where id=? and state='REQUESTED'",
        input.decision(),
        note,
        Timestamp.from(Instant.now(clock)),
        user.id(),
        pending.id());
    var body = new LinkedHashMap<String, Object>(resource.body());
    body.put("status", input.decision().equals("APPROVED") ? "CLOSED" : "INVESTIGATING");
    return workspace.writeCase(
        resource,
        body,
        user,
        "DECIDE_CASE_REVIEW",
        Map.of(
            "reviewId",
            pending.id(),
            "decision",
            input.decision(),
            "note",
            note,
            "reviewedVersion",
            pending.caseVersion()),
        false);
  }

  @Transactional
  public HuntWorkspace.Resource reopen(String id, Submit input, AuthUser user) {
    var resource = workspace.lockInvestigation(id, user);
    version(resource, input.version());
    if (!access.manage(resource, user))
      throw new ForbiddenException("조사 소유자·접근 가능한 관리자만 분석을 재개할 수 있습니다.");
    if (HuntCaseAccess.editable(resource)) throw new IllegalStateException("이미 분석 중인 조사입니다.");
    String note = note(input.note());
    var review = pending(id);
    if (review != null)
      db.update(
          "update hunt_case_reviews set state='WITHDRAWN',decision_note=?,decided_at=?,decided_by=? where id=? and state='REQUESTED'",
          note,
          Timestamp.from(Instant.now(clock)),
          user.id(),
          review.id());
    var body = new LinkedHashMap<String, Object>(resource.body());
    body.put("status", "INVESTIGATING");
    return workspace.writeCase(
        resource,
        body,
        user,
        "REOPEN_INVESTIGATION",
        Map.of("note", note, "previousStatus", HuntCaseAccess.state(resource)),
        false);
  }

  public ReviewPage reviews(String id, Long before, AuthUser user) {
    requireCase(id, user);
    if (before != null && before <= 0) throw new IllegalArgumentException("검토 이력 커서를 확인하세요.");
    var rows =
        db.query(
            "select * from hunt_case_reviews where investigation_id=?"
                + (before == null ? "" : " and id<?")
                + " order by id desc limit 21",
            this::review,
            before == null ? new Object[] {id} : new Object[] {id, before});
    var items = rows.stream().limit(20).toList();
    return new ReviewPage(items, rows.size() > 20 ? items.getLast().id() : null);
  }

  public Map<String, Object> reviewDetail(String id, long reviewId, AuthUser user) {
    requireCase(id, user);
    return db
        .query(
            "select * from hunt_case_reviews where investigation_id=? and id=?",
            this::reviewSnapshot,
            id,
            reviewId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("검토 기록을 찾을 수 없습니다."));
  }

  Map<String, Object> reviewSnapshot(ResultSet rs, int n) throws SQLException {
    String snapshot = rs.getString("snapshot_json"), sha256 = rs.getString("sha256");
    HuntWorkspace.verifySnapshot(snapshot, sha256);
    return Map.of(
        "review",
        review(rs, n),
        "snapshot",
        decode(snapshot),
        "snapshotJson",
        snapshot,
        "sha256",
        sha256);
  }

  private HuntWorkspace.Resource requireCase(String id, AuthUser user) {
    var resource = workspace.get(id, user);
    if (!resource.kind().equals("investigation")) throw new IllegalArgumentException("조사를 선택하세요.");
    return resource;
  }

  private Review pending(String id) {
    return db
        .query(
            "select * from hunt_case_reviews where investigation_id=? and state='REQUESTED' order by id desc limit 1",
            this::review,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Review review(ResultSet rs, int row) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Review(
        rs.getLong("id"),
        rs.getLong("case_version"),
        rs.getLong("submitted_by"),
        rs.getLong("reviewer_id"),
        rs.getString("state"),
        rs.getString("request_note"),
        rs.getString("decision_note"),
        rs.getString("sha256"),
        rs.getTimestamp("created_at").toInstant(),
        decided == null ? null : decided.toInstant(),
        rs.getObject("decided_by", Long.class));
  }

  private boolean canDecide(
      HuntWorkspace.Resource resource,
      HuntCaseAccess.Assignment assignment,
      Review review,
      AuthUser user) {
    return review != null
        && HuntCaseAccess.state(resource).equals("REVIEW")
        && access.active(user)
        && user.role() != Role.VIEWER
        && review.reviewerId() == user.id()
        && review.submittedBy() != user.id()
        && assignment.lastEditorId() != user.id()
        && Objects.equals(assignment.reviewerId(), user.id());
  }

  private List<String> readiness(
      HuntWorkspace.Resource resource, HuntCaseAccess.Assignment assignment, AuthUser user) {
    var reasons = new ArrayList<String>();
    if (assignment.assigneeId() == null) reasons.add("조사 담당자를 지정하세요.");
    if (assignment.reviewerId() == null) reasons.add("독립 검토자를 지정하세요.");
    else {
      var reviewer = access.describe(assignment.reviewerId());
      if (reviewer == null || !reviewer.enabled() || reviewer.role() == Role.VIEWER)
        reasons.add("활성 분석가를 검토자로 지정하세요.");
      if (assignment.reviewerId() == user.id()
          || assignment.reviewerId() == assignment.lastEditorId())
        reasons.add("제출자·마지막 분석 작성자와 다른 검토자를 지정하세요.");
    }
    if (assignment.assigneeId() != null) {
      var assignee = access.describe(assignment.assigneeId());
      if (assignee == null || !assignee.enabled() || assignee.role() == Role.VIEWER)
        reasons.add("활성 조사 담당자를 지정하세요.");
    }
    if (String.valueOf(resource.body().getOrDefault("hypothesis", "")).isBlank())
      reasons.add("검증할 가설을 작성하세요.");
    if ("UNDETERMINED".equals(resource.body().getOrDefault("verdict", "UNDETERMINED")))
      reasons.add("가설 판정을 저장하세요.");
    if ("CONFIRMED".equals(resource.body().get("verdict"))
        && db.queryForObject(
                "select count(*) from hunt_evidence where investigation_id=? and expires_at>?",
                Long.class,
                resource.id(),
                Timestamp.from(Instant.now(clock)))
            == 0) reasons.add("확인 판정에는 고정 근거가 필요합니다.");
    return reasons;
  }

  private List<Map<String, Object>> manifest(String id) {
    var rows =
        db.query(
            "select id,event_id,sha256,expires_at,snapshot_json from hunt_evidence where investigation_id=? and expires_at>? order by id limit 1001",
            (rs, n) -> {
              HuntWorkspace.verifySnapshot(rs.getString("snapshot_json"), rs.getString("sha256"));
              return Map.<String, Object>of(
                  "id",
                  rs.getString("id"),
                  "eventId",
                  rs.getString("event_id"),
                  "sha256",
                  rs.getString("sha256"),
                  "expiresAt",
                  rs.getTimestamp("expires_at").toInstant().toString());
            },
            id,
            Timestamp.from(Instant.now(clock)));
    if (rows.size() > 1000) throw new IllegalStateException("검토 근거는 조사당 1000개까지입니다. 조사를 나눠 제출하세요.");
    return rows;
  }

  private boolean sameEvidence(String id, long reviewId) {
    String snapshot =
        db.queryForObject(
            "select snapshot_json from hunt_case_reviews where investigation_id=? and id=?",
            String.class,
            id,
            reviewId);
    try {
      return Objects.equals(decode(snapshot).get("evidence"), manifest(id));
    } catch (HuntWorkspace.IntegrityException corrupt) {
      return false;
    }
  }

  private static void version(HuntWorkspace.Resource resource, long version) {
    if (version != resource.version())
      throw new IllegalStateException("다른 사용자가 변경했습니다. 최신 기록을 불러오세요.");
  }

  private static String note(String value) {
    if (value == null || value.isBlank() || value.length() > 4000)
      throw new IllegalArgumentException("이유를 1~4000자로 작성하세요.");
    return value.trim();
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("검토 기록을 저장할 수 없습니다.");
    }
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("검토 기록을 읽을 수 없습니다.");
    }
  }
}
