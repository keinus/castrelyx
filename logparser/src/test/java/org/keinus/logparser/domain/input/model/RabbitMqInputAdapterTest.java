package org.keinus.logparser.domain.input.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

class RabbitMqInputAdapterTest {

    @Test
    void runConsumesMessageAndAcknowledgesIt() throws Exception {
        InputAdapterConfig config = rabbitMqConfig("""
                {
                  "queue": "logs.input",
                  "autoAck": false
                }
                """);
        FakeRabbitMqClient client = new FakeRabbitMqClient(
                List.of(new RabbitMqInputAdapter.RabbitMqMessage("rabbit-msg", "rabbit.local", 42L))
        );

        RabbitMqInputAdapter adapter = new RabbitMqInputAdapter(config, client);

        LogEvent event = adapter.run();

        assertNotNull(event);
        assertEquals("rabbit-msg", event.getOriginalText());
        assertEquals("rabbit-logs", event.getMessageType());
        assertEquals("rabbit.local", event.getSourceHost());
        assertEquals(List.of(false), client.autoAckRequests);
        assertEquals(List.of(42L), client.ackedDeliveryTags);
    }

    @Test
    void runDoesNotAckWhenAutoAckIsEnabled() throws Exception {
        InputAdapterConfig config = rabbitMqConfig("""
                {
                  "queue": "logs.input",
                  "autoAck": true
                }
                """);
        FakeRabbitMqClient client = new FakeRabbitMqClient(
                List.of(new RabbitMqInputAdapter.RabbitMqMessage("auto-ack-msg", "rabbit.local", 7L))
        );

        RabbitMqInputAdapter adapter = new RabbitMqInputAdapter(config, client);

        LogEvent event = adapter.run();

        assertNotNull(event);
        assertEquals("auto-ack-msg", event.getOriginalText());
        assertEquals(List.of(true), client.autoAckRequests);
        assertTrue(client.ackedDeliveryTags.isEmpty());
    }

    @Test
    void runReturnsNullWhenQueueIsEmpty() throws Exception {
        InputAdapterConfig config = rabbitMqConfig("""
                {
                  "queue": "logs.input"
                }
                """);
        FakeRabbitMqClient client = new FakeRabbitMqClient(List.of());

        RabbitMqInputAdapter adapter = new RabbitMqInputAdapter(config, client);

        assertNull(adapter.run());
    }

    @Test
    void closeClosesClient() throws Exception {
        InputAdapterConfig config = rabbitMqConfig("""
                {
                  "queue": "logs.input"
                }
                """);
        FakeRabbitMqClient client = new FakeRabbitMqClient(List.of());
        RabbitMqInputAdapter adapter = new RabbitMqInputAdapter(config, client);

        adapter.close();

        assertTrue(client.closed);
    }

    private InputAdapterConfig rabbitMqConfig(String configParams) {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(10L);
        config.setType("RabbitMqInputAdapter");
        config.setMessagetype("rabbit-logs");
        config.setHost("rabbit.local");
        config.setPort(5672);
        config.setTimeoutMs(1500);
        config.setConfigParams(configParams);
        return config;
    }

    private static final class FakeRabbitMqClient implements RabbitMqInputAdapter.RabbitMqClient {
        private final List<RabbitMqInputAdapter.RabbitMqMessage> messages;
        private final List<Boolean> autoAckRequests = new ArrayList<>();
        private final List<Long> ackedDeliveryTags = new ArrayList<>();
        private boolean closed;

        private FakeRabbitMqClient(List<RabbitMqInputAdapter.RabbitMqMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }

        @Override
        public RabbitMqInputAdapter.RabbitMqMessage get(boolean autoAck) {
            autoAckRequests.add(autoAck);
            if (messages.isEmpty()) {
                return null;
            }
            return messages.remove(0);
        }

        @Override
        public void ack(long deliveryTag) {
            ackedDeliveryTags.add(deliveryTag);
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
