package org.castrelyx.manager.hunt.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Additive, versioned physical columns. The seven-column ingest contract stays intact. */
public final class HuntReadModel {
  private HuntReadModel() {}

  public static final String PREFIX = "hunt2_";

  public record Column(String name, String type, String expression, String fingerprint) {}

  public record Schema(
      boolean ready,
      List<String> missing,
      List<String> incompatible,
      Instant checkedAt,
      String error) {}

  public static String identity(String qualifier) {
    String q = qualifier.isEmpty() ? "" : qualifier + ".";
    return "lower(hex(SHA256(concat("
        + q
        + "asset_uid, '\\0', "
        + q
        + "source_type, '\\0', "
        + q
        + "source_id, '\\0',"
        + " coalesce(nullIf(JSONExtractString(event_json,'event_uid'),''),"
        + "nullIf(if("
        + q
        + "source_type='SECURITY_ROUTER',JSONExtractString(event_json,'event_id'),''),''),"
        + "nullIf(JSONExtractString(event_json,'dedup_key'),''),"
        + "concat(toString(observed_at),event_type,event_json))))))";
  }

  public static final String STORED_AT =
      "coalesce(parseDateTime64BestEffortOrNull(JSONExtractString(event_json,'stored_at')),observed_at)";

  public static List<Column> columns() {
    var result = new ArrayList<Column>();
    for (var field : HuntFields.all())
      if (field.expression().contains("event_json"))
        result.add(
            column(
                field.name(),
                field.type().equals("number") ? "Nullable(Float64)" : "String",
                field.expression()));
    result.add(column("id", "String", identity("")));
    result.add(column("stored_at", "DateTime64(3)", STORED_AT));
    return List.copyOf(result);
  }

  private static Column column(String name, String type, String expression) {
    try {
      String fingerprint =
          "castrelyx:hunt2:"
              + HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest((type + "\n" + expression).getBytes(StandardCharsets.UTF_8)));
      return new Column(PREFIX + name, type, expression, fingerprint);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Schema inspect(List<Map<String, Object>> metadata) {
    var present = new HashMap<String, Map<String, Object>>();
    metadata.forEach(row -> present.put(String.valueOf(row.get("name")), row));
    var missing = new ArrayList<String>();
    var incompatible = new ArrayList<String>();
    for (var column : columns()) {
      var row = present.get(column.name());
      if (row == null) missing.add(column.name());
      else if (!column.type().equals(row.get("type"))
          || !"MATERIALIZED".equals(row.get("default_kind"))
          || !column.fingerprint().equals(row.get("comment"))) incompatible.add(column.name());
    }
    return new Schema(
        missing.isEmpty() && incompatible.isEmpty(),
        List.copyOf(missing),
        List.copyOf(incompatible),
        Instant.now(),
        null);
  }

  public static String install(String database, Schema schema) {
    if (!schema.incompatible().isEmpty())
      throw new IllegalStateException("정규화 열의 정의가 다릅니다. 기존 열을 덮어쓰지 않고 마이그레이션 검토가 필요합니다.");
    var commands =
        columns().stream()
            .filter(c -> schema.missing().contains(c.name()))
            .map(
                c ->
                    "ADD COLUMN IF NOT EXISTS "
                        + c.name()
                        + " "
                        + c.type()
                        + " MATERIALIZED "
                        + c.expression()
                        + " COMMENT '"
                        + c.fingerprint()
                        + "'")
            .toList();
    return commands.isEmpty()
        ? ""
        : "ALTER TABLE " + table(database) + " " + String.join(",\n", commands);
  }

  public static String materialize(String database, String partition) {
    if (partition == null || !partition.matches("[0-9]{8}"))
      throw new IllegalArgumentException("백필할 일별 파티션을 선택하세요.");
    LocalDate.parse(partition, DateTimeFormatter.BASIC_ISO_DATE);
    return "ALTER TABLE "
        + table(database)
        + " "
        + columns().stream()
            .map(c -> "MATERIALIZE COLUMN " + c.name() + " IN PARTITION ID '" + partition + "'")
            .collect(java.util.stream.Collectors.joining(",\n"))
        + " SETTINGS mutations_sync=0";
  }

  public static String table(String database) {
    if (database == null || !database.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
      throw new IllegalArgumentException("Invalid database name");
    return "`" + database + "`.manager_events";
  }

  public static String projection() {
    return HuntFields.all().stream()
        .map(
            f ->
                (f.expression().contains("event_json")
                        ? "raw." + PREFIX + f.name()
                        : f.expression())
                    + " AS "
                    + f.name())
        .collect(java.util.stream.Collectors.joining(",\n"));
  }
}
