package org.castrelyx.manager.hunt.v2;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The same typed query is used by exploration, saved searches and scenarios. */
public record HuntQuery(
    Instant from,
    Instant to,
    Filter filter,
    String text,
    String groupBy,
    String metric,
    int pageSize,
    Cursor cursor,
    List<String> groupFields,
    boolean includeRelations) {
  public record Filter(String op, String field, List<String> values, List<Filter> children) {}

  public record Cursor(Instant at, String id) {}

  public HuntQuery(
      Instant from,
      Instant to,
      Filter filter,
      String text,
      String groupBy,
      String metric,
      int pageSize,
      Cursor cursor) {
    this(from, to, filter, text, groupBy, metric, pageSize, cursor, null, false);
  }

  public HuntQuery {
    if (from == null
        || to == null
        || !from.isBefore(to)
        || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0)
      throw new IllegalArgumentException("조회 시작·종료 시각을 지정하세요. 최대 조회 범위는 30일입니다.");
    if (to.isAfter(Instant.now().plusSeconds(60)))
      throw new IllegalArgumentException("미래 기간은 조회할 수 없습니다.");
    text = text == null ? "" : text.trim();
    if (text.length() > 512) throw new IllegalArgumentException("검색어는 512자 이하여야 합니다.");
    groupBy = groupBy == null || groupBy.isBlank() ? "event_type" : groupBy;
    if (groupFields != null) {
      if (groupFields.isEmpty()
          || groupFields.size() > 3
          || groupFields.stream().anyMatch(f -> f == null || f.isBlank())
          || Set.copyOf(groupFields).size() != groupFields.size())
        throw new IllegalArgumentException("집계 필드는 중복 없이 1~3개를 선택하세요.");
      groupFields = List.copyOf(groupFields);
      groupBy = groupFields.getFirst();
    }
    metric = metric == null ? "count" : metric;
    if (!Set.of("count", "distinct_hosts", "bytes_sent", "bytes_received").contains(metric))
      throw new IllegalArgumentException("지원하지 않는 집계입니다.");
    pageSize = pageSize == 0 ? 200 : pageSize;
    if (pageSize < 1 || pageSize > 1000) throw new IllegalArgumentException("페이지 크기는 1~1000입니다.");
    if (cursor != null
        && (cursor.at() == null || cursor.id() == null || !cursor.id().matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException("잘못된 페이지 커서입니다.");
  }

  public HuntQuery withoutCursor() {
    return withCursor(null);
  }

  public HuntQuery withCursor(Cursor next) {
    return new HuntQuery(
        from, to, filter, text, groupBy, metric, pageSize, next, groupFields, includeRelations);
  }

  public List<String> dimensions() {
    return groupFields == null ? List.of(groupBy) : groupFields;
  }
}
