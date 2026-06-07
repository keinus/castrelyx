package org.keinus.logparser.domain.input.model;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RabbitMqInputAdapter extends InputAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5672;
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_PREFETCH_COUNT = 1;

    private final RabbitMqConfig rabbitMqConfig;
    private final RabbitMqClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RabbitMqInputAdapter(InputAdapterConfig config) throws IOException {
        this(config, null);
    }

    RabbitMqInputAdapter(InputAdapterConfig config, RabbitMqClient client) throws IOException {
        super(config);
        this.rabbitMqConfig = RabbitMqConfig.from(config);
        this.client = client != null ? client : new RabbitMqJavaClient(rabbitMqConfig);
        log.info("RabbitMQ Input Adapter initialized for queue {} at {}:{}",
                rabbitMqConfig.queue(), rabbitMqConfig.host(), rabbitMqConfig.port());
    }

    @Override
    public LogEvent run() {
        if (closed.get()) {
            return null;
        }

        RabbitMqMessage message;
        try {
            message = client.get(rabbitMqConfig.autoAck());
            if (message == null) {
                return null;
            }
            if (!rabbitMqConfig.autoAck()) {
                client.ack(message.deliveryTag());
            }
        } catch (IOException e) {
            log.warn("RabbitMQ input polling failed: {}", e.getMessage(), e);
            return null;
        }

        return createLogEvent(message.body(), message.sourceHost());
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        client.close();
    }

    interface RabbitMqClient {
        RabbitMqMessage get(boolean autoAck) throws IOException;

        void ack(long deliveryTag) throws IOException;

        default void close() throws IOException {
        }
    }

    public record RabbitMqMessage(String body, String sourceHost, long deliveryTag) {
    }

    private record RabbitMqConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            String queue,
            String exchange,
            String routingKey,
            boolean autoAck,
            boolean declareQueue,
            boolean durableQueue,
            boolean exclusiveQueue,
            boolean autoDeleteQueue,
            boolean bindQueue,
            int prefetchCount,
            int timeoutMs,
            Charset charset
    ) {
        static RabbitMqConfig from(InputAdapterConfig config) throws IOException {
            JsonNode root = readRoot(config.getConfigParams());

            String host = firstNonBlank(config.getHost(), text(root, "host", DEFAULT_HOST));
            int port = config.getPort() != null
                    ? config.getPort()
                    : (int) number(root, "port", number(root, "rmqPort", DEFAULT_PORT));
            String queue = text(root, "queue", text(root, "queueName", null));
            if (queue == null || queue.isBlank()) {
                throw new IllegalArgumentException("RabbitMqInputAdapter configParams.queue is required");
            }

            String exchange = text(root, "exchange", null);
            String routingKey = text(root, "routingKey", text(root, "routingkey", ""));
            boolean declareQueue = bool(root, "declareQueue", false);
            boolean bindQueue = bool(root, "bindQueue", declareQueue && exchange != null && !exchange.isBlank());
            int timeoutMs = config.getTimeoutMs() != null
                    ? config.getTimeoutMs()
                    : (int) number(root, "timeoutMs", DEFAULT_TIMEOUT_MS);

            return new RabbitMqConfig(
                    host,
                    Math.max(1, Math.min(port, 65535)),
                    text(root, "username", text(root, "rmqUsername", "guest")),
                    text(root, "password", text(root, "rmqPassword", "guest")),
                    text(root, "virtualHost", "/"),
                    queue,
                    exchange,
                    routingKey,
                    bool(root, "autoAck", false),
                    declareQueue,
                    bool(root, "durableQueue", bool(root, "durable", true)),
                    bool(root, "exclusiveQueue", bool(root, "exclusive", false)),
                    bool(root, "autoDeleteQueue", bool(root, "autoDelete", false)),
                    bindQueue,
                    Math.max(1, (int) number(root, "prefetchCount", DEFAULT_PREFETCH_COUNT)),
                    Math.max(100, timeoutMs),
                    readCharset(text(root, "charset", StandardCharsets.UTF_8.name()))
            );
        }

        private static JsonNode readRoot(String configParams) throws IOException {
            if (configParams == null || configParams.trim().isEmpty()) {
                throw new IllegalArgumentException("RabbitMqInputAdapter requires configParams");
            }
            return OBJECT_MAPPER.readTree(configParams);
        }

        private static Charset readCharset(String charsetName) throws IOException {
            try {
                return Charset.forName(charsetName);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw new IOException("Invalid RabbitMQ charset: " + charsetName, e);
            }
        }

        private static String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
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

        private static boolean bool(JsonNode node, String fieldName, boolean defaultValue) {
            JsonNode value = node == null ? null : node.get(fieldName);
            if (value == null || !value.isBoolean()) {
                return defaultValue;
            }
            return value.asBoolean();
        }
    }

    private static final class RabbitMqJavaClient implements RabbitMqClient {
        private final RabbitMqConfig config;
        private final Connection connection;
        private final Channel channel;

        private RabbitMqJavaClient(RabbitMqConfig config) throws IOException {
            this.config = config;

            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(config.host());
            factory.setPort(config.port());
            factory.setUsername(config.username());
            factory.setPassword(config.password());
            factory.setVirtualHost(config.virtualHost());
            factory.setConnectionTimeout(config.timeoutMs());
            factory.setHandshakeTimeout(config.timeoutMs());
            factory.setAutomaticRecoveryEnabled(true);

            try {
                this.connection = factory.newConnection();
                this.channel = connection.createChannel();
                configureChannel();
            } catch (TimeoutException e) {
                throw new IOException("Failed to initialize RabbitMQ input adapter", e);
            }
        }

        private void configureChannel() throws IOException {
            if (!config.autoAck()) {
                channel.basicQos(config.prefetchCount());
            }
            if (config.declareQueue()) {
                channel.queueDeclare(
                        config.queue(),
                        config.durableQueue(),
                        config.exclusiveQueue(),
                        config.autoDeleteQueue(),
                        null
                );
            }
            if (config.bindQueue() && config.exchange() != null && !config.exchange().isBlank()) {
                channel.queueBind(config.queue(), config.exchange(), config.routingKey());
            }
        }

        @Override
        public RabbitMqMessage get(boolean autoAck) throws IOException {
            GetResponse response = channel.basicGet(config.queue(), autoAck);
            if (response == null) {
                return null;
            }
            String body = new String(response.getBody(), config.charset());
            return new RabbitMqMessage(body, config.host(), response.getEnvelope().getDeliveryTag());
        }

        @Override
        public void ack(long deliveryTag) throws IOException {
            channel.basicAck(deliveryTag, false);
        }

        @Override
        public void close() throws IOException {
            IOException closeError = null;
            try {
                channel.close();
            } catch (IOException e) {
                closeError = e;
            } catch (TimeoutException e) {
                closeError = new IOException("Failed to close RabbitMQ channel", e);
            }

            try {
                connection.close();
            } catch (IOException e) {
                if (closeError == null) {
                    closeError = e;
                } else {
                    closeError.addSuppressed(e);
                }
            }

            if (closeError != null) {
                throw closeError;
            }
        }
    }
}
