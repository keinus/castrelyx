package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.castrelyx.manager.config.ManagerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated bounded read client; query IDs make cancellation reach ClickHouse. */
@Component
public class HuntStorage {
  private final ManagerProperties.ClickHouse config;
  private final ObjectMapper json;
  private volatile HuntReadModel.Schema schema;

  @Value("${manager.hunt.physical-read-enabled:true}")
  private boolean physicalReadEnabled = true;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public HuntStorage(ManagerProperties properties, ObjectMapper json) {
    this.config = properties.clickhouse();
    this.json = json;
  }

  public String database() {
    return config.database();
  }

  /** Metadata-only negotiation; an old or unavailable schema keeps the compatible JSON reader. */
  public boolean materialized() {
    return physicalReadEnabled && readModel(false).ready();
  }

  public synchronized HuntReadModel.Schema readModel(boolean refresh) {
    if (!refresh && schema != null && schema.checkedAt().isAfter(Instant.now().minusSeconds(60)))
      return schema;
    try {
      schema =
          HuntReadModel.inspect(
              read(
                  new HuntSql.Statement(
                      "SELECT name,type,default_kind,comment FROM system.columns WHERE database={db:String}"
                          + " AND table='manager_events' AND startsWith(name,'hunt2_') FORMAT JSONEachRow",
                      Map.of("db", database())),
                  "hunt-" + UUID.randomUUID() + "-schema"));
    } catch (IllegalStateException e) {
      schema =
          new HuntReadModel.Schema(
              false, List.of(), List.of(), Instant.now(), "저장소 스키마를 확인하지 못해 JSON 조회를 사용합니다.");
    }
    return schema;
  }

  void command(String sql) {
    send(
        sql, "wait_end_of_query=1&max_execution_time=60&max_memory_usage=1073741824&max_threads=2");
  }

  public List<Map<String, Object>> read(HuntSql.Statement statement, String queryId) {
    if (!queryId.matches("hunt-[a-f0-9-]{36}-[a-z]+"))
      throw new IllegalArgumentException("Invalid query identifier");
    StringBuilder query =
        new StringBuilder("query_id=")
            .append(queryId)
            .append(
                "&max_execution_time=60&timeout_overflow_mode=throw&max_memory_usage=1073741824&max_result_rows=100001&result_overflow_mode=throw&wait_end_of_query=1&readonly=1&max_threads=2&output_format_json_quote_64bit_integers=0");
    statement
        .parameters()
        .forEach(
            (key, value) -> query.append("&param_").append(key).append("=").append(encode(value)));
    String body = send(statement.sql(), query.toString());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String line : body.split("\n"))
      if (!line.isBlank()) {
        try {
          rows.add(json.readValue(line, new TypeReference<>() {}));
        } catch (Exception e) {
          throw new IllegalStateException("헌팅 저장소 응답을 해석할 수 없습니다.", e);
        }
      }
    return rows;
  }

  public void cancel(String id) {
    if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid job identifier");
    send("KILL QUERY WHERE startsWith(query_id, 'hunt-" + id + "-') ASYNC", "wait_end_of_query=1");
  }

  private String send(String sql, String query) {
    try {
      String endpoint = config.endpointUrl();
      if (endpoint.contains("?") || URI.create(endpoint).getUserInfo() != null)
        throw new IllegalStateException("Invalid ClickHouse endpoint");
      HttpRequest.Builder request =
          HttpRequest.newBuilder(
                  URI.create(endpoint + (endpoint.endsWith("/") ? "" : "/") + "?" + query))
              .timeout(Duration.ofSeconds(70))
              .header("Content-Type", "text/plain; charset=utf-8")
              .header(
                  "X-ClickHouse-User", config.username() == null ? "default" : config.username());
      if (config.password() != null && !config.password().isBlank())
        request.header("X-ClickHouse-Key", config.password());
      var future =
          http.sendAsync(
              request.POST(HttpRequest.BodyPublishers.ofString(sql)).build(),
              boundedBody(32 * 1024 * 1024));
      try {
        HttpResponse<byte[]> response = future.get(70, TimeUnit.SECONDS);
        byte[] bytes = response.body();
        if (response.statusCode() != 200)
          throw new IllegalStateException(
              "헌팅 저장소 조회 실패 (HTTP " + response.statusCode() + "). 조회 범위를 줄이거나 저장소 상태를 확인하세요.");
        return new String(bytes, StandardCharsets.UTF_8);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new IllegalStateException("헌팅 저장소 응답 시간이 초과되었습니다. 조회 범위를 줄이세요.");
      } catch (InterruptedException e) {
        future.cancel(true);
        throw e;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("조회가 취소되었습니다.");
    } catch (ExecutionException e) {
      throw new IllegalStateException("헌팅 저장소 응답을 완료할 수 없습니다. 연결 상태와 응답 크기를 확인하세요.");
    }
  }

  static HttpResponse.BodyHandler<byte[]> boundedBody(int limit) {
    return info ->
        new HttpResponse.BodySubscriber<>() {
          private final HttpResponse.BodySubscriber<byte[]> target =
              HttpResponse.BodySubscribers.ofByteArray();
          private Flow.Subscription subscription;
          private long bytes;
          private boolean failed;

          public CompletionStage<byte[]> getBody() {
            return target.getBody();
          }

          public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            target.onSubscribe(value);
          }

          public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) bytes += buffer.remaining();
            if (bytes > limit) {
              failed = true;
              subscription.cancel();
              target.onError(new java.io.IOException("Response exceeds configured limit"));
              return;
            }
            target.onNext(buffers);
          }

          public void onError(Throwable failure) {
            if (!failed) {
              failed = true;
              target.onError(failure);
            }
          }

          public void onComplete() {
            if (!failed) target.onComplete();
          }
        };
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
