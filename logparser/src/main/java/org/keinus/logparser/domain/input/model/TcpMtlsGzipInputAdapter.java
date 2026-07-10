package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

@Slf4j
public class TcpMtlsGzipInputAdapter extends InputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_FRAME_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_MAX_BATCH_ITEMS = 5_000;
    private static final int DEFAULT_QUEUE_SIZE = 10_000;
    private static final int DEFAULT_MAX_CONNECTIONS = 32;
    private static final int DEFAULT_TLS_RELOAD_INTERVAL_MS = 5_000;
    private static final int MAX_BATCH_ID_LENGTH = 128;
    private static final int MAX_ITEM_ID_LENGTH = 256;
    private static final int RECENT_BATCH_LIMIT = 50_000;
    private static final long CONNECTION_LIMIT_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final AdapterConfig adapterConfig;
    private final BlockingQueue<LogEvent> eventQueue;
    private final ExecutorService clientExecutor;
    private final Semaphore connectionPermits;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong listenerGeneration = new AtomicLong(1);
    private final AtomicLong lastConnectionLimitWarningNanos = new AtomicLong();
    private final Object enqueueLock = new Object();
    private final Object listenerLock = new Object();
    private final LinkedHashMap<String, Boolean> recentBatches = new LinkedHashMap<>();
    private volatile SSLServerSocket serverSocket;
    private volatile SSLContext activeSslContext;
    private volatile String activeStoreFingerprint;
    private volatile long nextTlsReloadCheckNanos;

    public TcpMtlsGzipInputAdapter(InputAdapterConfig config) throws IOException {
        this(config, System::getenv);
    }

    TcpMtlsGzipInputAdapter(InputAdapterConfig config, Function<String, String> envLookup) throws IOException {
        super(config);
        this.adapterConfig = AdapterConfig.from(config, envLookup);
        int queueSize = config.getQueueSize() == null ? DEFAULT_QUEUE_SIZE : config.getQueueSize();
        if (queueSize <= 0) {
            throw new IOException("queueSize must be greater than zero");
        }
        this.eventQueue = new LinkedBlockingQueue<>(queueSize);
        this.connectionPermits = new Semaphore(adapterConfig.maxConnections, true);
        this.clientExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("TcpMtlsGzipClient-" + adapterConfig.port + "-", 0)
                .factory());
        LoadedTlsMaterial initialMaterial = loadTlsMaterial(adapterConfig);
        this.activeSslContext = initialMaterial.sslContext();
        this.activeStoreFingerprint = initialMaterial.fingerprint();
        this.serverSocket = bindServerSocket(initialMaterial.sslContext(), adapterConfig.port);
        this.nextTlsReloadCheckNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(adapterConfig.tlsReloadIntervalMs);
        log.info(
                "TCP mTLS gzip input adapter listening on port {} with maxConnections={}",
                this.serverSocket.getLocalPort(),
                adapterConfig.maxConnections);
    }

    @Override
    public LogEvent run() {
        if (closed.get()) {
            return null;
        }

        refreshTlsListenerIfDue();

        try {
            LogEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                return event;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            SSLServerSocket listener = serverSocket;
            if (listener == null || listener.isClosed()) {
                return null;
            }
            SSLSocket socket = (SSLSocket) listener.accept();
            if (!connectionPermits.tryAcquire()) {
                socket.close();
                warnConnectionLimitReached();
                return null;
            }
            try {
                clientExecutor.submit(() -> {
                    try {
                        handleClient(socket);
                    } finally {
                        connectionPermits.release();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                connectionPermits.release();
                socket.close();
                if (!closed.get()) {
                    throw rejected;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("Failed to accept TCP mTLS gzip client: {}", e.getMessage(), e);
            }
        }
        return null;
    }

    private void warnConnectionLimitReached() {
        long now = System.nanoTime();
        long previous = lastConnectionLimitWarningNanos.get();
        if ((previous == 0 || now - previous >= CONNECTION_LIMIT_WARNING_INTERVAL_NANOS)
                && lastConnectionLimitWarningNanos.compareAndSet(previous, now)) {
            log.warn("Rejected TCP mTLS client because maxConnections={} is reached", adapterConfig.maxConnections);
        }
    }

    private LoadedTlsMaterial loadTlsMaterial(AdapterConfig config) throws IOException {
        try {
            byte[] keyStoreBytes = Files.readAllBytes(Path.of(config.keyStorePath));
            byte[] trustStoreBytes = Files.readAllBytes(Path.of(config.trustStorePath));
            KeyStore keyStore = loadStore(keyStoreBytes, config.keyStorePassword);
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, config.keyStorePassword);

            KeyStore trustStore = loadStore(trustStoreBytes, config.trustStorePassword);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            return new LoadedTlsMaterial(sslContext, storeFingerprint(keyStoreBytes, trustStoreBytes));
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to initialize TCP mTLS gzip TLS material", e);
        }
    }

    private SSLServerSocket bindServerSocket(SSLContext sslContext, int port) throws IOException {
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        socket.setSoTimeout(50);
        socket.setNeedClientAuth(true);
        socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        return socket;
    }

    private static KeyStore loadStore(byte[] encoded, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = new ByteArrayInputStream(encoded)) {
            store.load(input, password);
        }
        return store;
    }

    private void refreshTlsListenerIfDue() {
        long now = System.nanoTime();
        if (now < nextTlsReloadCheckNanos || closed.get()) {
            return;
        }
        nextTlsReloadCheckNanos = now + TimeUnit.MILLISECONDS.toNanos(adapterConfig.tlsReloadIntervalMs);
        try {
            if (reloadTlsListenerIfChanged()) {
                log.info(
                        "Reloaded TCP mTLS listener on port {} after PKCS12 material changed",
                        listeningPort());
            }
        } catch (IOException reloadFailure) {
            log.warn(
                    "Keeping the current TCP mTLS listener because replacement PKCS12 material could not be loaded: {}",
                    reloadFailure.getMessage(),
                    reloadFailure);
        }
    }

    boolean reloadTlsListenerIfChanged() throws IOException {
        LoadedTlsMaterial candidate = loadTlsMaterial(adapterConfig);
        synchronized (listenerLock) {
            if (closed.get()) {
                return false;
            }
            SSLServerSocket previousListener = serverSocket;
            boolean listenerHealthy = previousListener != null && !previousListener.isClosed();
            if (candidate.fingerprint().equals(activeStoreFingerprint) && listenerHealthy) {
                return false;
            }

            int port = listenerHealthy ? previousListener.getLocalPort() : adapterConfig.port;
            SSLContext previousContext = activeSslContext;
            if (listenerHealthy) {
                previousListener.close();
            }
            try {
                SSLServerSocket replacement = bindServerSocket(candidate.sslContext(), port);
                serverSocket = replacement;
                activeSslContext = candidate.sslContext();
                activeStoreFingerprint = candidate.fingerprint();
                listenerGeneration.incrementAndGet();
                return true;
            } catch (IOException replacementFailure) {
                serverSocket = null;
                if (previousContext != null) {
                    try {
                        serverSocket = bindServerSocket(previousContext, port);
                    } catch (IOException restoreFailure) {
                        replacementFailure.addSuppressed(restoreFailure);
                    }
                }
                throw replacementFailure;
            }
        }
    }

    int listeningPort() {
        SSLServerSocket listener = serverSocket;
        return listener == null ? -1 : listener.getLocalPort();
    }

    long listenerGeneration() {
        return listenerGeneration.get();
    }

    int configuredMaxConnections() {
        return adapterConfig.maxConnections;
    }

    static String storeFingerprint(byte[] keyStoreBytes, byte[] trustStoreBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) 0x01);
            digest.update(keyStoreBytes);
            digest.update((byte) 0x02);
            digest.update(trustStoreBytes);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) {
            result.append(String.format("%02x", part & 0xff));
        }
        return result.toString();
    }

    private record LoadedTlsMaterial(SSLContext sslContext, String fingerprint) {
    }

    private void handleClient(SSLSocket socket) {
        try (socket) {
            socket.setSoTimeout(adapterConfig.timeoutMs);
            socket.startHandshake();
            String clientCn = clientCommonName(socket);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();

            while (!closed.get()) {
                int frameLength;
                try {
                    frameLength = input.readInt();
                } catch (java.io.EOFException e) {
                    return;
                }
                if (frameLength <= 0 || frameLength > adapterConfig.maxFrameBytes) {
                    writeNack(output, "bad_frame", "invalid frame length");
                    return;
                }

                byte[] payload = input.readNBytes(frameLength);
                if (payload.length != frameLength) {
                    writeNack(output, "bad_frame", "incomplete frame payload");
                    return;
                }

                List<LogEvent> events;
                try {
                    JsonNode batch = decodeBatch(payload);
                    events = toLogEvents(batch, clientCn, getMessageType());
                } catch (Exception e) {
                    writeNack(output, "bad_frame", e.getMessage());
                    return;
                }

                String batchKey = recentBatchKey(events);
                if (!enqueueBatchAtomically(eventQueue, events, batchKey, enqueueLock, recentBatches)) {
                    writeNack(output, "queue_full", "input adapter queue cannot accept the complete batch");
                    continue;
                }
                writeAck(output);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log.warn("TCP mTLS gzip client handling failed: {}", e.getMessage(), e);
            }
        }
    }

    private JsonNode decodeBatch(byte[] gzipPayload) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(gzipPayload))) {
            byte[] decoded = readAtMost(gzipInputStream, DEFAULT_MAX_DECOMPRESSED_BYTES);
            return OBJECT_MAPPER.readTree(decoded);
        }
    }

    static List<LogEvent> toLogEvents(JsonNode batch, String clientCn, String messageType) throws IOException {
        if (batch == null || !batch.isObject()) {
            throw new IllegalArgumentException("batch must be a JSON object");
        }
        String sourceId = requiredBoundedText(batch, "source_id", 255);
        if (!sourceId.equals(clientCn)) {
            throw new IllegalArgumentException("batch source_id does not match client certificate CN");
        }
        boolean schema11 = "1.1".equals(textOrNull(batch, "schema_version"));
        String batchId = textOrNull(batch, "batch_id");
        if (batchId != null && batchId.length() > MAX_BATCH_ID_LENGTH) {
            throw new IllegalArgumentException("batch batch_id exceeds " + MAX_BATCH_ID_LENGTH + " characters");
        }
        Integer chunkIndex = integerOrNull(batch, "chunk_index");
        Integer chunkCount = integerOrNull(batch, "chunk_count");
        if (schema11) {
            batchId = requiredBoundedText(batch, "batch_id", MAX_BATCH_ID_LENGTH);
            chunkIndex = requiredInteger(batch, "chunk_index");
            chunkCount = requiredInteger(batch, "chunk_count");
            if (chunkCount <= 0) {
                throw new IllegalArgumentException("batch chunk_count must be greater than zero");
            }
            if (chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw new IllegalArgumentException("batch chunk_index must be between zero and chunk_count - 1");
            }
        }
        JsonNode items = batch.get("items");
        if (items == null) {
            if (schema11) {
                throw new IllegalArgumentException("schema 1.1 batch items must be an array");
            }
            return List.of();
        }
        if (!items.isArray()) {
            throw new IllegalArgumentException("batch items must be an array");
        }
        if (items.size() > DEFAULT_MAX_BATCH_ITEMS) {
            throw new IllegalArgumentException("batch item count exceeds " + DEFAULT_MAX_BATCH_ITEMS);
        }

        List<LogEvent> events = new ArrayList<>();
        Set<Integer> itemSequences = schema11 ? new HashSet<>() : Set.of();
        Iterator<JsonNode> iterator = items.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (schema11) {
                requiredBoundedText(item, "item_id", MAX_ITEM_ID_LENGTH);
                int sequence = requiredInteger(item, "sequence");
                if (sequence < 0) {
                    throw new IllegalArgumentException("batch item sequence must be nonnegative");
                }
                if (!itemSequences.add(sequence)) {
                    throw new IllegalArgumentException("batch item sequence must be unique within a chunk");
                }
            }
            String originalText = OBJECT_MAPPER.writeValueAsString(item);
            LogEvent event = new LogEvent(originalText, sourceId, messageType);
            event.setField("schema_version", textOrNull(batch, "schema_version"));
            event.setField("batch_id", batchId);
            event.setField("chunk_index", chunkIndex);
            event.setField("chunk_count", chunkCount);
            event.setField("chunk_item_count", items.size());
            event.setField("source", textOrNull(batch, "source"));
            event.setField("source_id", sourceId);
            event.setField("tenant_id", textOrNull(batch, "tenant_id"));
            event.setField("observed_at", textOrNull(batch, "observed_at"));
            event.setField("sent_at", textOrNull(batch, "sent_at"));
            event.setField("item_kind", textOrNull(item, "kind"));
            event.setField("item_type", textOrNull(item, "type"));
            event.setField("item_key", textOrNull(item, "key"));
            event.setField("item_id", textOrNull(item, "item_id"));
            event.setField("item_sequence", integerOrNull(item, "sequence"));
            JsonNode payload = item.get("payload");
            if (payload != null && !payload.isNull()) {
                event.setField("payload", OBJECT_MAPPER.convertValue(payload, Object.class));
                exposePayloadFields(event, payload);
            }
            events.add(event);
        }
        return events;
    }

    static boolean enqueueBatchAtomically(
            BlockingQueue<LogEvent> queue,
            List<LogEvent> events,
            String batchKey,
            Object lock,
            LinkedHashMap<String, Boolean> recentBatches
    ) {
        synchronized (lock) {
            if (batchKey != null && recentBatches.containsKey(batchKey)) {
                return true;
            }
            if (events.size() > queue.remainingCapacity()) {
                return false;
            }
            for (LogEvent event : events) {
                queue.add(event);
            }
            if (batchKey != null) {
                recentBatches.put(batchKey, Boolean.TRUE);
                while (recentBatches.size() > RECENT_BATCH_LIMIT) {
                    String oldest = recentBatches.keySet().iterator().next();
                    recentBatches.remove(oldest);
                }
            }
            return true;
        }
    }

    static String recentBatchKey(List<LogEvent> events) {
        if (events.isEmpty()) {
            return null;
        }
        Object batchId = events.getFirst().getField("batch_id");
        Object chunkIndex = events.getFirst().getField("chunk_index");
        Object sourceId = events.getFirst().getField("source_id");
        if (batchId == null || batchId.toString().isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(sourceId == null ? "" : sourceId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(batchId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(chunkIndex == null ? 0 : chunkIndex).getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] readAtMost(InputStream input, int maxBytes) throws IOException {
        byte[] decoded = input.readNBytes(maxBytes + 1);
        if (decoded.length > maxBytes) {
            throw new IOException("decompressed batch exceeds " + maxBytes + " bytes");
        }
        return decoded;
    }

    private static void exposePayloadFields(LogEvent event, JsonNode payload) {
        if (!payload.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            event.setField(payloadFieldName(field.getKey()), OBJECT_MAPPER.convertValue(field.getValue(), Object.class));
        }
    }

    private static String payloadFieldName(String key) {
        StringBuilder normalized = new StringBuilder("payload_");
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                normalized.append(ch);
            } else {
                normalized.append('_');
            }
        }
        return normalized.toString();
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = textOrNull(node, fieldName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("batch " + fieldName + " is required");
        }
        return value;
    }

    private static String requiredBoundedText(JsonNode node, String fieldName, int maxLength) {
        String value = requiredText(node, fieldName);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("batch " + fieldName + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static Integer integerOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static int requiredInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("batch " + fieldName + " must be an integer");
        }
        return value.intValue();
    }

    private String clientCommonName(SSLSocket socket) throws Exception {
        X509Certificate cert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        LdapName ldapName = new LdapName(cert.getSubjectX500Principal().getName());
        for (Rdn rdn : ldapName.getRdns()) {
            if ("CN".equalsIgnoreCase(rdn.getType())) {
                return String.valueOf(rdn.getValue());
            }
        }
        throw new IllegalArgumentException("client certificate CN is required");
    }

    static void writeAck(OutputStream output) throws IOException {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("status", "accepted");
        writeResponse(output, response);
    }

    static void writeNack(OutputStream output, String code, String message) throws IOException {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("status", "error");
        response.put("code", code);
        response.put("message", message == null ? "" : message);
        writeResponse(output, response);
    }

    private static void writeResponse(OutputStream output, ObjectNode response) throws IOException {
        output.write(OBJECT_MAPPER.writeValueAsBytes(response));
        output.write('\n');
        output.flush();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (listenerLock) {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        }
        clientExecutor.shutdownNow();
    }

    private record AdapterConfig(
            int port,
            String keyStorePath,
            char[] keyStorePassword,
            String trustStorePath,
            char[] trustStorePassword,
            int maxFrameBytes,
            int timeoutMs,
            int maxConnections,
            int tlsReloadIntervalMs
    ) {
        static AdapterConfig from(InputAdapterConfig config, Function<String, String> envLookup) throws IOException {
            if (config.getPort() == null) {
                throw new IOException("TcpMtlsGzipInputAdapter requires port");
            }
            JsonNode root = OBJECT_MAPPER.readTree(config.getConfigParams());
            int maxFrameBytes = root.has("maxFrameBytes")
                    ? root.get("maxFrameBytes").asInt()
                    : DEFAULT_MAX_FRAME_BYTES;
            int timeoutMs = config.getTimeoutMs() == null ? 30_000 : config.getTimeoutMs();
            int defaultMaxConnections = config.getWorkerThreads() == null
                    ? DEFAULT_MAX_CONNECTIONS
                    : config.getWorkerThreads();
            int maxConnections = root.has("maxConnections")
                    ? root.get("maxConnections").asInt()
                    : defaultMaxConnections;
            int tlsReloadIntervalMs = root.has("tlsReloadIntervalMs")
                    ? root.get("tlsReloadIntervalMs").asInt()
                    : DEFAULT_TLS_RELOAD_INTERVAL_MS;
            if (config.getPort() < 0 || config.getPort() > 65_535) {
                throw new IOException("port must be between 0 and 65535");
            }
            if (maxFrameBytes <= 0 || timeoutMs <= 0 || maxConnections <= 0 || tlsReloadIntervalMs <= 0) {
                throw new IOException("frame, timeout, maxConnections, and TLS reload limits must be positive");
            }
            return new AdapterConfig(
                    config.getPort(),
                    requiredConfig(root, "keyStorePath"),
                    envSecret(requiredConfig(root, "keyStorePasswordEnv"), envLookup),
                    requiredConfig(root, "trustStorePath"),
                    envSecret(requiredConfig(root, "trustStorePasswordEnv"), envLookup),
                    maxFrameBytes,
                    timeoutMs,
                    maxConnections,
                    tlsReloadIntervalMs
            );
        }

        private static String requiredConfig(JsonNode root, String fieldName) {
            String value = textOrNull(root, fieldName);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("configParams." + fieldName + " is required");
            }
            return value;
        }

        private static char[] envSecret(String envName, Function<String, String> envLookup) {
            String value = envLookup.apply(envName);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Environment variable " + envName + " is required");
            }
            return value.toCharArray();
        }
    }
}
