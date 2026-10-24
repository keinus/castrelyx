package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class HuntScenarioRunnerTest {
  @Test
  void anApprovedSnapshotWithAnObsoletePolicyVersionCannotBeReinterpreted() {
    var definition = new HuntScenarios().require("A01");
    var approval =
        new HuntExceptionPolicy.Grant(
            UUID.randomUUID().toString(),
            2,
            definition.id(),
            definition.version(),
            "a".repeat(64),
            Map.of("actor", "alice"),
            now.minusSeconds(300),
            now.plusSeconds(300),
            2,
            now.minusSeconds(1),
            "QA",
            "QA",
            "b".repeat(64));
    assertThatThrownBy(
            () ->
                runner.run(
                    "A01",
                    query,
                    now,
                    "qa",
                    Map.of(),
                    new HuntScenarioRunner.Context(
                        definition.version(),
                        Map.of(),
                        "a".repeat(64),
                        List.of(approval),
                        "obsolete")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("예외 정책 버전");
    verifyNoInteractions(storage);
  }

  @Test
  void legacyContextsWithoutExceptionFieldsRemainReadable() throws Exception {
    var context =
        new ObjectMapper()
            .readValue(
                "{\"scenarioVersion\":\""
                    + new HuntScenarios().require("A05").version()
                    + "\",\"lookups\":{}}",
                HuntScenarioRunner.Context.class);
    assertThat(context.exceptions()).isEmpty();
    when(storage.database()).thenReturn("test");
    when(storage.read(any(), endsWith("readiness")))
        .thenReturn(List.of(Map.of("total", 0, "has_src_ip", 0, "has_actor", 0)));
    assertThat(runner.run("A05", query, now, "qa", Map.of(), context))
        .containsEntry("readiness", "UNAVAILABLE");
  }

  private final HuntStorage storage = mock(HuntStorage.class);
  private final HuntScenarioRunner runner =
      new HuntScenarioRunner(
          new HuntScenarios(), storage, mock(JdbcTemplate.class), new ObjectMapper());
  private final Instant now = Instant.now();
  private final HuntQuery query =
      new HuntQuery(now.minusSeconds(600), now, null, "", "actor", "count", 200, null);

  @ParameterizedTest
  @ValueSource(strings = {"2.0.0", "2.1.0", "2.1.1"})
  void capturedDefinitionsAreNotSilentlyReinterpretedAfterUpgrade(String version) {
    assertThatThrownBy(
            () ->
                runner.run(
                    "A05",
                    query,
                    now,
                    "test",
                    Map.of(),
                    new HuntScenarioRunner.Context(version, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("시나리오 버전");
    verifyNoInteractions(storage);
  }

  @Test
  void historicalDataCannotMakeAnEmptySelectedIntervalReady() {
    when(storage.database()).thenReturn("test");
    when(storage.read(any(), endsWith("readiness")))
        .thenReturn(List.of(Map.of("total", 0, "has_src_ip", 0, "has_actor", 0)));
    var result =
        runner.run(
            "A05",
            query,
            now,
            "test",
            Map.of(),
            new HuntScenarioRunner.Context(new HuntScenarios().require("A05").version(), Map.of()));
    assertThat(result).containsEntry("readiness", "UNAVAILABLE").containsEntry("complete", false);
    verify(storage, never()).read(any(), endsWith("scenario"));
  }

  @Test
  void baselineNeedsFourteenDaysAndExecutionUsesTheCapturedLookupValues() {
    when(storage.database()).thenReturn("test");
    when(storage.read(any(), endsWith("readiness")))
        .thenReturn(List.of(Map.of("total", 5, "has_src_ip", 5, "has_actor", 5)));
    when(storage.read(any(), endsWith("baseline"))).thenReturn(List.of(Map.of("days", 1)));
    assertThat(
            runner.run(
                "A05",
                query,
                now,
                "test",
                Map.of(),
                new HuntScenarioRunner.Context(
                    new HuntScenarios().require("A05").version(), Map.of())))
        .containsEntry("readiness", "WARMING_UP");
    when(storage.read(any(), endsWith("readiness")))
        .thenReturn(List.of(Map.of("total", 5, "has_src_ip", 5, "has_dst_ip", 5)));
    when(storage.read(any(), endsWith("scenario")))
        .thenAnswer(
            invocation -> {
              HuntSql.Statement sql = invocation.getArgument(0);
              assertThat(sql.parameters()).containsValue("10.0.0.53");
              return List.of();
            });
    assertThat(
            runner.run(
                "C06",
                query,
                now,
                "test",
                Map.of(),
                new HuntScenarioRunner.Context(
                    new HuntScenarios().require("C06").version(),
                    Map.of("approved_dns", List.of("10.0.0.53")))))
        .containsEntry("readiness", "READY")
        .containsEntry("complete", true);
  }
}
