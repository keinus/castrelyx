package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class HuntSqlTest {
  private final Instant end = Instant.parse("2026-01-02T00:00:00Z");

  private HuntQuery query(HuntQuery.Filter filter) {
    return new HuntQuery(end.minusSeconds(3600), end, filter, "", "event_type", "count", 200, null);
  }

  @Test
  void multipleDimensionsAreWhitelistedAndCursorKeepsTheAnalysisContract() {
    var query =
        new HuntQuery(
            end.minusSeconds(60),
            end,
            null,
            "",
            "actor",
            "count",
            10,
            null,
            List.of("actor", "dst_port", "source_id"),
            true);
    var statement = new HuntSql("test").groups(query, end);
    assertThat(statement.sql())
        .contains(
            "GROUP BY value,dimension_1,dimension_2",
            "LIMIT 101",
            "uniqExact(asset_uid) AS hostCount",
            "AS bytesSent",
            "AS bytesReceived");
    assertThat(query.withCursor(new HuntQuery.Cursor(end, "a".repeat(64))).withoutCursor())
        .isEqualTo(query);
    for (var fields :
        List.of(
            List.of("actor", "actor"),
            List.of("actor", "source_id", "dst_ip", "dst_port"),
            List.<String>of()))
      assertThatThrownBy(
              () ->
                  new HuntQuery(
                      query.from(), end, null, "", "actor", "count", 10, null, fields, false))
          .isInstanceOf(IllegalArgumentException.class);
    var injected =
        new HuntQuery(
            query.from(),
            end,
            null,
            "",
            "actor",
            "count",
            10,
            null,
            List.of("actor", "x;DROP TABLE y"),
            false);
    assertThatThrownBy(() -> new HuntSql("test").groups(injected, end))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void filtersBeforePaginationAndAggregatesFullInterval() {
    var filter = new HuntQuery.Filter("eq", "actor", List.of("alice' OR 1=1 --"), null);
    var statement = new HuntSql("test").events(query(filter), end);
    assertThat(statement.sql())
        .doesNotContain("alice'", "10000")
        .contains("actor = {", "LIMIT 201", "LIMIT 1 BY");
    assertThat(statement.parameters()).containsValue("alice' OR 1=1 --");
    var summary = new HuntSql("test").summary(query(filter), end);
    assertThat(summary.sql()).contains("count() AS total").doesNotContain("LIMIT 201");
  }

  @Test
  void rejectsUnknownFieldsOperatorsAndInvalidNumericConditions() {
    for (var filter :
        List.of(
            new HuntQuery.Filter("eq", "actor;DROP TABLE x", List.of("x"), null),
            new HuntQuery.Filter("sql", "actor", List.of("x"), null),
            new HuntQuery.Filter("gt", "dst_port", List.of("NaN"), null),
            new HuntQuery.Filter("contains", "dst_port", List.of("443"), null),
            new HuntQuery.Filter("prefix", "dst_port", List.of("4"), null),
            new HuntQuery.Filter("cidr", "src_ip", List.of("10.0.0.0/8", "192.168.0.0/16"), null),
            new HuntQuery.Filter("cidr", "src_ip", List.of("192.168.1.0/99"), null),
            new HuntQuery.Filter("cidr", "src_ip", List.of("999.1.1.1/24"), null)))
      assertThatThrownBy(() -> new HuntSql("test").events(query(filter), end))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permitsIPv6CidrAndLogicalNegation() {
    var filter =
        new HuntQuery.Filter(
            "not",
            null,
            null,
            List.of(new HuntQuery.Filter("cidr", "src_ip", List.of("fd00::/8"), null)));
    var sql = new HuntSql("test").events(query(filter), end);
    assertThat(sql.sql()).contains("NOT (", "isIPAddressInRange");
    assertThat(sql.parameters()).containsValue("fd00::/8");
  }

  @Test
  void windowsEventCodeIsNotUsedAsAnEventIdentity() {
    String sql = new HuntSql("test").events(query(null), end).sql();
    assertThat(sql)
        .contains(
            "if(raw.source_type='SECURITY_ROUTER',JSONExtractString(event_json,'event_id'),'')",
            "dedup_key");
  }

  @Test
  void enforcesRangeAndCursorContracts() {
    assertThatThrownBy(() -> new HuntQuery(end, end, null, "", null, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new HuntQuery(end.minusSeconds(31 * 86400), end, null, "", null, null, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new HuntQuery(
                    end.minusSeconds(3600),
                    end,
                    null,
                    "",
                    null,
                    null,
                    0,
                    new HuntQuery.Cursor(end, "bad")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void excessiveNestedConditionsAreRejected() {
    var filter = new HuntQuery.Filter("eq", "actor", List.of("user"), null);
    for (int i = 0; i < 10; i++) filter = new HuntQuery.Filter("not", null, null, List.of(filter));
    var excessive = filter;
    assertThatThrownBy(() -> new HuntSql("test").events(query(excessive), end))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
