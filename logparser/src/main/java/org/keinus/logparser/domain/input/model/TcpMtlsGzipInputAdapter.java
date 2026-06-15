package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

@Slf4j
public class TcpMtlsGzipInputAdapter extends InputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_FRAME_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_QUEUE_SIZE = 10_000;
    private static final int DEFAULT_WORKERS = 32;

    private final AdapterConfig adapterConfig;
    private final BlockingQueue<LogEvent> eventQueue;
    private final ExecutorService clientExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final SSLServerSocket serverSocket;

    public TcpMtlsGzipInputAdapter(InputAdapterConfig config) throws IOException {
        super(config);
        this.adapterConfig = AdapterConfig.from(config);
        int queueSize = config.getQueueSize() == null ? DEFAULT_QUEUE_SIZE : config.getQueueSize();
        int workerThreads = config.getWorkerThreads() == null ? DEFAULT_WORKERS : config.getWorkerThreads();
        this.eventQueue = new LinkedBlockingQueue<>(queueSize);
        this.clientExecutor = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("TcpMtlsGzipClient-" + adapterConfig.port + "-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        this.serverSocket = createServerSocket(adapterConfig);
        log.info("TCP mTLS gzip input adapter listening on port {}", adapterConfig.port);
    }

    @Override
    public LogEvent run() {
        if (closed.get()) {
            return null;
        }

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
            SSLSocket socket = (SSLSocket) serverSocket.accept();
            clientExecutor.submit(() -> handleClient(socket));
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("Failed to accept TCP mTLS gzip client: {}", e.getMessage(), e);
            }
        }
        return null;
    }

    private SSLServerSocket createServerSocket(AdapterConfig config) throws IOException {
        try {
            KeyStore keyStore = loadStore(config.keyStorePath, config.keyStorePassword);
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, config.keyStorePassword);

            KeyStore trustStore = loadStore(config.trustStorePath, config.trustStorePassword);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(config.port);
            socket.setReuseAddress(true);
            socket.setSoTimeout(50);
            socket.setNeedClientAuth(true);
            socket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            return socket;
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to initialize TCP mTLS gzip server socket", e);
        }
    }

    private static KeyStore loadStore(String path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))) {
            store.load(input, password);
        }
        return store;
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

                for (LogEvent event : events) {
                    if (!eventQueue.offer(event, 1, TimeUnit.SECONDS)) {
                        writeNack(output, "queue_full", "input adapter queue is full");
                        return;
                    }
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
            return OBJECT_MAPPER.readTree(gzipInputStream);
        }
    }

    static List<LogEvent> toLogEvents(JsonNode batch, String clientCn, String messageType) throws IOException {
        if (batch == null || !batch.isObject()) {
            throw new IllegalArgumentException("batch must be a JSON object");
        }
        String sourceId = requiredText(batch, "source_id");
        if (!sourceId.equals(clientCn)) {
            throw new IllegalArgumentException("batch source_id does not match client certificate CN");
        }
        JsonNode items = batch.get("items");
        if (items == null) {
            return List.of();
        }
        if (!items.isArray()) {
            throw new IllegalArgumentException("batch items must be an array");
        }

        List<LogEvent> events = new ArrayList<>();
        Iterator<JsonNode> iterator = items.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            String originalText = OBJECT_MAPPER.writeValueAsString(item);
            LogEvent event = new LogEvent(originalText, sourceId, messageType);
            event.setField("schema_version", textOrNull(batch, "schema_version"));
            event.setField("source", textOrNull(batch, "source"));
            event.setField("source_id", sourceId);
            event.setField("tenant_id", textOrNull(batch, "tenant_id"));
            event.setField("observed_at", textOrNull(batch, "observed_at"));
            event.setField("sent_at", textOrNull(batch, "sent_at"));
            event.setField("item_kind", textOrNull(item, "kind"));
            event.setField("item_type", textOrNull(item, "type"));
            event.setField("item_key", textOrNull(item, "key"));
            JsonNode payload = item.get("payload");
            if (payload != null && !payload.isNull()) {
                event.setField("payload", OBJECT_MAPPER.convertValue(payload, Object.class));
                exposePayloadFields(event, payload);
            }
            events.add(event);
        }
        return events;
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

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
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

    private void writeAck(OutputStream output) throws IOException {
        output.write("{\"status\":\"accepted\"}\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeNack(OutputStream output, String code, String message) throws IOException {
        String safeMessage = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        String response = "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"" + safeMessage + "\"}\n";
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        serverSocket.close();
        clientExecutor.shutdownNow();
    }

    private record AdapterConfig(
            int port,
            String keyStorePath,
            char[] keyStorePassword,
            String trustStorePath,
            char[] trustStorePassword,
            int maxFrameBytes,
            int timeoutMs
    ) {
        static AdapterConfig from(InputAdapterConfig config) throws IOException {
            if (config.getPort() == null) {
                throw new IOException("TcpMtlsGzipInputAdapter requires port");
            }
            JsonNode root = OBJECT_MAPPER.readTree(config.getConfigParams());
            return new AdapterConfig(
                    config.getPort(),
                    requiredConfig(root, "keyStorePath"),
                    envSecret(requiredConfig(root, "keyStorePasswordEnv")),
                    requiredConfig(root, "trustStorePath"),
                    envSecret(requiredConfig(root, "trustStorePasswordEnv")),
                    root.has("maxFrameBytes") ? root.get("maxFrameBytes").asInt() : DEFAULT_MAX_FRAME_BYTES,
                    config.getTimeoutMs() == null ? 30_000 : config.getTimeoutMs()
            );
        }

        private static String requiredConfig(JsonNode root, String fieldName) {
            String value = textOrNull(root, fieldName);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("configParams." + fieldName + " is required");
            }
            return value;
        }

        private static char[] envSecret(String envName) {
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Environment variable " + envName + " is required");
            }
            return value.toCharArray();
        }
    }
}
