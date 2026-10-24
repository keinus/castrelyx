package org.castrelyx.manager.hunt.v2;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relational artifacts have explicit expiry; pinned snapshots do not depend on an expiring job row.
 */
@Component
public class HuntRetention {
  private final JdbcTemplate db;
  private final Clock clock;

  public HuntRetention(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  @Transactional
  public void expire() {
    var now = Timestamp.from(Instant.now(clock));
    db.update(
        "update hunt_query_jobs set state='FAILED',error_message='조회 대기 시간이 만료되었습니다.',updated_at=?"
            + " where state='QUEUED' and expires_at<=?",
        now,
        now);
    db.update(
        "delete from hunt_query_jobs where expires_at<=? and state not in ('QUEUED','RUNNING')",
        now);
    db.update("delete from hunt_evidence where expires_at<=?", now);
    db.update(
        "delete from hunt_audit where created_at<=?",
        Timestamp.from(Instant.now(clock).minusSeconds(365 * 86400L)));
  }
}
