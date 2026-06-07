package org.castrelyx.castrelsign.api;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.castrelyx.castrelsign.persistence.IngestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class IngestService {
  private final ObjectMapper objectMapper;
  private final IngestRepository ingestRepository;
  private final AgentRepository agentRepository;

  public IngestService(ObjectMapper objectMapper, IngestRepository ingestRepository, AgentRepository agentRepository) {
    this.objectMapper = objectMapper;
    this.ingestRepository = ingestRepository;
    this.agentRepository = agentRepository;
  }

  public long ingest(String certificateAgentId, String contentEncoding, byte[] requestBody) {
    if (contentEncoding == null || !"gzip".equalsIgnoreCase(contentEncoding.trim())) {
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Encoding must be gzip");
    }
    String rawJson = ungzip(requestBody);
    JsonNode batch = parseBatch(rawJson);
    String sourceId = text(batch, "source_id");
    if (sourceId == null || sourceId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_id is required");
    }
    if (!certificateAgentId.equals(sourceId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "source_id must match client certificate common name");
    }
    long batchId = ingestRepository.save(certificateAgentId, batch, rawJson);
    agentRepository.audit("INGEST_ACCEPTED", certificateAgentId, "stored ingest batch " + batchId);
    return batchId;
  }

  private String ungzip(byte[] body) {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to decode gzip body", e);
    }
  }

  private JsonNode parseBatch(String rawJson) {
    try {
      JsonNode batch = objectMapper.readTree(rawJson);
      if (!batch.isObject()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batch must be a JSON object");
      }
      JsonNode items = batch.get("items");
      if (items != null && !items.isArray()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items must be an array");
      }
      return batch;
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to parse batch JSON", e);
    }
  }

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.asText();
  }
}
