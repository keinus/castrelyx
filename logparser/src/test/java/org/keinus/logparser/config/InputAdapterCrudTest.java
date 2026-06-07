package org.keinus.logparser.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.*;
import org.keinus.logparser.interfaces.exception.ConfigNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InputAdapter CRUD 기능 통합 테스트
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ConfigManagementService.class, ConfigValidationService.class})
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.data.jpa.repository.config.EnableJpaAuditing
class InputAdapterCrudTest {

    @Autowired
    private ConfigManagementService configManagementService;

    @Autowired
    private InputAdapterRepository inputAdapterRepository;

    @TestConfiguration
    static class MappingRepositoryTestConfig {
        @Bean
        MappingRepository mappingRepository() {
            return new MappingRepository() {
                @Override
                public Optional<MappingConfiguration> findByMessageType(String messageType) {
                    return Optional.empty();
                }

                @Override
                public List<MappingConfiguration> findAll() {
                    return List.of();
                }

                @Override
                public void save(MappingConfiguration config) {
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        inputAdapterRepository.deleteAll();
    }

    // ==================== CREATE 테스트 ====================

    @Test
    @DisplayName("InputAdapter 생성 - 정상 케이스")
    void testCreateInputAdapter_Success() {
        // Given
        InputAdapterEntity entity = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("tcp-test-log")
                .host("localhost")
                .port(8080)
                .enabled(true)
                .build();

        // When
        InputAdapterEntity created = configManagementService.createInputAdapter(entity);

        // Then
        assertNotNull(created.getId());
        assertEquals("TcpInputAdapter", created.getType());
        assertEquals("tcp-test-log", created.getMessagetype());
        assertEquals("localhost", created.getHost());
        assertEquals(8080, created.getPort());
        assertTrue(created.getEnabled());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    @DisplayName("InputAdapter 생성 - 다양한 타입")
    void testCreateInputAdapter_DifferentTypes() {
        // TCP
        InputAdapterEntity tcp = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("tcp-log")
                .port(8080)
                .build();
        InputAdapterEntity createdTcp = configManagementService.createInputAdapter(tcp);
        assertEquals("TcpInputAdapter", createdTcp.getType());

        // UDP
        InputAdapterEntity udp = InputAdapterEntity.builder()
                .type("UdpInputAdapter")
                .messagetype("udp-log")
                .port(8081)
                .build();
        InputAdapterEntity createdUdp = configManagementService.createInputAdapter(udp);
        assertEquals("UdpInputAdapter", createdUdp.getType());

        // Kafka
        InputAdapterEntity kafka = InputAdapterEntity.builder()
                .type("KafkaInputAdapter")
                .messagetype("kafka-log")
                .topicid("test-topic")
                .bootstrapservers("localhost:9092")
                .build();
        InputAdapterEntity createdKafka = configManagementService.createInputAdapter(kafka);
        assertEquals("KafkaInputAdapter", createdKafka.getType());
    }

    // ==================== READ 테스트 ====================

    @Test
    @DisplayName("InputAdapter 조회 - ID로 조회 성공")
    void testGetInputAdapter_ById_Success() {
        // Given
        InputAdapterEntity entity = createAndSaveTestAdapter("test-read");

        // When
        InputAdapterEntity found = configManagementService.getInputAdapter(entity.getId());

        // Then
        assertNotNull(found);
        assertEquals(entity.getId(), found.getId());
        assertEquals("test-read", found.getMessagetype());
    }

    @Test
    @DisplayName("InputAdapter 조회 - 존재하지 않는 ID")
    void testGetInputAdapter_NotFound() {
        // When & Then
        assertThrows(ConfigNotFoundException.class, () ->
                configManagementService.getInputAdapter(999L));
    }

    @Test
    @DisplayName("InputAdapter 조회 - 페이징 조회")
    void testGetAllInputAdapters_Paging() {
        // Given
        for (int i = 0; i < 15; i++) {
            createAndSaveTestAdapter("test-paging-" + i);
        }

        // When
        Page<InputAdapterEntity> page1 = configManagementService.getAllInputAdapters(PageRequest.of(0, 10));
        Page<InputAdapterEntity> page2 = configManagementService.getAllInputAdapters(PageRequest.of(1, 10));

        // Then
        assertEquals(10, page1.getContent().size());
        assertEquals(5, page2.getContent().size());
        assertEquals(15, page1.getTotalElements());
        assertEquals(2, page1.getTotalPages());
    }

    @Test
    @DisplayName("InputAdapter 조회 - 타입별 조회")
    void testGetInputAdaptersByType() {
        // Given
        createAndSaveTestAdapter("tcp-1", "TcpInputAdapter");
        createAndSaveTestAdapter("tcp-2", "TcpInputAdapter");
        createAndSaveTestAdapter("udp-1", "UdpInputAdapter");

        // When
        List<InputAdapterEntity> tcpAdapters = configManagementService.getInputAdaptersByType("TcpInputAdapter");
        List<InputAdapterEntity> udpAdapters = configManagementService.getInputAdaptersByType("UdpInputAdapter");

        // Then
        assertEquals(2, tcpAdapters.size());
        assertEquals(1, udpAdapters.size());
    }

    @Test
    @DisplayName("InputAdapter 조회 - MessageType으로 조회")
    void testGetInputAdapterByMessageType() {
        // Given
        createAndSaveTestAdapter("unique-message-type");

        // When
        InputAdapterEntity found = configManagementService.getInputAdapterByMessageType("unique-message-type");

        // Then
        assertNotNull(found);
        assertEquals("unique-message-type", found.getMessagetype());
    }

    @Test
    @DisplayName("InputAdapter 조회 - MessageType으로 조회 실패")
    void testGetInputAdapterByMessageType_NotFound() {
        // When & Then
        assertThrows(ConfigNotFoundException.class, () ->
                configManagementService.getInputAdapterByMessageType("non-existent"));
    }

    // ==================== UPDATE 테스트 ====================

    @Test
    @DisplayName("InputAdapter 수정 - 정상 케이스")
    void testUpdateInputAdapter_Success() {
        // Given
        InputAdapterEntity entity = createAndSaveTestAdapter("test-update");
        Long originalId = entity.getId();

        InputAdapterEntity updateData = InputAdapterEntity.builder()
                .type("UdpInputAdapter")
                .messagetype("test-update")
                .host("192.168.1.1")
                .port(9090)
                .enabled(false)
                .build();

        // When
        InputAdapterEntity updated = configManagementService.updateInputAdapter(originalId, updateData);

        // Then
        assertEquals(originalId, updated.getId());
        assertEquals("UdpInputAdapter", updated.getType());
        assertEquals("192.168.1.1", updated.getHost());
        assertEquals(9090, updated.getPort());
        assertFalse(updated.getEnabled());
    }

    @Test
    @DisplayName("InputAdapter 수정 - 존재하지 않는 ID")
    void testUpdateInputAdapter_NotFound() {
        // Given
        InputAdapterEntity updateData = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("test")
                .build();

        // When & Then
        assertThrows(ConfigNotFoundException.class, () ->
                configManagementService.updateInputAdapter(999L, updateData));
    }

    @Test
    @DisplayName("InputAdapter 활성화/비활성화")
    void testEnableDisableInputAdapter() {
        // Given
        InputAdapterEntity entity = createAndSaveTestAdapter("test-enable-disable");
        assertTrue(entity.getEnabled());

        // When - 비활성화
        InputAdapterEntity disabled = configManagementService.disableInputAdapter(entity.getId());

        // Then
        assertFalse(disabled.getEnabled());

        // When - 활성화
        InputAdapterEntity enabled = configManagementService.enableInputAdapter(entity.getId());

        // Then
        assertTrue(enabled.getEnabled());
    }

    @Test
    @DisplayName("활성화된 InputAdapter만 조회")
    void testGetEnabledInputAdapters() {
        // Given
        createAndSaveTestAdapter("enabled-1");
        createAndSaveTestAdapter("enabled-2");
        InputAdapterEntity disabled = createAndSaveTestAdapter("disabled-1");
        configManagementService.disableInputAdapter(disabled.getId());

        // When
        List<InputAdapterEntity> enabledAdapters = configManagementService.getEnabledInputAdapters();

        // Then
        assertEquals(2, enabledAdapters.size());
        assertTrue(enabledAdapters.stream().allMatch(InputAdapterEntity::getEnabled));
    }

    // ==================== DELETE 테스트 ====================

    @Test
    @DisplayName("InputAdapter 삭제 - 정상 케이스")
    void testDeleteInputAdapter_Success() {
        // Given
        InputAdapterEntity entity = createAndSaveTestAdapter("test-delete");
        Long id = entity.getId();
        assertTrue(inputAdapterRepository.existsById(id));

        // When
        configManagementService.deleteInputAdapter(id);

        // Then
        assertFalse(inputAdapterRepository.existsById(id));
    }

    @Test
    @DisplayName("InputAdapter 삭제 - 존재하지 않는 ID")
    void testDeleteInputAdapter_NotFound() {
        // When & Then
        ConfigNotFoundException exception = assertThrows(ConfigNotFoundException.class, () ->
                configManagementService.deleteInputAdapter(999L));

        assertTrue(exception.getMessage().contains("InputAdapter"));
    }

    @Test
    @DisplayName("InputAdapter 삭제 후 재삭제 시도")
    void testDeleteInputAdapter_AlreadyDeleted() {
        // Given
        InputAdapterEntity entity = createAndSaveTestAdapter("test-double-delete");
        Long id = entity.getId();

        // When - 첫 번째 삭제
        configManagementService.deleteInputAdapter(id);

        // Then - 두 번째 삭제 시도 시 예외 발생
        assertThrows(ConfigNotFoundException.class, () ->
                configManagementService.deleteInputAdapter(id));
    }

    // ==================== 헬퍼 메서드 ====================

    private InputAdapterEntity createAndSaveTestAdapter(String messagetype) {
        return createAndSaveTestAdapter(messagetype, "TcpInputAdapter");
    }

    private InputAdapterEntity createAndSaveTestAdapter(String messagetype, String type) {
        InputAdapterEntity entity = InputAdapterEntity.builder()
                .type(type)
                .messagetype(messagetype)
                .host("localhost")
                .port(8080)
                .enabled(true)
                .build();
        return configManagementService.createInputAdapter(entity);
    }
}
