package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HuntExceptionPolicyTest {
  private final HuntScenarios catalogue = new HuntScenarios();
  private final Instant now = Instant.parse("2020-01-02T00:00:00Z");
  private final String scopeKey = "a".repeat(64);

  Stream<String> scenarios() {
    return catalogue.all().stream().map(HuntScenarios.Scenario::id);
  }

  private Map<String, Object> candidate(HuntScenarios.Scenario definition) {
    var row = new LinkedHashMap<String, Object>();
    for (String key : definition.keys()) row.put(key, key.equals("dst_port") ? 443 : "qa-" + key);
    row.put("first_seen", now.minusSeconds(120).toString());
    row.put("last_seen", now.minusSeconds(60).toString());
    row.put("hits", 20);
    return row;
  }

  private HuntExceptionPolicy.Grant grant(
      HuntScenarios.Scenario definition, Map<String, Object> row) {
    return new HuntExceptionPolicy.Grant(
        UUID.randomUUID().toString(),
        2,
        definition.id(),
        definition.version(),
        scopeKey,
        HuntExceptionPolicy.identity(definition, row),
        now.minusSeconds(600),
        now.plusSeconds(600),
        2,
        now.minusSeconds(1),
        "Approved QA work",
        "Reviewed change QA-1",
        "b".repeat(64));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void allFortyEightScenarioIdentitiesSupportExactApprovedWorkWithoutDeletingTheOriginal(
      String id) {
    var definition = catalogue.require(id);
    var row = candidate(definition);
    var grant = grant(definition, row);
    var result = HuntExceptionPolicy.apply(definition, scopeKey, now, List.of(grant), List.of(row));
    assertThat(result.findings()).isEmpty();
    assertThat(result.suppressed()).hasSize(1);
    assertThat(result.suppressed().getFirst().finding()).isSameAs(row);
    assertThat(result.suppressed().getFirst().approvals()).containsExactly(grant);
    var different = new LinkedHashMap<>(row);
    different.put(definition.keys().getFirst(), "another-identity");
    assertThat(
            HuntExceptionPolicy.apply(definition, scopeKey, now, List.of(grant), List.of(different))
                .findings())
        .containsExactly(different);
    assertThat(
            HuntExceptionPolicy.apply(
                    definition, scopeKey, grant.expiresAt(), List.of(grant), List.of(row))
                .findings())
        .containsExactly(row);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-scope",
        "wrong-scenario",
        "wrong-version",
        "not-approved-yet",
        "before-valid",
        "at-expiry",
        "partial-episode-before",
        "partial-episode-after",
        "missing-time",
        "reverse-time",
        "missing-key"
      })
  void unsupportedOrOutOfScopeApprovalNeverSuppressesTheCandidate(String mode) {
    var definition = catalogue.require("A01");
    var row = candidate(definition);
    var grant = grant(definition, row);
    Instant at = now;
    String key = scopeKey;
    switch (mode) {
      case "wrong-scope" -> key = "c".repeat(64);
      case "wrong-scenario" -> definition = catalogue.require("A02");
      case "wrong-version" ->
          grant =
              new HuntExceptionPolicy.Grant(
                  grant.id(),
                  2,
                  grant.scenarioId(),
                  "obsolete",
                  scopeKey,
                  grant.scope(),
                  grant.validFrom(),
                  grant.expiresAt(),
                  2,
                  grant.approvedAt(),
                  grant.title(),
                  grant.reason(),
                  grant.evidenceSha256());
      case "not-approved-yet" -> at = grant.approvedAt().minusMillis(1);
      case "before-valid" -> at = grant.validFrom().minusMillis(1);
      case "at-expiry" -> at = grant.expiresAt();
      case "partial-episode-before" ->
          row.put("first_seen", grant.validFrom().minusMillis(1).toString());
      case "partial-episode-after" -> row.put("last_seen", grant.expiresAt().toString());
      case "missing-time" -> row.remove("first_seen");
      case "reverse-time" -> row.put("first_seen", now.toString());
      case "missing-key" -> row.remove("actor");
    }
    var result = HuntExceptionPolicy.apply(definition, key, at, List.of(grant), List.of(row));
    assertThat(result.findings()).containsExactly(row);
    assertThat(result.suppressed()).isEmpty();
  }

  @Test
  void capturedIdentityCannotBeChangedAfterApprovalAndNullIsNotAWildcard() {
    var definition = catalogue.require("A01");
    var row = candidate(definition);
    row.put("realm", null);
    var grant = grant(definition, row);
    assertThatThrownBy(() -> grant.scope().put("actor", "another"))
        .isInstanceOf(UnsupportedOperationException.class);
    var other = new LinkedHashMap<>(row);
    other.put("realm", "new-realm");
    assertThat(
            HuntExceptionPolicy.apply(definition, scopeKey, now, List.of(grant), List.of(other))
                .findings())
        .containsExactly(other);
  }

  @Test
  void sourceFiltersAliasesAndDefinitionBindTheApprovalButQueryWindowCanMoveWithinTheGrant() {
    var json = new ObjectMapper();
    var filter = new HuntQuery.Filter("eq", "source_id", List.of("sensor-a"), null);
    var first = new HuntQuery(now.minusSeconds(600), now, filter, "", "actor", "count", 200, null);
    var second =
        new HuntQuery(now.minusSeconds(300), now, filter, "", "event_type", "count", 50, null);
    String original =
        HuntExceptionPolicy.scopeKey(json, "A01", "v1", first, Map.of("sensor-a", "host-a"));
    assertThat(
            HuntExceptionPolicy.scopeKey(json, "A01", "v1", second, Map.of("sensor-a", "host-a")))
        .isEqualTo(original);
    assertThat(HuntExceptionPolicy.scopeKey(json, "A01", "v2", first, Map.of("sensor-a", "host-a")))
        .isNotEqualTo(original);
    assertThat(HuntExceptionPolicy.scopeKey(json, "A01", "v1", first, Map.of("sensor-a", "host-b")))
        .isNotEqualTo(original);
    var another =
        new HuntQuery(
            first.from(),
            first.to(),
            new HuntQuery.Filter("eq", "source_id", List.of("sensor-b"), null),
            "",
            "actor",
            "count",
            200,
            null);
    assertThat(
            HuntExceptionPolicy.scopeKey(json, "A01", "v1", another, Map.of("sensor-a", "host-a")))
        .isNotEqualTo(original);
  }
}
