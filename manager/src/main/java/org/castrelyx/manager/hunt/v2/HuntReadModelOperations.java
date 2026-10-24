package org.castrelyx.manager.hunt.v2;

import java.util.*;
import org.castrelyx.manager.auth.*;
import org.springframework.stereotype.Service;

/** Explicit storage rollout. No mutation is submitted by a read or application startup. */
@Service
public class HuntReadModelOperations {
  private final HuntStorage storage;
  private final HuntWorkspace workspace;

  public HuntReadModelOperations(HuntStorage storage, HuntWorkspace workspace) {
    this.storage = storage;
    this.workspace = workspace;
  }

  public record Status(
      HuntReadModel.Schema schema,
      List<Map<String, Object>> partitions,
      List<Map<String, Object>> mutations,
      long pendingMutations,
      String queryMode) {}

  public synchronized Status status(AuthUser user) {
    admin(user);
    var schema = storage.readModel(true);
    var partitions = partitions();
    // Read the monotonic pending state before the detail snapshot. A just-finished mutation
    // may cause one extra poll, but cannot stop polling with stale unfinished detail rows.
    long pending = pendingMutationCount();
    return new Status(
        schema, partitions, mutations(), pending, storage.materialized() ? "PHYSICAL" : "JSON");
  }

  public synchronized Status install(AuthUser user) {
    admin(user);
    var schema = storage.readModel(true);
    if (schema.error() != null) throw new IllegalStateException(schema.error());
    String sql = HuntReadModel.install(storage.database(), schema);
    if (!sql.isEmpty()) {
      workspace.audit(
          user, "READ_MODEL_INSTALL_REQUESTED", "storage", Map.of("columns", schema.missing()));
      storage.command(sql);
      workspace.audit(user, "READ_MODEL_INSTALLED", "storage", Map.of("columns", schema.missing()));
    }
    return status(user);
  }

  public synchronized Status backfill(String partition, AuthUser user) {
    admin(user);
    String sql = HuntReadModel.materialize(storage.database(), partition); // validate before any IO
    if (!storage.readModel(true).ready()) throw new IllegalStateException("먼저 호환되는 정규화 열을 설치하세요.");
    // Do not enqueue overlapping heavy work, including pending mutations from another Manager.
    if (pendingMutationCount() > 0)
      throw new IllegalStateException("진행 중인 저장소 변경이 있습니다. 완료 상태를 확인한 뒤 실행하세요.");
    if (partitions().stream().noneMatch(p -> partition.equals(p.get("partition"))))
      throw new IllegalArgumentException("현재 저장소에 없는 파티션입니다.");
    workspace.audit(
        user, "READ_MODEL_BACKFILL_REQUESTED", "storage", Map.of("partition", partition));
    storage.command(sql);
    workspace.audit(
        user, "READ_MODEL_BACKFILL_SUBMITTED", "storage", Map.of("partition", partition));
    return status(user);
  }

  private List<Map<String, Object>> partitions() {
    return read(
        "SELECT partition_id AS partition,sum(rows) AS rows,sum(bytes_on_disk) AS bytes,"
            + "count() AS parts FROM system.parts WHERE database={db:String} AND table='manager_events'"
            + " AND active GROUP BY partition_id ORDER BY partition_id DESC LIMIT 366 FORMAT JSONEachRow");
  }

  private List<Map<String, Object>> mutations() {
    return read(
        "SELECT m.mutation_id AS id,arrayStringConcat(groupUniqArray(m.command),'\\n') AS command,"
            + "min(m.create_time) AS create_time,max(m.parts_to_do) AS parts_to_do,min(m.is_done) AS is_done,"
            + "arrayStringConcat(arrayFilter(x->x!='',groupUniqArray(m.latest_fail_reason)),'\\n') AS latest_fail_reason"
            + " FROM system.mutations AS m WHERE m.database={db:String} AND m.table='manager_events'"
            + " AND position(m.command,'MATERIALIZE COLUMN hunt2_')>0"
            + " GROUP BY m.mutation_id"
            + " ORDER BY create_time DESC,m.mutation_id DESC LIMIT 100 FORMAT JSONEachRow");
  }

  private long pendingMutationCount() {
    var rows =
        read(
            "SELECT uniqExact(mutation_id) AS pending FROM system.mutations WHERE database={db:String}"
                + " AND table='manager_events' AND NOT is_done FORMAT JSONEachRow");
    if (rows.size() != 1 || !(rows.getFirst().get("pending") instanceof Number count))
      throw new IllegalStateException("저장소 변경의 진행 상태를 확인하지 못했습니다.");
    return count.longValue();
  }

  private List<Map<String, Object>> read(String sql) {
    return storage.read(
        new HuntSql.Statement(sql, Map.of("db", storage.database())),
        "hunt-" + UUID.randomUUID() + "-storage");
  }

  private static void admin(AuthUser user) {
    if (user.role() != Role.ADMIN) throw new ForbiddenException("저장소 관리에는 관리자 권한이 필요합니다.");
  }
}
