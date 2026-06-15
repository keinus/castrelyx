package org.castrelyx.manager.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.alert.AlertEvaluationInput;
import org.castrelyx.manager.alert.AlertEvaluator;
import org.castrelyx.manager.alert.AlertSignal;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetType;
import org.castrelyx.manager.asset.SourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TelemetrySyncWorker {
  private static final String CURSOR_NAME = "clickhouse.raw.castrelyx_agent_events";
  private static final int BATCH_SIZE = 1000;
  private static final int MAX_BATCHES = 100;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ClickHouseClient clickHouseClient;
  private final TelemetryNormalizer telemetryNormalizer;
  private final AssetService assetService;
  private final JdbcTemplate jdbcTemplate;
  private final AlertEvaluator alertEvaluator;

  @Autowired
  public TelemetrySyncWorker(ClickHouseClient clickHouseClient, TelemetryNormalizer telemetryNormalizer,
      AssetService assetService, JdbcTemplate jdbcTemplate, AlertEvaluator alertEvaluator) {
    this.clickHouseClient = clickHouseClient;
    this.telemetryNormalizer = telemetryNormalizer;
    this.assetService = assetService;
    this.jdbcTemplate = jdbcTemplate;
    this.alertEvaluator = alertEvaluator;
  }

  TelemetrySyncWorker(ClickHouseClient clickHouseClient, TelemetryNormalizer telemetryNormalizer,
      AssetService assetService, JdbcTemplate jdbcTemplate) {
    this(clickHouseClient, telemetryNormalizer, assetService, jdbcTemplate, new AlertEvaluator());
  }

  public synchronized Map<String, Object> syncOnce() {
    clickHouseClient.ensureCanonicalTables();
    String cursor = cursorValue();
    int rawRowCount = 0;
    int canonicalRowCount = 0;
    ClickHouseClient.RawTelemetryRow lastRow = null;
    List<CanonicalTelemetryRecord> canonicalRows = new ArrayList<>();
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      List<ClickHouseClient.RawTelemetryRow> rawRows = clickHouseClient.fetchRawTelemetryRows(cursor, BATCH_SIZE);
      if (rawRows.isEmpty()) {
        break;
      }
      rawRowCount += rawRows.size();
      for (ClickHouseClient.RawTelemetryRow row : rawRows) {
        List<CanonicalTelemetryRecord> normalized = telemetryNormalizer.normalizeRawLogparserEvent(rawJson(row));
        for (CanonicalTelemetryRecord record : normalized) {
          upsertObservedAgent(record);
          evaluateAndPersistAlert(record);
        }
        canonicalRows.addAll(normalized);
      }
      lastRow = rawRows.getLast();
      cursor = cursorValue(lastRow);
      if (rawRows.size() < BATCH_SIZE) {
        break;
      }
    }
    canonicalRowCount = canonicalRows.size();
    clickHouseClient.insertCanonicalRecords(canonicalRows);
    if (lastRow != null) {
      updateCursor(lastRow);
    }
    return Map.of("synced", true, "rawRows", rawRowCount, "canonicalRows", canonicalRowCount);
  }

  public Map<String, Object> syncObservedAgents() {
    int count = 0;
    for (ClickHouseClient.ObservedAgentSource source : clickHouseClient.fetchObservedAgentSources()) {
      ObservedIdentity identity = observedIdentity(source);
      Asset asset = assetService.upsertObservedAsset(
          identity.assetUid(),
          identity.name(),
          identity.assetType(),
          identity.managementIp());
      bindSourceIfMissing(asset.id(), SourceType.AGENT, source.sourceId(), "raw-agent", 90);
      count++;
    }
    return Map.of("synced", true, "agents", count);
  }

  private void upsertObservedAgent(CanonicalTelemetryRecord record) {
    if (!"AGENT".equalsIgnoreCase(record.sourceType())) {
      return;
    }
    if (record.kind() == CanonicalTelemetryRecord.Kind.STATE && "identity".equals(record.stateType())) {
      upsertObservedIdentity(record);
      return;
    }
    Asset asset = touchObservedAgent(record.assetUid());
    bindSourceIfMissing(asset.id(), SourceType.AGENT, record.sourceId(), null, 80);
  }

  private Asset touchObservedAgent(String assetUid) {
    List<Long> existing = jdbcTemplate.query("select id from assets where asset_uid = ?",
        (rs, rowNum) -> rs.getLong("id"), assetUid);
    if (existing.isEmpty()) {
      return assetService.upsertObservedAsset(assetUid, assetUid, AssetType.UNKNOWN, null);
    }
    Instant now = Instant.now();
    jdbcTemplate.update("update assets set last_seen_at = ?, updated_at = ? where id = ?",
        Timestamp.from(now), Timestamp.from(now), existing.getFirst());
    return assetService.getAsset(existing.getFirst());
  }

  private void upsertObservedIdentity(CanonicalTelemetryRecord record) {
    try {
      JsonNode identity = OBJECT_MAPPER.readTree(record.stateJson());
      String assetUid = nonBlank(text(identity, "asset_uid"), record.assetUid());
      String name = nonBlank(text(identity, "hostname"), text(identity, "target_name"), assetUid);
      String managementIp = nonBlank(text(identity, "management_ip"), text(identity, "target_host"), null);
      Asset asset = assetService.upsertObservedAsset(assetUid, name, assetType(text(identity, "asset_type")), managementIp);
      bindSourceIfMissing(asset.id(), sourceType(record.sourceType()), record.sourceId(), record.stateKey(), confidence(record.sourceType()));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid identity state JSON", exception);
    }
  }

  private void evaluateAndPersistAlert(CanonicalTelemetryRecord record) {
    List<AlertSignal> signals = switch (record.kind()) {
      case METRIC -> alertEvaluator.evaluate(new AlertEvaluationInput(
          record.assetUid(),
          record.observedAt(),
          null,
          Map.of(record.metricName(), record.metricValue() == null ? 0.0 : record.metricValue()),
          Map.of(),
          List.of()));
      case EVENT -> alertEvaluator.evaluate(new AlertEvaluationInput(
          record.assetUid(),
          record.observedAt(),
          null,
          Map.of(),
          Map.of(),
          List.of(record.eventType())));
      case STATE -> List.of();
      case ASSET -> List.of();
    };
    for (AlertSignal signal : signals) {
      persistSignal(record.assetUid(), signal, record.observedAt());
    }
  }

  private void persistSignal(String assetUid, AlertSignal signal, Instant observedAt) {
    Long ruleId = jdbcTemplate.query("""
        select id from alert_rules
        where rule_type = ? and enabled = true
        order by id
        limit 1
        """, (rs, rowNum) -> rs.getLong("id"), signal.ruleType()).stream().findFirst().orElse(null);
    if (ruleId == null) {
      return;
    }
    Long assetId = jdbcTemplate.query("select id from assets where asset_uid = ?",
        (rs, rowNum) -> rs.getLong("id"), assetUid).stream().findFirst().orElse(null);
    List<Long> existing = jdbcTemplate.query("""
        select id
        from alert_instances
        where rule_id = ? and ((asset_id = ?) or (asset_id is null and ? is null)) and state_key = ? and status in ('ACTIVE', 'ACKNOWLEDGED')
        order by id
        limit 1
        """, (rs, rowNum) -> rs.getLong("id"), ruleId, assetId, assetId, signal.stateKey());
    if (existing.isEmpty()) {
      jdbcTemplate.update("""
          insert into alert_instances(rule_id, asset_id, severity, status, title, detail, state_key, first_seen_at, last_seen_at)
          values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
          """,
          ruleId,
          assetId,
          signal.severity().name(),
          signal.title(),
          signal.detail(),
          signal.stateKey(),
          Timestamp.from(observedAt),
          Timestamp.from(observedAt));
    } else {
      jdbcTemplate.update("""
          update alert_instances
          set severity = ?, title = ?, detail = ?, last_seen_at = ?
          where id = ?
          """,
          signal.severity().name(),
          signal.title(),
          signal.detail(),
          Timestamp.from(observedAt),
          existing.getFirst());
    }
  }

  private void bindSourceIfMissing(long assetId, SourceType sourceType, String sourceId, String sourceKey, int confidence) {
    List<Long> existing = jdbcTemplate.query("""
        select id
        from asset_source_bindings
        where source_type = ? and source_id = ? and ((source_key = ?) or (source_key is null and ? is null))
        """, (rs, rowNum) -> rs.getLong("id"), sourceType.name(), sourceId, sourceKey, sourceKey);
    if (existing.isEmpty()) {
      assetService.bindSource(assetId, sourceType, sourceId, sourceKey, confidence);
    } else {
      jdbcTemplate.update("update asset_source_bindings set asset_id = ?, confidence = ?, last_seen_at = ? where id = ?",
          assetId, confidence, Timestamp.from(Instant.now()), existing.getFirst());
    }
  }

  private String cursorValue() {
    return jdbcTemplate.query("select cursor_value from sync_cursors where name = ?",
        (rs, rowNum) -> rs.getString("cursor_value"), CURSOR_NAME).stream().findFirst().orElse(null);
  }

  private void updateCursor(ClickHouseClient.RawTelemetryRow row) {
    String value = cursorValue(row);
    Integer count = jdbcTemplate.queryForObject("select count(*) from sync_cursors where name = ?", Integer.class, CURSOR_NAME);
    if (count == null || count == 0) {
      jdbcTemplate.update("insert into sync_cursors(name, cursor_value, updated_at) values (?, ?, ?)",
          CURSOR_NAME, value, Timestamp.from(Instant.now()));
    } else {
      jdbcTemplate.update("update sync_cursors set cursor_value = ?, updated_at = ? where name = ?",
          value, Timestamp.from(Instant.now()), CURSOR_NAME);
    }
  }

  private ObservedIdentity observedIdentity(ClickHouseClient.ObservedAgentSource source) {
    String assetUid = source.sourceId();
    String name = source.sourceId();
    AssetType assetType = AssetType.UNKNOWN;
    String managementIp = null;
    if (source.identityJson() != null && !source.identityJson().isBlank()) {
      try {
        JsonNode root = OBJECT_MAPPER.readTree(source.identityJson());
        JsonNode payload = root.path("additionalAttributes").path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
          JsonNode rawLog = root.path("common").path("rawLog");
          if (rawLog.isTextual()) {
            payload = OBJECT_MAPPER.readTree(rawLog.asText()).path("payload");
          }
        }
        assetUid = nonBlank(text(payload, "asset_uid"), source.sourceId());
        name = nonBlank(text(payload, "hostname"), text(root, "source_host"), assetUid);
        managementIp = nonBlank(text(payload, "management_ip"), text(payload, "ip_address"), null);
        assetType = assetTypeFromIdentity(payload);
      } catch (JsonProcessingException ignored) {
        assetUid = source.sourceId();
        name = source.sourceId();
        assetType = AssetType.UNKNOWN;
        managementIp = null;
      }
    }
    return new ObservedIdentity(assetUid, name, assetType, managementIp);
  }

  private static AssetType assetTypeFromIdentity(JsonNode payload) {
    String explicit = text(payload, "asset_type");
    if (explicit != null && !explicit.isBlank()) {
      return assetType(explicit);
    }
    String os = text(payload, "os");
    if ("linux".equalsIgnoreCase(os)) {
      return AssetType.LINUX_SERVER;
    }
    if ("windows".equalsIgnoreCase(os)) {
      return AssetType.WINDOWS_SERVER;
    }
    return AssetType.UNKNOWN;
  }

  private static String cursorValue(ClickHouseClient.RawTelemetryRow row) {
    Map<String, Object> cursor = new LinkedHashMap<>();
    cursor.put("received_at", row.receivedAt().toString());
    cursor.put("source_id", row.sourceId());
    cursor.put("item_key", row.itemKey());
    try {
      return OBJECT_MAPPER.writeValueAsString(cursor);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("failed to serialize telemetry cursor", exception);
    }
  }

  private static String rawJson(ClickHouseClient.RawTelemetryRow row) {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("received_at", row.receivedAt().toString());
    root.put("source_id", row.sourceId());
    root.put("item_kind", row.itemKind());
    root.put("item_type", row.itemType());
    root.put("item_key", row.itemKey());
    try {
      root.set("event_json", OBJECT_MAPPER.readTree(row.eventJson() == null || row.eventJson().isBlank() ? "{}" : row.eventJson()));
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid raw event JSON", exception);
    }
  }

  private static AssetType assetType(String value) {
    if (value == null || value.isBlank()) {
      return AssetType.UNKNOWN;
    }
    try {
      return AssetType.valueOf(value);
    } catch (IllegalArgumentException ignored) {
      return AssetType.UNKNOWN;
    }
  }

  private static SourceType sourceType(String value) {
    return "SNMP".equalsIgnoreCase(value) ? SourceType.SNMP : SourceType.AGENT;
  }

  private static int confidence(String sourceType) {
    return "SNMP".equalsIgnoreCase(sourceType) ? 95 : 100;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String nonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  private static String nonBlank(String first, String second, String fallback) {
    String value = nonBlank(first, null);
    return value == null ? nonBlank(second, fallback) : value;
  }

  private record ObservedIdentity(String assetUid, String name, AssetType assetType, String managementIp) {
  }
}
