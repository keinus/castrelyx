package org.castrelyx.manager.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assets/{assetUid}/files")
public class AssetFileController {
  private static final long DEFAULT_TTL_SECONDS = 120;

  private final CastrelSignClient castrelSignClient;

  public AssetFileController(CastrelSignClient castrelSignClient) {
    this.castrelSignClient = castrelSignClient;
  }

  @PostMapping("/commands")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createCommand(@PathVariable String assetUid, @RequestBody FileCommandRequest request) {
    if (request == null || request.operation() == null || request.operation().isBlank()) {
      throw new IllegalArgumentException("operation is required");
    }
    return normalize(castrelSignClient.createAgentFileCommand(
        assetUid,
        request.operation(),
        request.request(),
        ttlSeconds(request.ttlSeconds())));
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Object upload(@PathVariable String assetUid,
      @RequestParam String path,
      @RequestParam(defaultValue = "true") boolean overwrite,
      @RequestParam(name = "ttl_seconds", required = false) Long ttlSeconds,
      @RequestPart MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file is required");
    }
    return normalize(castrelSignClient.createAgentFileUpload(
        assetUid,
        path,
        overwrite,
        file.getBytes(),
        file.getOriginalFilename(),
        file.getContentType(),
        ttlSeconds(ttlSeconds)));
  }

  @PostMapping("/download")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createDownload(@PathVariable String assetUid, @RequestBody DownloadRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    return normalize(castrelSignClient.createAgentFileDownload(assetUid, request.path(), ttlSeconds(request.ttlSeconds())));
  }

  @GetMapping("/commands/{commandId}")
  public Object getCommand(@PathVariable String commandId) {
    return normalize(castrelSignClient.getAgentFileCommand(commandId));
  }

  @GetMapping("/commands/{commandId}/download")
  public ResponseEntity<byte[]> download(@PathVariable String commandId) {
    ResponseEntity<byte[]> upstream = castrelSignClient.downloadAgentFileCommand(commandId);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(upstream.getStatusCode());
    MediaType contentType = upstream.getHeaders().getContentType();
    if (contentType != null) {
      response.contentType(contentType);
    }
    String disposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    if (disposition != null && !disposition.isBlank()) {
      response.header(HttpHeaders.CONTENT_DISPOSITION, disposition);
    }
    byte[] body = upstream.getBody() == null ? new byte[0] : upstream.getBody();
    return response.contentLength(body.length).body(body);
  }

  private static long ttlSeconds(Long ttlSeconds) {
    return ttlSeconds == null ? DEFAULT_TTL_SECONDS : Math.max(10, Math.min(ttlSeconds, 3600));
  }

  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        normalized.put(camelKey(String.valueOf(entry.getKey())), normalize(entry.getValue()));
      }
      return normalized;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(AssetFileController::normalize).toList();
    }
    return value;
  }

  private static String camelKey(String key) {
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '_') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(c));
        upperNext = false;
      } else {
        builder.append(c);
      }
    }
    return builder.toString();
  }

  public record FileCommandRequest(
      String operation,
      Map<String, Object> request,
      @JsonProperty("ttl_seconds") Long ttlSeconds) {
    public FileCommandRequest {
      if (request == null) {
        request = Map.of();
      }
    }
  }

  public record DownloadRequest(
      String path,
      @JsonProperty("ttl_seconds") Long ttlSeconds) {
  }
}
