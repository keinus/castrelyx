package org.castrelyx.castrelsign.persistence;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class IngestRepository {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public IngestRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public long save(String agentId, JsonNode batch, String rawJson) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into ingest_batches(agent_id, tenant_id, source, source_id, schema_version, observed_at, sent_at, received_at, raw_json)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, agentId);
      statement.setString(2, text(batch, "tenant_id"));
      statement.setString(3, text(batch, "source"));
      statement.setString(4, text(batch, "source_id"));
      statement.setString(5, text(batch, "schema_version"));
      statement.setString(6, text(batch, "observed_at"));
      statement.setString(7, text(batch, "sent_at"));
      statement.setString(8, Instant.now().toString());
      statement.setString(9, rawJson);
      return statement;
    }, keyHolder);
    long batchId = keyHolder.getKey().longValue();

    JsonNode items = batch.get("items");
    if (items != null && items.isArray()) {
      for (JsonNode item : items) {
        jdbcTemplate.update("""
            insert into ingest_items(batch_id, kind, type, item_key, payload_json)
            values (?, ?, ?, ?, ?)
            """,
            batchId,
            text(item, "kind"),
            text(item, "type"),
            text(item, "key"),
            payload(item));
      }
    }
    return batchId;
  }

  private String payload(JsonNode item) {
    JsonNode payload = item.get("payload");
    if (payload == null || payload.isNull()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to serialize item payload", e);
    }
  }

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node == null ? null : node.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText();
  }
}
