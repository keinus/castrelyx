package org.keinus.logparser.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.KafkaInputAdapter;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * KafkaInputAdapter 클래스의 단위 테스트
 *
 * 테스트 대상 함수들:
 * - KafkaInputAdapter(InputAdapterConfig) : 생성자 테스트
 * - run() : 메시지 폴링 및 큐 처리 로직 테스트
 * - close() : 리소스 정리 테스트
 */
@ExtendWith(MockitoExtension.class)
class KafkaInputAdapterTest {

    private InputAdapterConfig validConfig;

    @BeforeEach
    void setUp() {
        validConfig = new InputAdapterConfig();
        validConfig.setType("KafkaInputAdapter");
        validConfig.setBootstrapservers("localhost:9092");
        validConfig.setTopicid("test-topic");
        validConfig.setMessagetype("test-message");
    }

    @Test
    @DisplayName("생성자 테스트 - 유효한 설정으로 생성")
    void testConstructorWithValidConfig() {
        // Given
        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction =
                     mockConstruction(kafkaConsumerType())) {
            // When & Then
            assertDoesNotThrow(() -> new KafkaInputAdapter(validConfig));
            assertEquals(1, mockedConstruction.constructed().size());
        }
    }

    @Test
    @DisplayName("생성자 테스트 - null 설정으로 생성 시 예외 발생")
    void testConstructorWithNullConfig() {
        // When & Then
        assertThrows(IOException.class, () -> new KafkaInputAdapter(null));
    }

    @Test
    @DisplayName("생성자 테스트 - 필수 필드 누락 시 예외 발생")
    void testConstructorWithMissingFields() {
        // Given
        InputAdapterConfig incompleteConfig = new InputAdapterConfig();
        incompleteConfig.setBootstrapservers("localhost:9092");
        incompleteConfig.setType("KafkaInputAdapter");
        // topicid is missing

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> new KafkaInputAdapter(incompleteConfig));
    }

    @Test
    @DisplayName("run() 테스트 - 메시지 큐가 비어있고 새 레코드가 있을 때")
    void testRunWithEmptyQueueAndNewRecords() throws IOException {
        // Given
        TopicPartition partition = new TopicPartition("test-topic", 0);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0L, "key", "test message");
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(record)));

        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction = mockConstruction(kafkaConsumerType(),
                (mock, context) -> {
                    when(mock.poll(any(Duration.class))).thenReturn(records);
                })) {

            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);
            assertEquals(1, mockedConstruction.constructed().size());

            // When
            LogEvent result = adapter.run();

            // Then
            assertNotNull(result);
            assertEquals("test message", result.getOriginalText());
            assertEquals("test-message", result.getMessageType());
        }
    }

    @Test
    @DisplayName("run() 테스트 - 메시지 큐가 비어있고 새 레코드가 없을 때")
    void testRunWithEmptyQueueAndNoRecords() throws IOException {
        // Given
        ConsumerRecords<String, String> emptyRecords = new ConsumerRecords<>(Map.of());

        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction = mockConstruction(kafkaConsumerType(),
                (mock, context) -> {
                    when(mock.poll(any(Duration.class))).thenReturn(emptyRecords);
                })) {

            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);
            assertEquals(1, mockedConstruction.constructed().size());

            // When
            LogEvent result = adapter.run();

            // Then
            assertNull(result);
        }
    }

    @Test
    @DisplayName("close() 테스트 - 리소스 정리")
    void testClose() throws IOException {
        // Given
        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction =
                     mockConstruction(kafkaConsumerType())) {
            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);

            // When
            adapter.close();

            // Then
            KafkaConsumer<String, String> mockConsumer = mockedConstruction.constructed().get(0);
            verify(mockConsumer, times(1)).close(any(Duration.class));
        }
    }

    @Test
    @DisplayName("getType() 테스트 - 메시지 타입 반환")
    void testGetType() throws IOException {
        // Given
        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction =
                     mockConstruction(kafkaConsumerType())) {
            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);
            assertEquals(1, mockedConstruction.constructed().size());

            // When
            String type = adapter.getMessageType();

            // Then
            assertEquals("test-message", type);
        }
    }

    @Test
    @DisplayName("getSourceHost() 테스트 - 소스 호스트 반환")
    void testGetSourceHost() throws IOException {
        // Given
        validConfig.setHost("custom-host");
        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction =
                     mockConstruction(kafkaConsumerType())) {
            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);
            assertEquals(1, mockedConstruction.constructed().size());

            // When
            String host = adapter.getSourceHost();

            // Then
            assertEquals("custom-host", host);
        }
    }

    @Test
    @DisplayName("getSourceHost() 테스트 - 기본 호스트 반환")
    void testGetSourceHostDefault() throws IOException {
        // Given
        try (MockedConstruction<KafkaConsumer<String, String>> mockedConstruction =
                     mockConstruction(kafkaConsumerType())) {
            KafkaInputAdapter adapter = new KafkaInputAdapter(validConfig);
            assertEquals(1, mockedConstruction.constructed().size());

            // When
            String host = adapter.getSourceHost();

            // Then
            assertEquals("localhost", host);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<KafkaConsumer<String, String>> kafkaConsumerType() {
        return (Class<KafkaConsumer<String, String>>) (Class<?>) KafkaConsumer.class;
    }
}
