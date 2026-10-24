package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persist completion and exception-use audits together; cancellation never leaves an applied audit.
 */
@Service
public class HuntJobResults {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public HuntJobResults(
      JdbcTemplate db, ObjectMapper json, Clock clock, PlatformTransactionManager transactions) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
  }

  public void complete(String id, Object result) {
    String encoded = encode(result);
    tx.executeWithoutResult(
        status -> {
          var job =
              db.queryForMap(
                  "select owner_id,as_of from hunt_query_jobs where id=? for update", id);
          Instant now = Instant.now(clock);
          if (db.update(
                  "update hunt_query_jobs set state='COMPLETED',result_json=?,updated_at=? where id=? and state='RUNNING'",
                  encoded,
                  Timestamp.from(now),
                  id)
              != 1) return;
          Map<String, Object> data = json.convertValue(result, new TypeReference<>() {});
          if (!(data.get("scenario") instanceof Map<?, ?> scenario)
              || !(scenario.get("suppressed") instanceof List<?> suppressed)) return;
          for (Object item : suppressed) {
            if (!(item instanceof Map<?, ?> row)
                || !(row.get("approvals") instanceof List<?> approvals)) continue;
            for (Object approval : approvals) {
              if (!(approval instanceof Map<?, ?> grant))
                throw new IllegalStateException("예외 승인 사본 형식을 확인하세요.");
              String exceptionId = String.valueOf(grant.get("id"));
              if (!exceptionId.matches("[a-f0-9-]{36}"))
                throw new IllegalStateException("예외 승인 식별자를 확인하세요.");
              var detail = new LinkedHashMap<String, Object>();
              detail.put("jobId", id);
              detail.put("asOf", ((Timestamp) job.get("as_of")).toInstant().toString());
              detail.put("scenarioId", scenario.get("id"));
              detail.put("scenarioVersion", scenario.get("version"));
              detail.put("policyVersion", scenario.get("exceptionPolicyVersion"));
              detail.put("finding", row.get("finding"));
              detail.put("approvalVersion", grant.get("version"));
              detail.put("evidenceSha256", grant.get("evidenceSha256"));
              db.update(
                  "insert into hunt_audit(actor_id,action,resource_id,detail_json,created_at) values(?,'HUNT_EXCEPTION_APPLIED',?,?,?)",
                  job.get("owner_id"),
                  exceptionId,
                  encode(detail),
                  Timestamp.from(now));
            }
          }
        });
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("조회 결과와 예외 감사 기록을 저장할 수 없습니다.", e);
    }
  }
}
