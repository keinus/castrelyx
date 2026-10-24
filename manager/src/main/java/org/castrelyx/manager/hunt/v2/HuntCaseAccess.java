package org.castrelyx.manager.hunt.v2;

import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Assignment grants case-scoped access; organizational sharing alone never grants editing. */
@Component
public class HuntCaseAccess {
  private final JdbcTemplate db;

  public HuntCaseAccess(JdbcTemplate db) {
    this.db = db;
  }

  public record Analyst(long id, String username, String displayName, Role role, boolean enabled) {}

  public record Assignment(Long assigneeId, Long reviewerId, long lastEditorId) {}

  public record AnalystPage(List<Analyst> items, boolean hasMore) {}

  void initialize(String id, long owner) {
    db.update(
        "insert into hunt_case_workflow(investigation_id,last_editor_id) values(?,?)", id, owner);
  }

  Assignment assignment(String id) {
    return db
        .query(
            "select * from hunt_case_workflow where investigation_id=?",
            (rs, n) ->
                new Assignment(
                    rs.getObject("assignee_id", Long.class),
                    rs.getObject("reviewer_id", Long.class),
                    rs.getLong("last_editor_id")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("조사 협업 정보를 찾을 수 없습니다."));
  }

  boolean active(AuthUser user) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "select count(*)>0 from users where id=? and enabled=true", Boolean.class, user.id()));
  }

  boolean member(String id, AuthUser user) {
    if (user.role() == Role.VIEWER || !active(user)) return false;
    var assignment = assignment(id);
    return Objects.equals(assignment.assigneeId(), user.id())
        || Objects.equals(assignment.reviewerId(), user.id());
  }

  void requireRead(HuntWorkspace.Resource resource, AuthUser user) {
    if (!active(user)
        || resource.ownerId() != user.id() && !resource.shared() && !member(resource.id(), user))
      throw new ForbiddenException("이 조사에 접근할 수 없습니다.");
  }

  boolean manage(HuntWorkspace.Resource resource, AuthUser user) {
    return user.role() != Role.VIEWER
        && active(user)
        && (resource.ownerId() == user.id() || user.role() == Role.ADMIN);
  }

  boolean edit(HuntWorkspace.Resource resource, AuthUser user) {
    return editable(resource)
        && user.role() != Role.VIEWER
        && active(user)
        && (manage(resource, user)
            || Objects.equals(assignment(resource.id()).assigneeId(), user.id()));
  }

  static boolean editable(HuntWorkspace.Resource resource) {
    return Set.of("DRAFT", "INVESTIGATING").contains(state(resource));
  }

  static String state(HuntWorkspace.Resource resource) {
    return String.valueOf(resource.body().getOrDefault("status", "DRAFT"));
  }

  void requireEdit(HuntWorkspace.Resource resource, AuthUser user) {
    requireRead(resource, user);
    if (!editable(resource)) throw new IllegalStateException("검토 중이거나 종료된 조사입니다. 분석을 재개한 뒤 변경하세요.");
    if (!edit(resource, user))
      throw new ForbiddenException("조사 소유자·담당자·접근 가능한 관리자만 분석을 변경할 수 있습니다.");
  }

  void edited(String id, long actor) {
    db.update("update hunt_case_workflow set last_editor_id=? where investigation_id=?", actor, id);
  }

  Analyst analyst(Long id) {
    if (id == null) return null;
    return db
        .query(
            "select id,username,display_name,role from users where id=? and enabled=true and role in ('ADMIN','OPERATOR')",
            (rs, n) ->
                new Analyst(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    Role.valueOf(rs.getString("role")),
                    true),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("활성 분석가 계정을 선택하세요."));
  }

  Analyst describe(Long id) {
    if (id == null) return null;
    return db
        .query(
            "select id,username,display_name,role,enabled from users where id=?",
            (rs, n) ->
                new Analyst(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    Role.valueOf(rs.getString("role")),
                    rs.getBoolean("enabled")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public AnalystPage analysts(String query, AuthUser user) {
    if (user.role() == Role.VIEWER || !active(user)) throw new ForbiddenException("분석가 권한이 필요합니다.");
    String term = query == null ? "" : query.trim();
    if (term.length() > 120) throw new IllegalArgumentException("분석가 검색어는 120자 이하여야 합니다.");
    var rows =
        db.query(
            "select id,username,display_name,role from users where enabled=true and role in ('ADMIN','OPERATOR') and (locate(lower(?),lower(username))>0 or locate(lower(?),lower(coalesce(display_name,'')))>0) order by username,id limit 101",
            (rs, n) ->
                new Analyst(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    Role.valueOf(rs.getString("role")),
                    true),
            term,
            term);
    return new AnalystPage(rows.stream().limit(100).toList(), rows.size() > 100);
  }
}
