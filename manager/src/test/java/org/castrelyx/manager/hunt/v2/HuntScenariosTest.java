package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HuntScenariosTest {
  private final HuntScenarios catalogue = new HuntScenarios();
  private final Instant end = Instant.parse("2026-01-02T00:00:00Z");
  private final HuntQuery query =
      new HuntQuery(end.minusSeconds(3600), end, null, "", "event_type", "count", 200, null);
  private final Map<String, List<String>> lookups =
      Map.of(
          "trusted_scanners",
          List.of("10.0.0.1"),
          "internal_networks",
          List.of("10.0.0.0/8"),
          "approved_dns",
          List.of("10.0.0.53"),
          "approved_services",
          List.of("443"),
          "business_hours",
          List.of("9-18"),
          "trusted_processes",
          List.of("/usr/sbin/sshd"),
          "protected_paths",
          List.of("/data/"),
          "indicators",
          List.of("malicious.example"));

  @Test
  void allFortyEightPacksHaveExecutableConditionsAndInvestigationInstructions() {
    assertThat(catalogue.all())
        .hasSize(48)
        .extracting(HuntScenarios.Scenario::id)
        .doesNotHaveDuplicates();
    for (var scenario : catalogue.all()) {
      assertThat(scenario.steps()).hasSizeGreaterThanOrEqualTo(3);
      assertThat(scenario.falsePositives()).isNotEmpty();
      var statement = catalogue.compile(scenario, query, end, lookups, new HuntSql("test"));
      assertThat(statement.sql()).contains("FORMAT JSONEachRow").doesNotContain("placeholder");
      assertThat(statement.parameters()).containsValue(query.from().toString());
      scenario.requiredFields().forEach(HuntFields::require);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"A04", "A06", "B06", "E05", "F02", "F03", "F06", "G04", "G06"})
  void orderedScenariosRetainBothPrecedingAndFollowingEvents(String id) {
    var sql =
        catalogue.compile(catalogue.require(id), query, end, lookups, new HuntSql("test")).sql();
    assertThat(sql).contains("ASOF INNER JOIN", "FROM data", "next.at_ms>seed.at_ms");
  }

  @Test
  void missingOperationalConfigurationDoesNotBecomeZeroThreats() {
    assertThatThrownBy(
            () ->
                catalogue.compile(
                    catalogue.require("C06"), query, end, Map.of(), new HuntSql("test")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved_dns");
  }

  @Test
  void dnsBurstRequiresBothVolumeAndDiversity() {
    String sql =
        catalogue.compile(catalogue.require("C01"), query, end, lookups, new HuntSql("test")).sql();
    assertThat(sql)
        .contains(
            "uniqExact(domain)", "hits>=10 AND events>=20.0", "RANGE BETWEEN 300000 PRECEDING");
  }
}
