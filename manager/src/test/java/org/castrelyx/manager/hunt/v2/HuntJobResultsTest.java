package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;

class HuntJobResultsTest {
  private EmbeddedDatabase database;
  private JdbcTemplate db;
  private HuntJobResults results;
  private final Instant now = Instant.parse("2020-01-02T00:00:00Z");
  private String job, grant;

  @BeforeEach
  void setup() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("job-results-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
            .build();
    db = new JdbcTemplate(database);
    db.execute(
        "create table hunt_query_jobs(id varchar(36) primary key,owner_id bigint,state varchar(30),as_of timestamp(3),result_json longtext,updated_at timestamp(3))");
    db.execute(
        "create table hunt_audit(id bigint primary key auto_increment,actor_id bigint,action varchar(60),resource_id varchar(100),detail_json longtext,created_at timestamp(3))");
    results =
        new HuntJobResults(
            db,
            new ObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
            new DataSourceTransactionManager(database));
    job = UUID.randomUUID().toString();
    grant = UUID.randomUUID().toString();
    db.update(
        "insert into hunt_query_jobs(id,owner_id,state,as_of) values(?,7,'RUNNING',?)",
        job,
        Timestamp.from(now));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  private Map<String, Object> result(String grantId) {
    return Map.of(
        "scenario",
        Map.of(
            "id",
            "A01",
            "version",
            "2.1.0",
            "exceptionPolicyVersion",
            "1.0.0",
            "findings",
            List.of(),
            "suppressed",
            List.of(
                Map.of(
                    "finding",
                    Map.of("actor", "alice", "hits", 5),
                    "approvals",
                    List.of(
                        Map.of("id", grantId, "version", 2, "evidenceSha256", "a".repeat(64)))))));
  }

  @Test
  void completionAndOneDurableAuditAreAtomicAndIdempotent() {
    results.complete(job, result(grant));
    results.complete(job, result(grant));
    assertThat(db.queryForObject("select state from hunt_query_jobs where id=?", String.class, job))
        .isEqualTo("COMPLETED");
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("select actor_id from hunt_audit", Long.class)).isEqualTo(7);
    assertThat(db.queryForObject("select resource_id from hunt_audit", String.class))
        .isEqualTo(grant);
    assertThat(db.queryForObject("select detail_json from hunt_audit", String.class))
        .contains(job, "alice", "approvalVersion");
  }

  @Test
  void cancelledJobsNeverClaimThatAnExceptionWasApplied() {
    db.update("update hunt_query_jobs set state='CANCELLED' where id=?", job);
    results.complete(job, result(grant));
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isZero();
    assertThat(
            db.queryForObject(
                "select result_json from hunt_query_jobs where id=?", String.class, job))
        .isNull();
  }

  @Test
  void failedAuditRollsBackTheSuccessfulResult() {
    assertThatThrownBy(() -> results.complete(job, result("invalid-grant")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(db.queryForObject("select state from hunt_query_jobs where id=?", String.class, job))
        .isEqualTo("RUNNING");
    assertThat(
            db.queryForObject(
                "select result_json from hunt_query_jobs where id=?", String.class, job))
        .isNull();
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isZero();
  }

  @Test
  void competingWorkersCannotDuplicateAnAppliedAudit() {
    var workers =
        java.util.stream.IntStream.range(0, 2)
            .mapToObj(
                index -> CompletableFuture.runAsync(() -> results.complete(job, result(grant))))
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(workers).join();
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isEqualTo(1);
  }

  @Test
  void ordinaryQueriesCompleteWithoutCreatingExceptionAudits() {
    results.complete(job, Map.of("summary", Map.of("total", 10)));
    assertThat(db.queryForObject("select state from hunt_query_jobs where id=?", String.class, job))
        .isEqualTo("COMPLETED");
    assertThat(db.queryForObject("select count(*) from hunt_audit", Integer.class)).isZero();
  }
}
