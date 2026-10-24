package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.junit.jupiter.api.Test;

class HuntReadModelOperationsTest {
  private final HuntStorage storage = mock(HuntStorage.class);
  private final HuntWorkspace workspace = mock(HuntWorkspace.class);
  private final HuntReadModelOperations operations =
      new HuntReadModelOperations(storage, workspace);
  private final AuthUser admin = new AuthUser(1, "admin", "admin", Role.ADMIN);
  private final HuntReadModel.Schema ready =
      new HuntReadModel.Schema(true, List.of(), List.of(), Instant.now(), null);

  @Test
  void nonAdministratorsCannotInspectOrMutateTheStorage() {
    for (var role : List.of(Role.OPERATOR, Role.VIEWER)) {
      var user = new AuthUser(2, "analyst", "analyst", role);
      assertThatThrownBy(() -> operations.status(user)).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> operations.install(user)).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> operations.backfill("20261022", user))
          .isInstanceOf(ForbiddenException.class);
    }
    verifyNoInteractions(storage, workspace);
  }

  @Test
  void rejectsMalformedPartitionBeforeContactingStorage() {
    when(storage.database()).thenReturn("fixture");
    for (var partition : List.of("20261022' DROP TABLE", "20261301", "all", "20261022Z"))
      assertThatThrownBy(() -> operations.backfill(partition, admin))
          .isInstanceOf(RuntimeException.class);
    verify(storage, never()).command(anyString());
    verify(storage, never()).read(any(), anyString());
    verifyNoInteractions(workspace);
  }

  @Test
  void doesNotQueueBackfillWhileAnotherMutationIsPending() {
    when(storage.database()).thenReturn("fixture");
    when(storage.readModel(true)).thenReturn(ready);
    when(storage.read(any(), anyString())).thenReturn(List.of(Map.of("pending", 1)));
    assertThatThrownBy(() -> operations.backfill("20261022", admin))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("진행 중");
    verify(storage, never()).command(anyString());
    verifyNoInteractions(workspace);
  }

  @Test
  void recordsSubmissionRatherThanClaimingMutationCompletion() {
    when(storage.database()).thenReturn("fixture");
    when(storage.readModel(true)).thenReturn(ready);
    when(storage.read(any(), anyString()))
        .thenAnswer(
            call -> {
              String sql = ((HuntSql.Statement) call.getArgument(0)).sql();
              if (sql.contains("AS pending")) return List.of(Map.of("pending", 0));
              if (sql.contains("system.parts"))
                return List.of(Map.of("partition", "20261022", "rows", 100));
              return List.of(Map.of("id", "mutation_1.txt", "is_done", 0, "parts_to_do", 2));
            });
    var status = operations.backfill("20261022", admin);
    assertThat(status.mutations().getFirst()).containsEntry("is_done", 0);
    verify(storage).command(contains("IN PARTITION ID '20261022'"));
    verify(workspace)
        .audit(eq(admin), eq("READ_MODEL_BACKFILL_SUBMITTED"), eq("storage"), anyMap());
    verify(workspace, never())
        .audit(any(), eq("READ_MODEL_BACKFILL_COMPLETED"), anyString(), anyMap());
  }
}
