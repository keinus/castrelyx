package org.castrelyx.manager.hunt.v2;

import java.time.Instant;
import java.util.*;

/** Parameterized ClickHouse queries over the complete selected interval. */
public final class HuntSql {
  public record Statement(String sql, Map<String, String> parameters) {}

  private final Map<String, String> parameters = new LinkedHashMap<>();
  private int nodes;
  private final String table;
  private final Map<String, String> aliases;
  private final boolean physical;

  public HuntSql(String database) {
    this(database, Map.of());
  }

  public HuntSql(String database, Map<String, String> aliases) {
    this(database, aliases, false);
  }

  public HuntSql(String database, Map<String, String> aliases, boolean physical) {
    table = HuntReadModel.table(database);
    this.aliases = Map.copyOf(aliases);
    this.physical = physical;
  }

  public String parameter(String value, String type) {
    String key = "p" + parameters.size();
    parameters.put(key, value);
    return "{" + key + ":" + type + "}";
  }

  public Statement statement(String sql) {
    return new Statement(sql, Map.copyOf(parameters));
  }

  public String source(HuntQuery query, Instant asOf) {
    String start = parameter(query.from().toString(), "String"),
        end = parameter(query.to().toString(), "String");
    String cutoff = parameter(asOf.toString(), "String");
    // event_json remains available for exact source evidence. Deduplication precedes every
    // aggregate.
    String projection = physical ? HuntReadModel.projection() : HuntFields.projection();
    if (!aliases.isEmpty()) {
      var keys = aliases.keySet().stream().sorted().toList();
      String from =
          keys.stream()
              .map(k -> parameter(k, "String"))
              .collect(java.util.stream.Collectors.joining(","));
      String to =
          keys.stream()
              .map(k -> parameter(aliases.get(k), "String"))
              .collect(java.util.stream.Collectors.joining(","));
      projection =
          projection.replace(
              "asset_uid AS asset_uid",
              "transform(raw.asset_uid,[" + from + "],[" + to + "],raw.asset_uid) AS asset_uid");
    }
    return "(SELECT * FROM (SELECT observed_at, event_json, raw.asset_uid AS observed_asset_uid, "
        + projection
        + ", "
        + (physical ? "raw.hunt2_id" : HuntReadModel.identity("raw"))
        + " AS id FROM "
        + table
        + " AS raw WHERE observed_at >= parseDateTime64BestEffort("
        + start
        + ")"
        + " AND observed_at < parseDateTime64BestEffort("
        + end
        + ") AND"
        + " "
        + (physical ? "raw.hunt2_stored_at" : HuntReadModel.STORED_AT)
        + " <= parseDateTime64BestEffort("
        + cutoff
        + ")"
        + " ORDER BY observed_at DESC LIMIT 1 BY asset_uid,source_type,source_id,id))";
  }

  public String predicate(HuntQuery.Filter filter) {
    return predicate(filter, 0);
  }

  private String predicate(HuntQuery.Filter filter, int depth) {
    if (filter == null) return "1";
    if (++nodes > 100 || depth > 8) throw new IllegalArgumentException("검색 조건이 너무 복잡합니다.");
    String op = filter.op() == null ? "eq" : filter.op();
    if (Set.of("and", "or", "not").contains(op)) {
      var children = filter.children();
      if (children == null
          || children.isEmpty()
          || children.size() > 30
          || op.equals("not") && children.size() != 1)
        throw new IllegalArgumentException("잘못된 논리 조건입니다.");
      String joined =
          children.stream()
              .map(child -> "(" + predicate(child, depth + 1) + ")")
              .collect(java.util.stream.Collectors.joining(op.equals("or") ? " OR " : " AND "));
      return op.equals("not") ? "NOT (" + joined + ")" : joined;
    }
    var field = HuntFields.require(filter.field());
    String name = field.name();
    if (op.equals("exists"))
      return field.type().equals("number") ? "isNotNull(" + name + ")" : name + " != ''";
    List<String> values = filter.values();
    if (values == null
        || values.isEmpty()
        || values.size() > 100
        || values.stream().anyMatch(v -> v == null || v.length() > 1024))
      throw new IllegalArgumentException("검색 조건 값을 확인하세요.");
    if (op.equals("cidr")) {
      if (!field.type().equals("ip")) throw new IllegalArgumentException("CIDR 조건은 IP 필드에만 적용됩니다.");
      if (values.size() != 1) throw new IllegalArgumentException("CIDR 조건에는 대역 하나가 필요합니다.");
      validateCidr(values.getFirst());
      return addressInRange(name, parameter(values.getFirst(), "String"));
    }
    if (Set.of("in", "not_in").contains(op)) {
      return name
          + (op.equals("in") ? " IN (" : " NOT IN (")
          + values.stream()
              .map(value -> value(field, value))
              .collect(java.util.stream.Collectors.joining(","))
          + ")";
    }
    if (values.size() != 1) throw new IllegalArgumentException("단일 조건에는 값 하나가 필요합니다.");
    if (Set.of("contains", "prefix").contains(op) && field.type().equals("number"))
      throw new IllegalArgumentException("문자열 비교는 숫자 필드에 적용할 수 없습니다.");
    String value = value(field, values.getFirst());
    return switch (op) {
      case "eq" -> name + " = " + value;
      case "neq" -> name + " != " + value;
      case "gt", "gte", "lt", "lte" -> {
        if (!field.type().equals("number"))
          throw new IllegalArgumentException("크기 비교는 숫자 필드에만 적용됩니다.");
        yield name + Map.of("gt", ">", "gte", ">=", "lt", "<", "lte", "<=").get(op) + value;
      }
      case "contains" -> "positionCaseInsensitiveUTF8(toString(" + name + "), " + value + ") > 0";
      case "prefix" -> "startsWith(toString(" + name + "), " + value + ")";
      default -> throw new IllegalArgumentException("지원하지 않는 조건: " + op);
    };
  }

  private String value(HuntFields.Field field, String value) {
    if (field.type().equals("number")) {
      try {
        if (!Double.isFinite(Double.parseDouble(value))) throw new NumberFormatException();
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("숫자 조건 값을 확인하세요.");
      }
      return parameter(value, "Float64");
    }
    return parameter(value, "String");
  }

  static String addressInRange(String name, String boundRange) {
    String valid = "(isIPv4String(" + name + ") OR isIPv6String(" + name + "))";
    // An AND guard alone does not guarantee short-circuit evaluation in ClickHouse.
    return "("
        + valid
        + " AND isIPAddressInRange(if("
        + valid
        + ","
        + name
        + ",'0.0.0.0'), "
        + boundRange
        + "))";
  }

  public String where(HuntQuery query) {
    String result = predicate(query.filter());
    if (!query.text().isBlank())
      result +=
          " AND positionCaseInsensitiveUTF8(concat(event_json,' ',asset_uid,' ',event_type),"
              + parameter(query.text(), "String")
              + ") > 0";
    return "(" + result + ")";
  }

  public Statement events(HuntQuery query, Instant asOf) {
    String source = source(query, asOf), where = where(query);
    if (query.cursor() != null)
      where +=
          " AND (observed_at,id) < (parseDateTime64BestEffort("
              + parameter(query.cursor().at().toString(), "String")
              + "),"
              + parameter(query.cursor().id(), "String")
              + ")";
    return statement(
        "SELECT *, formatDateTime(observed_at,'%Y-%m-%dT%H:%i:%S.%fZ','UTC') AS at FROM "
            + source
            + " WHERE "
            + where
            + " ORDER BY observed_at DESC,id DESC LIMIT "
            + (query.pageSize() + 1)
            + " FORMAT JSONEachRow");
  }

  public Statement summary(HuntQuery query, Instant asOf) {
    String source = source(query, asOf), where = where(query);
    return statement(
        "SELECT count() AS total, uniqExact(asset_uid) AS assets, min(observed_at) AS oldest,"
            + " max(observed_at) AS newest,countIf(src_ip!='') AS addresses, countIf(actor!='') AS"
            + " accounts FROM "
            + source
            + " WHERE "
            + where
            + " FORMAT JSONEachRow");
  }

  public Statement histogram(HuntQuery query, Instant asOf) {
    long minutes =
        Math.max(1, java.time.Duration.between(query.from(), query.to()).toMinutes() / 90);
    return statement(
        "SELECT toUnixTimestamp(toStartOfInterval(observed_at,INTERVAL "
            + minutes
            + " MINUTE)) AS time, count() AS count FROM "
            + source(query, asOf)
            + " WHERE "
            + where(query)
            + " GROUP BY time ORDER BY time FORMAT JSONEachRow");
  }

  public Statement groups(HuntQuery query, Instant asOf) {
    var dimensions = query.dimensions();
    var columns = new ArrayList<String>();
    var aliases = new ArrayList<String>();
    for (int i = 0; i < dimensions.size(); i++) {
      String alias = i == 0 ? "value" : "dimension_" + i;
      columns.add("toString(" + HuntFields.require(dimensions.get(i)).name() + ") AS " + alias);
      aliases.add(alias);
    }
    String metric =
        switch (query.metric()) {
          case "distinct_hosts" -> "uniqExact(asset_uid)";
          case "bytes_sent" -> "sum(bytes_sent)";
          case "bytes_received" -> "sum(bytes_received)";
          default -> "count()";
        };
    return statement(
        "SELECT "
            + String.join(",", columns)
            + ", count() AS count, uniqExact(asset_uid) AS hostCount,"
            + " sum(ifNull(bytes_sent,0)) AS bytesSent, sum(ifNull(bytes_received,0)) AS bytesReceived,"
            + metric
            + " AS metric FROM "
            + source(query, asOf)
            + " WHERE "
            + where(query)
            + " GROUP BY "
            + String.join(",", aliases)
            + " ORDER BY metric DESC,"
            + String.join(",", aliases)
            + " LIMIT 101 FORMAT JSONEachRow");
  }

  public Statement relations(HuntQuery query, Instant asOf) {
    return statement(
        "SELECT id, formatDateTime(observed_at,'%Y-%m-%dT%H:%i:%S.%fZ','UTC') AS at, "
            + String.join(",", HuntRelations.FIELDS)
            + " FROM "
            + source(query, asOf)
            + " WHERE "
            + where(query)
            + " ORDER BY observed_at,id LIMIT "
            + (HuntRelations.INPUT_LIMIT + 1)
            + " FORMAT JSONEachRow");
  }

  public Statement event(HuntQuery query, Instant asOf, String id) {
    if (id == null || !id.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("잘못된 이벤트 ID입니다.");
    return statement(
        "SELECT *, formatDateTime(observed_at,'%Y-%m-%dT%H:%i:%S.%fZ','UTC') AS at FROM "
            + source(query, asOf)
            + " WHERE "
            + where(query)
            + " AND id="
            + parameter(id, "String")
            + " LIMIT 1 FORMAT JSONEachRow");
  }

  private static void validateCidr(String cidr) {
    if (!cidr.matches("[0-9a-fA-F:.]+/[0-9]{1,3}"))
      throw new IllegalArgumentException("잘못된 CIDR입니다.");
    String[] parts = cidr.split("/");
    int mask = Integer.parseInt(parts[1]);
    if (mask > (parts[0].contains(":") ? 128 : 32))
      throw new IllegalArgumentException("잘못된 CIDR입니다.");
    if (!parts[0].contains(":")) {
      String[] octets = parts[0].split("\\.", -1);
      if (octets.length != 4
          || Arrays.stream(octets)
              .anyMatch(s -> s.isEmpty() || s.length() > 3 || Integer.parseInt(s) > 255))
        throw new IllegalArgumentException("잘못된 CIDR입니다.");
    } else
      try {
        if (!(java.net.InetAddress.getByName(parts[0]) instanceof java.net.Inet6Address))
          throw new Exception();
      } catch (Exception e) {
        throw new IllegalArgumentException("잘못된 CIDR입니다.");
      }
  }
}
