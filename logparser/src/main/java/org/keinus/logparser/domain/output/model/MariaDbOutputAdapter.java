package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Slf4j
public class MariaDbOutputAdapter extends OutputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    private static final String DEFAULT_TABLE_NAME = "castrelyx_agent_events";

    private final MariaDbConfig config;
    private final List<EventRecord> buffer = new ArrayList<>();
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService flushExecutor;

    public MariaDbOutputAdapter(Map<String, String> obj) throws IOException {
        super(obj);
        this.config = MariaDbConfig.from(obj, System::getenv);
        if (config.autoCreateSchema()) {
            createSchema();
        }
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("MariaDbOutputAdapterFlush-" + config.tableName());
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
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists %s (
                      id bigint primary key auto_increment,
                      received_at timestamp not null default current_timestamp,
                      agent_id varchar(255) not null,
                      tenant_id varchar(255),
                      source_id varchar(255) not null,
                      item_kind varchar(100),
                      item_type varchar(255),
                      item_key varchar(500),
                      event_json json not null,
                      index idx_castrelyx_agent_events_source_id (source_id),
                      index idx_castrelyx_agent_events_item_type (item_type),
                      index idx_castrelyx_agent_events_received_at (received_at)
                    )
                    """.formatted(config.tableName()));
        } catch (SQLException e) {
            throw deliveryFailure("Failed to create MariaDB schema", e);
        }
    }

    private void flushQuietly() {
        try {
            synchronized (lock) {
                flushLocked();
            }
        } catch (Exception e) {
            log.warn("MariaDB output flush failed: {}", e.getMessage(), e);
        }
    }

    private void flushLocked() {
        if (buffer.isEmpty()) {
            return;
        }
        List<EventRecord> records = List.copyOf(buffer);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql())) {
            for (EventRecord record : records) {
                statement.setString(1, record.agentId());
                statement.setString(2, record.tenantId());
                statement.setString(3, record.sourceId());
                statement.setString(4, record.itemKind());
                statement.setString(5, record.itemType());
                statement.setString(6, record.itemKey());
                statement.setString(7, record.eventJson());
                statement.addBatch();
            }
            statement.executeBatch();
            buffer.clear();
        } catch (SQLException e) {
            throw deliveryFailure("Failed to insert MariaDB event rows", e);
        }
    }

    private String insertSql() {
        return "insert into " + config.tableName()
                + " (agent_id, tenant_id, source_id, item_kind, item_type, item_key, event_json)"
                + " values (?, ?, ?, ?, ?, ?, ?)";
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
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

    record MariaDbConfig(
            String jdbcUrl,
            String username,
            String password,
            String tableName,
            int batchSize,
            int flushIntervalMs,
            boolean autoCreateSchema
    ) {
        static MariaDbConfig from(Map<String, String> rawConfig, Function<String, String> envLookup) throws IOException {
            String configParams = rawConfig.get("configParams");
            if (configParams == null || configParams.isBlank()) {
                throw new IOException("MariaDbOutputAdapter requires configParams");
            }
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            String tableName = optionalText(root, "tableName", DEFAULT_TABLE_NAME);
            validateTableName(tableName);
            String usernameEnv = requiredText(root, "usernameEnv");
            String passwordEnv = requiredText(root, "passwordEnv");
            String username = envLookup.apply(usernameEnv);
            String password = envLookup.apply(passwordEnv);
            if (username == null || username.isBlank()) {
                throw new IOException("Environment variable " + usernameEnv + " is required");
            }
            if (password == null || password.isBlank()) {
                throw new IOException("Environment variable " + passwordEnv + " is required");
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
            return new MariaDbConfig(
                    requiredText(root, "jdbcUrl"),
                    username,
                    password,
                    tableName,
                    batchSize,
                    flushIntervalMs,
                    root.has("autoCreateSchema") && root.get("autoCreateSchema").asBoolean()
            );
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

        private static void validateTableName(String tableName) throws IOException {
            if (!tableName.matches("[A-Za-z0-9_]+")) {
                throw new IOException("tableName must contain only letters, numbers, and underscore");
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
            String sourceId = firstNonBlank(
                    asString(output.get("source_id")),
                    asString(additional(output).get("source_id")),
                    asString(common(output).get("srcHost")),
                    event.getSourceHost(),
                    "unknown"
            );
            return new EventRecord(
                    sourceId,
                    firstNonBlank(
                            asString(output.get("tenant_id")),
                            asString(additional(output).get("tenant_id"))
                    ),
                    sourceId,
                    firstNonBlank(
                            asString(output.get("item_kind")),
                            asString(additional(output).get("item_kind")),
                            asString(common(output).get("eventCategory"))
                    ),
                    firstNonBlank(
                            asString(output.get("item_type")),
                            asString(additional(output).get("item_type")),
                            asString(common(output).get("eventType"))
                    ),
                    firstNonBlank(
                            asString(output.get("item_key")),
                            asString(additional(output).get("item_key")),
                            asString(common(output).get("eventAction"))
                    ),
                    event.toOutputJson(includeOriginText)
            );
        }

        private static Map<?, ?> common(Map<String, Object> output) {
            return mapValue(output.get("common"));
        }

        private static Map<?, ?> additional(Map<String, Object> output) {
            return mapValue(output.get("additionalAttributes"));
        }

        private static Map<?, ?> mapValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            return Map.of();
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }
}
