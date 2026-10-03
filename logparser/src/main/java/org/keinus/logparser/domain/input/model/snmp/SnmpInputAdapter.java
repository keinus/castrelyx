package org.keinus.logparser.domain.input.model.snmp;

import org.keinus.logparser.domain.input.model.InputAdapter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnmpInputAdapter extends InputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 161;
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_QUEUE_SIZE = 1000;
    private static final int DEFAULT_WORKER_THREADS = 1;
    private static final long DEFAULT_INTERVAL_MS = 60_000;

    private final CollectorConfig collectorConfig;
    private final SnmpClient snmpClient;
    private final boolean ownsClient;
    private final BlockingQueue<LogEvent> eventQueue;
    private final ExecutorService pollExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile long nextPollAtMs = 0;

    public SnmpInputAdapter(InputAdapterConfig config) throws IOException {
        this(config, null, true);
    }

    public static void validateConfig(InputAdapterConfig config) throws IOException {
        CollectorConfig.from(config);
    }

    SnmpInputAdapter(InputAdapterConfig config, SnmpClient snmpClient) throws IOException {
        this(config, snmpClient, false);
    }

    private SnmpInputAdapter(InputAdapterConfig config, SnmpClient snmpClient, boolean ownsClient) throws IOException {
        super(config);
        this.collectorConfig = CollectorConfig.from(config);
        this.snmpClient = snmpClient != null ? snmpClient : new SnmpPollClient(collectorConfig.targets());
        this.ownsClient = ownsClient;
        this.eventQueue = new LinkedBlockingQueue<>(collectorConfig.queueSize());
        this.pollExecutor = Executors.newFixedThreadPool(collectorConfig.workerThreads(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("SnmpPoller-" + getId() + "-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        log.info("SNMP Input Adapter initialized with {} targets, {} OIDs, interval={}ms",
                collectorConfig.targets().size(), collectorConfig.oids().size(), collectorConfig.intervalMs());
    }

    @Override
    public LogEvent run() {
        LogEvent queued = eventQueue.poll();
        if (queued != null || closed.get()) {
            return queued;
        }

        long now = System.currentTimeMillis();
        if (now < nextPollAtMs) {
            return null;
        }

        nextPollAtMs = now + collectorConfig.intervalMs();
        pollTargets();
        return eventQueue.poll();
    }

    private void pollTargets() {
        List<Callable<LogEvent>> tasks = collectorConfig.targets().stream()
                .map(target -> (Callable<LogEvent>) () -> pollTarget(target))
                .toList();

        try {
            List<Future<LogEvent>> futures = pollExecutor.invokeAll(tasks);
            for (Future<LogEvent> future : futures) {
                LogEvent event = future.get();
                if (!eventQueue.offer(event)) {
                    log.warn("SNMP event queue full, dropping event");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("SNMP polling interrupted");
        } catch (Exception e) {
            log.warn("SNMP polling failed: {}", e.getMessage(), e);
        }
    }

    private LogEvent pollTarget(SnmpTarget target) {
        Instant pollTime = Instant.now();
        try {
            Map<String, Object> metrics = snmpClient.get(
                    target,
                    collectorConfig.oids(),
                    collectorConfig.timeoutMs(),
                    collectorConfig.retries()
            );
            return createLogEvent(toJson(buildSuccessPayload(target, pollTime, metrics)), target.host());
        } catch (Exception e) {
            return createLogEvent(toJson(buildErrorPayload(target, pollTime, e.getMessage())), target.host());
        }
    }

    private Map<String, Object> buildSuccessPayload(SnmpTarget target, Instant pollTime, Map<String, Object> metrics) {
        Map<String, Object> payload = buildBasePayload(target, pollTime);
        long failures = metrics.values().stream().filter(value -> value instanceof Map<?, ?> metric
                && metric.containsKey("status") && !"success".equals(metric.get("status"))).count();
        payload.put("poll_status", failures == 0 ? "success" : failures == metrics.size() ? "error" : "partial");
        payload.put("metrics", metrics);
        return payload;
    }

    private Map<String, Object> buildErrorPayload(SnmpTarget target, Instant pollTime, String errorMessage) {
        Map<String, Object> payload = buildBasePayload(target, pollTime);
        payload.put("poll_status", "error");
        payload.put("error_message", errorMessage == null ? "SNMP polling failed" : errorMessage);
        return payload;
    }

    private Map<String, Object> buildBasePayload(SnmpTarget target, Instant pollTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol", "snmp");
        payload.put("operation", "poll");
        payload.put("observed_at", pollTime.toString());
        payload.put("poll_time", pollTime.toString());
        payload.put("target_name", target.name());
        payload.put("target_host", target.host());
        payload.put("target_port", target.port());
        payload.put("version", target.version());
        if (target.isVersion3()) {
            payload.put("security_level", target.securityLevel());
            payload.put("security_name", target.securityName());
        }
        return payload;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SNMP payload", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            if (ownsClient) snmpClient.close();
        } finally {
            pollExecutor.shutdownNow();
        }
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SNMP poll executor did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        eventQueue.clear();
    }

    @FunctionalInterface
    interface SnmpClient {
        Map<String, Object> get(SnmpTarget target, List<SnmpOid> oids, int timeoutMs, int retries) throws IOException;

        default void close() throws IOException {
        }
    }

    public record SnmpTarget(
            String name,
            String host,
            int port,
            String community,
            String version,
            String securityName,
            String securityLevel,
            String authProtocol,
            String authPassphrase,
            String authPassphraseEnv,
            String privProtocol,
            String privPassphrase,
            String privPassphraseEnv
    ) {
        boolean isVersion3() {
            String normalized = version == null ? "" : version.trim().toLowerCase();
            return "3".equals(normalized) || "v3".equals(normalized);
        }
    }

    public record SnmpOid(String name, String oid) {
    }

    private record CollectorConfig(
            List<SnmpTarget> targets,
            List<SnmpOid> oids,
            long intervalMs,
            int timeoutMs,
            int retries,
            int workerThreads,
            int queueSize
    ) {
        static CollectorConfig from(InputAdapterConfig config) throws IOException {
            if (config.getConfigParams() == null || config.getConfigParams().isBlank()) {
                throw new IllegalArgumentException("SnmpInputAdapter requires configParams");
            }
            JsonNode root = OBJECT_MAPPER.readTree(config.getConfigParams());
            if (root == null || !root.isObject()) throw new IllegalArgumentException("SNMP configParams must be an object");
            String defaultCommunity = text(root, "community", "public");
            String defaultVersion = text(root, "version", "2c");

            List<SnmpTarget> targets = readTargets(root, defaultCommunity, defaultVersion);
            List<SnmpOid> oids = readOids(root);
            long intervalMs = number(root, "intervalMs", DEFAULT_INTERVAL_MS);
            int timeoutMs = config.getTimeoutMs() != null ? config.getTimeoutMs() : (int) number(root, "timeoutMs", DEFAULT_TIMEOUT_MS);
            int retries = (int) number(root, "retries", 0);
            int workerThreads = config.getWorkerThreads() != null
                    ? config.getWorkerThreads()
                    : (int) number(root, "workerThreads", DEFAULT_WORKER_THREADS);
            int queueSize = config.getQueueSize() != null
                    ? config.getQueueSize()
                    : (int) number(root, "queueSize", DEFAULT_QUEUE_SIZE);

            return new CollectorConfig(
                    targets,
                    oids,
                    Math.max(1000, intervalMs),
                    Math.max(100, timeoutMs),
                    Math.max(0, retries),
                    Math.max(1, Math.min(workerThreads, Math.max(1, targets.size()))),
                    Math.max(1, queueSize)
            );
        }

        private static List<SnmpTarget> readTargets(JsonNode root, String defaultCommunity, String defaultVersion) {
            JsonNode targetNodes = root.get("targets");
            if (targetNodes == null || !targetNodes.isArray() || targetNodes.isEmpty()) {
                throw new IllegalArgumentException("SNMP configParams.targets must contain at least one target");
            }

            List<SnmpTarget> targets = new ArrayList<>();
            for (JsonNode targetNode : targetNodes) {
                String host = text(targetNode, "host", null);
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("SNMP target host is required");
                }
                int version = SnmpSecurity.version(text(targetNode, "version", defaultVersion));
                long port = number(targetNode, "port", DEFAULT_PORT);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("SNMP target port must be 1..65535");
                if (version == org.snmp4j.mp.SnmpConstants.version3) SnmpSecurity.validate(targetNode, root);
                targets.add(new SnmpTarget(
                        text(targetNode, "name", host),
                        host,
                        (int) number(targetNode, "port", DEFAULT_PORT),
                        text(targetNode, "community", defaultCommunity),
                        text(targetNode, "version", defaultVersion),
                        text(targetNode, "securityName", text(root, "securityName", null)),
                        text(targetNode, "securityLevel", text(root, "securityLevel", "authPriv")),
                        text(targetNode, "authProtocol", text(root, "authProtocol", "SHA256")),
                        readSecret(targetNode, root, "authPassphrase", "authPassphraseEnv"),
                        text(targetNode, "authPassphraseEnv", text(root, "authPassphraseEnv", null)),
                        text(targetNode, "privProtocol", text(root, "privProtocol", "AES128")),
                        readSecret(targetNode, root, "privPassphrase", "privPassphraseEnv"),
                        text(targetNode, "privPassphraseEnv", text(root, "privPassphraseEnv", null))
                ));
            }
            return targets;
        }

        private static List<SnmpOid> readOids(JsonNode root) {
            JsonNode oidNodes = root.get("oids");
            if (oidNodes == null || !oidNodes.isArray() || oidNodes.isEmpty()) {
                throw new IllegalArgumentException("SNMP configParams.oids must contain at least one OID");
            }

            List<SnmpOid> oids = new ArrayList<>();
            for (JsonNode oidNode : oidNodes) {
                if (oidNode.isTextual()) {
                    String oid = oidNode.asText();
                    validateOid(oid);
                    oids.add(new SnmpOid(oid, oid));
                    continue;
                }

                String oid = text(oidNode, "oid", null);
                if (oid == null || oid.isBlank()) {
                    throw new IllegalArgumentException("SNMP OID value is required");
                }
                validateOid(oid);
                oids.add(new SnmpOid(text(oidNode, "name", oid), oid));
            }
            if (oids.stream().map(SnmpOid::name).distinct().count() != oids.size()) {
                throw new IllegalArgumentException("SNMP OID names must be unique");
            }
            return oids;
        }

        private static void validateOid(String value) {
            if (!value.matches("\\.?[0-9]+(?:\\.[0-9]+)+") || !new org.snmp4j.smi.OID(value).isValid()) {
                throw new IllegalArgumentException("SNMP OID must be a valid numeric OID: " + value);
            }
        }

        private static String text(JsonNode node, String fieldName, String defaultValue) {
            JsonNode value = node == null ? null : node.get(fieldName);
            if (value == null || value.isNull()) {
                return defaultValue;
            }
            String text = value.asText();
            return text == null || text.isBlank() ? defaultValue : text;
        }

        private static String readSecret(JsonNode targetNode, JsonNode root, String directField, String envField) {
            String direct = text(targetNode, directField, null);
            String envName = text(targetNode, envField, null);
            if (direct == null && envName == null) {
                direct = text(root, directField, null);
                envName = text(root, envField, null);
            }
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            if (envName == null || envName.isBlank()) {
                return null;
            }
            String envValue = System.getenv(envName);
            return envValue == null || envValue.isBlank() ? null : envValue;
        }

        private static long number(JsonNode node, String fieldName, long defaultValue) {
            JsonNode value = node == null ? null : node.get(fieldName);
            if (value == null || !value.isNumber()) {
                return defaultValue;
            }
            return value.asLong();
        }
    }

}
