package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.castrelyx.manager.auth.*;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Prepares one coherent, complete case export before committing an HTTP success. */
@Service
public class HuntExports {
  private final HuntWorkspace workspace;
  private final HuntInvestigations investigations;
  private final HuntCaseAccess access;
  private final HuntExportFiles files;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate snapshot;
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  private record Built(HuntExportFiles.Download file, long version) {}

  private record Pending(
      String caseId, long userId, Role role, Instant expiresAt, HuntExportFiles.Download file) {}

  public record Ticket(
      String url, String sha256, long length, long caseVersion, Instant expiresAt) {}

  public HuntExports(
      HuntWorkspace workspace,
      HuntInvestigations investigations,
      HuntCaseAccess access,
      HuntExportFiles files,
      JdbcTemplate db,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.workspace = workspace;
    this.investigations = investigations;
    this.access = access;
    this.files = files;
    this.db = db;
    this.json = json;
    this.clock = clock;
    snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setTimeout(60);
  }

  public HuntExportFiles.Download prepare(String id, AuthUser user) {
    return build(id, null, user).file();
  }

  public Ticket ticket(String id, long version, AuthUser user) {
    if (version < 1) throw new IllegalArgumentException("내보낼 조사 버전을 지정하세요.");
    var built = build(id, version, user);
    String key = UUID.randomUUID().toString();
    Instant expires = clock.instant().plusSeconds(900);
    pending.put(key, new Pending(id, user.id(), user.role(), expires, built.file()));
    return new Ticket(
        "/api/security/hunt/v2/exports/" + key,
        built.file().sha256(),
        built.file().length(),
        built.version(),
        expires);
  }

  public HuntExportFiles.Download consume(String key, AuthUser user) {
    UUID.fromString(key);
    var value = pending.get(key);
    if (value == null) throw new IllegalStateException("내보내기 링크가 만료되었거나 이미 사용됐습니다. 다시 준비하세요.");
    if (value.userId() != user.id()) throw new ForbiddenException("다른 사용자의 내보내기에 접근할 수 없습니다.");
    try {
      if (!value.expiresAt().isAfter(clock.instant()))
        throw new IllegalStateException("내보내기 링크가 만료되었습니다. 다시 준비하세요.");
      if (value.role() != user.role())
        throw new ForbiddenException("역할이 변경되었습니다. 현재 권한으로 다시 내보내세요.");
      workspace.get(value.caseId(), user);
    } catch (RuntimeException denied) {
      if (pending.remove(key, value)) value.file().close();
      throw denied;
    }
    if (!pending.remove(key, value))
      throw new IllegalStateException("이미 사용한 내보내기 링크입니다. 다시 준비하세요.");
    return value.file();
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void expireTickets() {
    Instant now = clock.instant();
    pending.forEach(
        (key, value) -> {
          if (!value.expiresAt().isAfter(now) && pending.remove(key, value)) value.file().close();
        });
  }

  @PreDestroy
  public void close() {
    pending.forEach(
        (key, value) -> {
          if (pending.remove(key, value)) value.file().close();
        });
  }

  private Built build(String id, Long version, AuthUser user) {
    var staged = files.begin();
    try {
      return snapshot.execute(
          status -> {
            var item = workspace.lockInvestigation(id, user);
            if (version != null && item.version() != version)
              throw new IllegalStateException("조사 버전이 변경되었습니다. 미저장 초안을 확인하고 최신 버전을 불러오세요.");
            Instant asOf = clock.instant();
            workspace.audit(
                user,
                "EXPORT_INVESTIGATION",
                id,
                Map.of(
                    "format",
                    "json",
                    "caseVersion",
                    item.version(),
                    "phase",
                    "PREPARED",
                    "role",
                    user.role().name()));
            try (var out = json.getFactory().createGenerator(staged.output())) {
              out.setCodec(json);
              out.writeStartObject();
              out.writeStringField("schemaVersion", "hunt-investigation-export-1.0");
              out.writeObjectField("investigation", workspace.get(id, user));
              out.writeObjectField("exportedAt", asOf);
              var assignment = access.assignment(id);
              var people = new LinkedHashMap<String, Object>();
              people.put("assignee", access.describe(assignment.assigneeId()));
              people.put("reviewer", access.describe(assignment.reviewerId()));
              people.put("lastEditorId", assignment.lastEditorId());
              out.writeObjectField("assignment", people);
              long evidence =
                  rows(
                      out,
                      "evidence",
                      "select * from hunt_evidence where investigation_id=? and expires_at>? order by created_at,id",
                      new Object[] {id, Timestamp.from(asOf)},
                      workspace::evidenceRow,
                      user);
              long reviews =
                  rows(
                      out,
                      "reviews",
                      "select * from hunt_case_reviews where investigation_id=? order by id",
                      new Object[] {id},
                      investigations::reviewSnapshot,
                      user);
              long audit =
                  rows(
                      out,
                      "audit",
                      "select id,actor_id,action,detail_json,created_at from hunt_audit where resource_id=? order by id",
                      new Object[] {id},
                      (rs, n) -> {
                        var value = new LinkedHashMap<String, Object>();
                        value.put("id", rs.getLong("id"));
                        value.put("actorId", rs.getLong("actor_id"));
                        value.put("action", rs.getString("action"));
                        try {
                          value.put(
                              "detail", json.readValue(rs.getString("detail_json"), Map.class));
                        } catch (IOException error) {
                          throw new IllegalStateException("감사 기록을 읽을 수 없습니다.", error);
                        }
                        value.put("at", rs.getTimestamp("created_at").toInstant());
                        return value;
                      },
                      user);
              out.writeObjectField(
                  "manifest",
                  Map.of(
                      "complete",
                      true,
                      "caseVersion",
                      item.version(),
                      "evidenceCount",
                      evidence,
                      "reviewCount",
                      reviews,
                      "auditCount",
                      audit,
                      "redacted",
                      user.role() != Role.ADMIN,
                      "snapshotHashInput",
                      "UTF-8 bytes of snapshotJson",
                      "evidenceScope",
                      "unexpired at exportedAt; all retained review and audit records"));
              out.writeEndObject();
            } catch (IOException error) {
              throw new IllegalStateException("내보내기를 완료하지 못했습니다: " + error.getMessage(), error);
            }
            try {
              return new Built(
                  staged.finish("investigation-" + UUID.fromString(id) + ".json"), item.version());
            } catch (IOException error) {
              throw new IllegalStateException("내보내기를 완료하지 못했습니다: " + error.getMessage(), error);
            }
          });
    } catch (RuntimeException error) {
      staged.close();
      throw error;
    }
  }

  private long rows(
      JsonGenerator out,
      String field,
      String sql,
      Object[] parameters,
      RowMapper<Map<String, Object>> mapper,
      AuthUser user)
      throws IOException {
    out.writeArrayFieldStart(field);
    long[] count = {0};
    db.query(
        connection -> {
          var statement =
              connection.prepareStatement(
                  sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
          statement.setFetchSize(100);
          for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
          return statement;
        },
        (RowCallbackHandler)
            rs -> {
              try {
                out.writeObject(HuntRedaction.apply(mapper.mapRow(rs, (int) count[0]), user));
                count[0]++;
              } catch (IOException error) {
                throw new IllegalStateException("내보내기를 완료하지 못했습니다: " + error.getMessage(), error);
              }
            });
    out.writeEndArray();
    return count[0];
  }
}
