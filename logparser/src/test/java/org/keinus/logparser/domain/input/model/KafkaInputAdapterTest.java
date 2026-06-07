package org.keinus.logparser.domain.input.model;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaInputAdapterTest {

    private InputAdapterConfig config;

    @BeforeEach
    void setUp() {
        config = new InputAdapterConfig();
        config.setType("KafkaInputAdapter");
        config.setBootstrapservers("localhost:9092");
        config.setTopicid("test-topic");
        config.setMessagetype("kafka-test");
    }

    @Test
    @DisplayName("Constructor should throw exception if required fields are missing")
    void constructorMissingFields() {
        config.setBootstrapservers(null);
        assertThrows(IllegalArgumentException.class, () -> new KafkaInputAdapter(config));
        
        config.setBootstrapservers("localhost:9092");
        config.setTopicid(null);
        assertThrows(IllegalArgumentException.class, () -> new KafkaInputAdapter(config));
    }

    @Test
    @DisplayName("Should poll messages from Kafka")
    void pollMessages() throws IOException {
        TopicPartition partition = new TopicPartition("test-topic", 0);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0L, "key", "kafka-msg");
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(record)));

        try (MockedConstruction<KafkaConsumer<String, String>> mocked = mockConstruction(kafkaConsumerType(),
                (mock, context) -> {
                    when(mock.poll(any(Duration.class))).thenReturn(records);
                })) {
            
            KafkaInputAdapter adapter = new KafkaInputAdapter(config);
            assertThat(mocked.constructed()).hasSize(1);
            LogEvent event = adapter.run();
            
            assertThat(event).isNotNull();
            assertThat(event.getOriginalText()).isEqualTo("kafka-msg");
            assertThat(event.getMessageType()).isEqualTo("kafka-test");
        }
    }

    @Test
    @DisplayName("Should return null when no messages are available")
    void noMessages() throws IOException {
        try (MockedConstruction<KafkaConsumer<String, String>> mocked = mockConstruction(kafkaConsumerType(),
                (mock, context) -> {
                    when(mock.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Collections.emptyMap()));
                })) {
            
            KafkaInputAdapter adapter = new KafkaInputAdapter(config);
            assertThat(mocked.constructed()).hasSize(1);
            LogEvent event = adapter.run();
            
            assertThat(event).isNull();
        }
    }

    @Test
    @DisplayName("Should close consumer on close")
    void closeAdapter() throws IOException {
        try (MockedConstruction<KafkaConsumer<String, String>> mocked = mockConstruction(kafkaConsumerType())) {
            KafkaInputAdapter adapter = new KafkaInputAdapter(config);
            adapter.close();
            
            KafkaConsumer<String, String> mock = mocked.constructed().get(0);
            verify(mock).close(any(Duration.class));
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<KafkaConsumer<String, String>> kafkaConsumerType() {
        return (Class<KafkaConsumer<String, String>>) (Class<?>) KafkaConsumer.class;
    }
}
