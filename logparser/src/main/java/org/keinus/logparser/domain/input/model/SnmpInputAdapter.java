package org.keinus.logparser.domain.input.model;

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
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

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

    SnmpInputAdapter(InputAdapterConfig config, SnmpClient snmpClient) throws IOException {
        this(config, snmpClient, false);
    }

    private SnmpInputAdapter(InputAdapterConfig config, SnmpClient snmpClient, boolean ownsClient) throws IOException {
        super(config);
        this.collectorConfig = CollectorConfig.from(config);
        this.snmpClient = snmpClient != null ? snmpClient : new Snmp4jClient();
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
        payload.put("poll_status", "success");
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
        payload.put("poll_time", pollTime.toString());
        payload.put("target_name", target.name());
        payload.put("target_host", target.host());
        payload.put("target_port", target.port());
        payload.put("version", target.version());
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

        pollExecutor.shutdownNow();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SNMP poll executor did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        eventQueue.clear();
        if (ownsClient) {
            snmpClient.close();
        }
    }

    @FunctionalInterface
    interface SnmpClient {
        Map<String, Object> get(SnmpTarget target, List<SnmpOid> oids, int timeoutMs, int retries) throws IOException;

        default void close() throws IOException {
        }
    }

    public record SnmpTarget(String name, String host, int port, String community, String version) {
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
            JsonNode root = OBJECT_MAPPER.readTree(config.getConfigParams());
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
                targets.add(new SnmpTarget(
                        text(targetNode, "name", host),
                        host,
                        (int) number(targetNode, "port", DEFAULT_PORT),
                        text(targetNode, "community", defaultCommunity),
                        text(targetNode, "version", defaultVersion)
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
                    oids.add(new SnmpOid(oid, oid));
                    continue;
                }

                String oid = text(oidNode, "oid", null);
                if (oid == null || oid.isBlank()) {
                    throw new IllegalArgumentException("SNMP OID value is required");
                }
                oids.add(new SnmpOid(text(oidNode, "name", oid), oid));
            }
            return oids;
        }

        private static String text(JsonNode node, String fieldName, String defaultValue) {
            JsonNode value = node == null ? null : node.get(fieldName);
            if (value == null || value.isNull()) {
                return defaultValue;
            }
            String text = value.asText();
            return text == null || text.isBlank() ? defaultValue : text;
        }

        private static long number(JsonNode node, String fieldName, long defaultValue) {
            JsonNode value = node == null ? null : node.get(fieldName);
            if (value == null || !value.isNumber()) {
                return defaultValue;
            }
            return value.asLong();
        }
    }

    private static final class Snmp4jClient implements SnmpClient {
        private final DefaultUdpTransportMapping transport;
        private final Snmp snmp;

        private Snmp4jClient() throws IOException {
            this.transport = new DefaultUdpTransportMapping();
            this.snmp = new Snmp(transport);
            this.transport.listen();
        }

        @Override
        public Map<String, Object> get(SnmpTarget target, List<SnmpOid> oids, int timeoutMs, int retries) throws IOException {
            PDU pdu = new PDU();
            pdu.setType(PDU.GET);
            for (SnmpOid oid : oids) {
                pdu.add(new VariableBinding(new OID(oid.oid())));
            }

            CommunityTarget<UdpAddress> communityTarget = new CommunityTarget<>();
            communityTarget.setCommunity(new OctetString(target.community()));
            communityTarget.setAddress(new UdpAddress(target.host() + "/" + target.port()));
            communityTarget.setVersion(toSnmpVersion(target.version()));
            communityTarget.setTimeout(timeoutMs);
            communityTarget.setRetries(retries);

            ResponseEvent<UdpAddress> response = snmp.send(pdu, communityTarget);
            PDU responsePdu = response == null ? null : response.getResponse();
            if (responsePdu == null) {
                throw new IOException("No SNMP response from " + target.host());
            }
            if (responsePdu.getErrorStatus() != PDU.noError) {
                throw new IOException(responsePdu.getErrorStatusText());
            }

            Map<String, Object> metrics = new LinkedHashMap<>();
            for (int i = 0; i < responsePdu.size(); i++) {
                VariableBinding binding = responsePdu.get(i);
                SnmpOid requestedOid = oids.get(i);
                metrics.put(requestedOid.name(), binding.getVariable().toString());
            }
            return metrics;
        }

        private int toSnmpVersion(String version) throws IOException {
            String normalized = version == null ? "2c" : version.trim().toLowerCase();
            return switch (normalized) {
                case "1", "v1" -> SnmpConstants.version1;
                case "2", "2c", "v2c" -> SnmpConstants.version2c;
                default -> throw new IOException("Unsupported SNMP version: " + version);
            };
        }

        @Override
        public void close() throws IOException {
            snmp.close();
        }
    }
}
