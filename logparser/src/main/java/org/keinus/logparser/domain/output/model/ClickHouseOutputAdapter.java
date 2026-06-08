package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Slf4j
public class ClickHouseOutputAdapter extends OutputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final String DEFAULT_DATABASE = "default";
    private static final String DEFAULT_TABLE_NAME = "castrelyx_agent_events";

    private final ClickHouseConfig config;
    private final HttpClient httpClient;
    private final List<EventRecord> buffer = new ArrayList<>();
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService flushExecutor;

    public ClickHouseOutputAdapter(Map<String, String> obj) throws IOException {
        super(obj);
        this.config = ClickHouseConfig.from(obj, System::getenv);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(getTimeoutMs()))
                .build();
        if (config.autoCreateSchema()) {
            createSchema();
        }
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("ClickHouseOutputAdapterFlush-" + config.tableName());
            thread.setDaemon(true);
            return thread;
        });
        this.flushExecutor.scheduleWithFixedDelay(
                this::flushQuietly,
                config.flushIntervalMs(),
                config.flushIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void send(LogEvent logEvent) {
        if (closed.get()) {
            throw deliveryFailure("Adapter is closed");
        }
        synchronized (lock) {
            buffer.add(EventRecord.from(logEvent, isAddOriginText()));
            if (buffer.size() >= config.batchSize()) {
                flushLocked();
            }
        }
    }

    private void createSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                  received_at DateTime64(3) DEFAULT now64(3),
                  agent_id String,
                  tenant_id Nullable(String),
                  source_id String,
                  item_kind Nullable(String),
                  item_type Nullable(String),
                  item_key Nullable(String),
                  event_json String
                )
                ENGINE = MergeTree
                PARTITION BY toYYYYMM(received_at)
                ORDER BY (source_id, received_at)
                """.formatted(config.tableReference());
        postQuery(sql, "");
    }

    private void flushQuietly() {
        try {
            synchronized (lock) {
                flushLocked();
            }
        } catch (Exception e) {
            log.warn("ClickHouse output flush failed: {}", e.getMessage(), e);
        }
    }

    private void flushLocked() {
        if (buffer.isEmpty()) {
            return;
        }
        List<EventRecord> records = List.copyOf(buffer);
        StringBuilder body = new StringBuilder();
        for (EventRecord record : records) {
            body.append(record.toJsonEachRow()).append('\n');
        }
        postQuery(insertSql(), body.toString());
        buffer.clear();
    }

    private String insertSql() {
        return "INSERT INTO " + config.tableReference()
                + " (agent_id, tenant_id, source_id, item_kind, item_type, item_key, event_json)"
                + " FORMAT JSONEachRow";
    }

    private void postQuery(String query, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(queryUri(query))
                    .timeout(Duration.ofMillis(getTimeoutMs()))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            config.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw deliveryFailure("ClickHouse HTTP status " + response.statusCode() + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw deliveryFailure("Interrupted while sending ClickHouse request", e);
        } catch (IOException e) {
            throw deliveryFailure("Failed to send ClickHouse request", e);
        }
    }

    private URI queryUri(String query) {
        String separator = config.endpointUrl().contains("?") ? "&" : "?";
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return URI.create(config.endpointUrl() + separator + "query=" + encodedQuery);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        flushExecutor.shutdown();
        synchronized (lock) {
            flushLocked();
        }
    }

    record ClickHouseConfig(
            String endpointUrl,
            String database,
            String username,
            String password,
            String tableName,
            int batchSize,
            int flushIntervalMs,
            boolean autoCreateSchema
    ) {
        static ClickHouseConfig from(Map<String, String> rawConfig, Function<String, String> envLookup) throws IOException {
            String configParams = rawConfig.get("configParams");
            if (configParams == null || configParams.isBlank()) {
                throw new IOException("ClickHouseOutputAdapter requires configParams");
            }
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            String endpointUrl = normalizeEndpointUrl(requiredText(root, "endpointUrl"));
            String database = optionalText(root, "database", DEFAULT_DATABASE);
            String tableName = optionalText(root, "tableName", DEFAULT_TABLE_NAME);
            validateIdentifier(database, "database");
            validateIdentifier(tableName, "tableName");

            String username = resolveEnv(root, "usernameEnv", envLookup);
            String password = resolveEnv(root, "passwordEnv", envLookup);
            if ((username == null) != (password == null)) {
                throw new IOException("configParams.usernameEnv and configParams.passwordEnv must be provided together");
            }

            int batchSize = root.has("batchSize") ? root.get("batchSize").asInt() : DEFAULT_BATCH_SIZE;
            int flushIntervalMs = root.has("flushIntervalMs")
                    ? root.get("flushIntervalMs").asInt()
                    : DEFAULT_FLUSH_INTERVAL_MS;
            if (batchSize <= 0) {
                throw new IOException("batchSize must be greater than zero");
            }
            if (flushIntervalMs <= 0) {
                throw new IOException("flushIntervalMs must be greater than zero");
            }
            return new ClickHouseConfig(
                    endpointUrl,
                    database,
                    username,
                    password,
                    tableName,
                    batchSize,
                    flushIntervalMs,
                    root.has("autoCreateSchema") && root.get("autoCreateSchema").asBoolean()
            );
        }

        String tableReference() {
            return database + "." + tableName;
        }

        java.util.Optional<String> authorizationHeader() {
            if (username == null || password == null) {
                return java.util.Optional.empty();
            }
            String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return java.util.Optional.of("Basic " + token);
        }

        private static String requiredText(JsonNode root, String fieldName) throws IOException {
            String value = optionalText(root, fieldName, null);
            if (value == null || value.isBlank()) {
                throw new IOException("configParams." + fieldName + " is required");
            }
            return value;
        }

        private static String optionalText(JsonNode root, String fieldName, String defaultValue) {
            JsonNode value = root.get(fieldName);
            if (value == null || value.isNull()) {
                return defaultValue;
            }
            return value.asText();
        }

        private static String resolveEnv(JsonNode root, String fieldName, Function<String, String> envLookup) throws IOException {
            String envName = optionalText(root, fieldName, null);
            if (envName == null || envName.isBlank()) {
                return null;
            }
            String value = envLookup.apply(envName);
            if (value == null || value.isBlank()) {
                throw new IOException("Environment variable " + envName + " is required");
            }
            return value;
        }

        private static String normalizeEndpointUrl(String endpointUrl) throws IOException {
            URI uri;
            try {
                uri = URI.create(endpointUrl);
            } catch (IllegalArgumentException e) {
                throw new IOException("configParams.endpointUrl must be a valid URI", e);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IOException("configParams.endpointUrl must use http or https");
            }
            if (endpointUrl.endsWith("/")) {
                return endpointUrl.substring(0, endpointUrl.length() - 1);
            }
            return endpointUrl;
        }

        private static void validateIdentifier(String value, String fieldName) throws IOException {
            if (value == null || !value.matches("[A-Za-z0-9_]+")) {
                throw new IOException(fieldName + " must contain only letters, numbers, and underscore");
            }
        }
    }

    record EventRecord(
            String agentId,
            String tenantId,
            String sourceId,
            String itemKind,
            String itemType,
            String itemKey,
            String eventJson
    ) {
        static EventRecord from(LogEvent event, boolean includeOriginText) {
            Map<String, Object> output = event.toOutputMap(includeOriginText);
            String sourceId = firstNonBlank(asString(output.get("source_id")), event.getSourceHost(), "unknown");
            return new EventRecord(
                    sourceId,
                    asString(output.get("tenant_id")),
                    sourceId,
                    asString(output.get("item_kind")),
                    asString(output.get("item_type")),
                    asString(output.get("item_key")),
                    event.toOutputJson(includeOriginText)
            );
        }

        String toJsonEachRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent_id", agentId);
            row.put("tenant_id", tenantId);
            row.put("source_id", sourceId);
            row.put("item_kind", itemKind);
            row.put("item_type", itemType);
            row.put("item_key", itemKey);
            row.put("event_json", eventJson);
            try {
                return OBJECT_MAPPER.writeValueAsString(row);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize ClickHouse JSONEachRow payload", e);
            }
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String firstNonBlank(String first, String second, String fallback) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            if (second != null && !second.isBlank()) {
                return second;
            }
            return fallback;
        }
    }
}
