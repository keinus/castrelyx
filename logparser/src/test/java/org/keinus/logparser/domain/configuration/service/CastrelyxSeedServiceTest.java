package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.ConfigSettingsRepository;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.infrastructure.persistence.repository.OutputAdapterRepository;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.SubTableRule;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CastrelyxSeedServiceTest {
    @Mock private InputAdapterRepository inputAdapterRepository;
    @Mock private OutputAdapterRepository outputAdapterRepository;
    @Mock private ConfigSettingsRepository configSettingsRepository;
    @Mock private MappingRepository mappingRepository;

    @Test
    void createsEnabledAgentInputAndClickHouseOutputRowsWhenMissing() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(inputAdapterRepository.findByType("TcpMtlsGzipInputAdapter")).thenReturn(List.of());
        when(outputAdapterRepository.findByType("ClickHouseOutputAdapter")).thenReturn(List.of());
        when(mappingRepository.findByMessageType("castrelyx-agent-item")).thenReturn(Optional.empty());

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        ArgumentCaptor<InputAdapterEntity> inputCaptor = ArgumentCaptor.forClass(InputAdapterEntity.class);
        verify(inputAdapterRepository).save(inputCaptor.capture());
        assertEquals("TcpMtlsGzipInputAdapter", inputCaptor.getValue().getType());
        assertEquals("castrelyx-agent-item", inputCaptor.getValue().getMessagetype());
        assertEquals(9443, inputCaptor.getValue().getPort());
        assertTrue(inputCaptor.getValue().getEnabled());
        assertTrue(inputCaptor.getValue().getConfigParams().contains("/var/lib/castrelsign/certs/server.p12"));
        assertTrue(inputCaptor.getValue().getConfigParams().contains("CASTRELSIGN_KEYSTORE_PASSWORD"));
        assertTrue(inputCaptor.getValue().getConfigParams().contains("\"maxConnections\":32"));
        assertTrue(inputCaptor.getValue().getConfigParams().contains("\"tlsReloadIntervalMs\":5000"));

        ArgumentCaptor<OutputAdapterEntity> outputCaptor = ArgumentCaptor.forClass(OutputAdapterEntity.class);
        verify(outputAdapterRepository).save(outputCaptor.capture());
        assertEquals("ClickHouseOutputAdapter", outputCaptor.getValue().getType());
        assertEquals("castrelyx-agent-item", outputCaptor.getValue().getMessagetype());
        assertTrue(outputCaptor.getValue().getEnabled());
        assertTrue(outputCaptor.getValue().getConfigParams().contains("http://clickhouse:8123"));
        assertTrue(outputCaptor.getValue().getConfigParams().contains("CLICKHOUSE_PASSWORD"));
        assertTrue(outputCaptor.getValue().getConfigParams().contains("\"maxPendingBytes\":67108864"));
        assertTrue(outputCaptor.getValue().getConfigParams().contains("\"maxIncompleteChunkDlqRecords\":1000"));

        verify(mappingRepository).save(any(MappingConfiguration.class));
        verify(configSettingsRepository, times(2)).save(any(ConfigSettingsEntity.class));
    }

    @Test
    void preservesExistingInputSettingsWhileAddingSafetyDefaultsAndMissingOutput() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY)).thenReturn(Optional.of(ConfigSettingsEntity.builder().build()));
        InputAdapterEntity existingInput = InputAdapterEntity.builder()
                .id(1L)
                .type("TcpMtlsGzipInputAdapter")
                .messagetype("castrelyx-agent-item")
                .host("127.0.0.1")
                .port(9555)
                .enabled(false)
                .configParams("""
                        {"keyStorePath":"/custom/server.p12","trustStorePath":"/custom/trust.p12","maxConnections":7}
                        """)
                .build();
        when(inputAdapterRepository.findByType("TcpMtlsGzipInputAdapter"))
                .thenReturn(List.of(existingInput));
        when(outputAdapterRepository.findByType("ClickHouseOutputAdapter")).thenReturn(List.of());
        when(mappingRepository.findByMessageType("castrelyx-agent-item"))
                .thenReturn(Optional.of(new MappingConfiguration()));

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        verify(inputAdapterRepository).save(argThat(entity ->
                entity.getId().equals(1L)
                        && !entity.getEnabled()
                        && entity.getPort().equals(9555)
                        && "127.0.0.1".equals(entity.getHost())
                        && entity.getConfigParams().contains("/custom/server.p12")
                        && entity.getConfigParams().contains("\"maxConnections\":7")
                        && entity.getConfigParams().contains("\"tlsReloadIntervalMs\":5000")));
        verify(outputAdapterRepository).save(argThat(entity ->
                "ClickHouseOutputAdapter".equals(entity.getType())
                        && entity.getEnabled()
                        && entity.getConfigParams().contains("\"database\":\"castrelyx\"")));
        verify(configSettingsRepository).save(any(ConfigSettingsEntity.class));
        verify(mappingRepository, never()).save(any(MappingConfiguration.class));
    }

    @Test
    void upgradesVersionThreeSeedWithoutReplacingOperatorAdapterSettings() {
        ConfigSettingsEntity existingMarker = ConfigSettingsEntity.builder()
                .id(99L)
                .configKey(CastrelyxSeedService.SEED_MARKER_KEY)
                .configValue("3")
                .dataType("STRING")
                .build();
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY))
                .thenReturn(Optional.of(existingMarker));
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY))
                .thenReturn(Optional.of(ConfigSettingsEntity.builder().build()));
        when(inputAdapterRepository.findByType("TcpMtlsGzipInputAdapter"))
                .thenReturn(List.of(InputAdapterEntity.builder()
                        .id(1L)
                        .type("TcpMtlsGzipInputAdapter")
                        .messagetype("castrelyx-agent-item")
                        .host("10.0.0.5")
                        .port(10443)
                        .enabled(false)
                        .configParams("{\"keyStorePath\":\"/operator/server.p12\",\"maxConnections\":5}")
                        .build()));
        when(outputAdapterRepository.findByType("ClickHouseOutputAdapter"))
                .thenReturn(List.of(OutputAdapterEntity.builder()
                        .id(2L)
                        .type("ClickHouseOutputAdapter")
                        .messagetype("castrelyx-agent-item")
                        .enabled(false)
                        .configParams("{\"endpointUrl\":\"https://operator-clickhouse:8443\",\"database\":\"operator_db\",\"maxPendingBytes\":123456}")
                        .build()));
        when(mappingRepository.findByMessageType("castrelyx-agent-item"))
                .thenReturn(Optional.of(new MappingConfiguration()));

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        verify(inputAdapterRepository).save(argThat(entity ->
                Long.valueOf(1L).equals(entity.getId())
                        && !entity.getEnabled()
                        && entity.getPort().equals(10443)
                        && "10.0.0.5".equals(entity.getHost())
                        && entity.getConfigParams().contains("/operator/server.p12")
                        && entity.getConfigParams().contains("\"maxConnections\":5")
                        && entity.getConfigParams().contains("\"tlsReloadIntervalMs\":5000")));
        verify(outputAdapterRepository).save(argThat(entity ->
                Long.valueOf(2L).equals(entity.getId())
                        && !entity.getEnabled()
                        && entity.getConfigParams().contains("https://operator-clickhouse:8443")
                        && entity.getConfigParams().contains("\"database\":\"operator_db\"")
                        && entity.getConfigParams().contains("\"maxPendingBytes\":123456")
                        && entity.getConfigParams().contains("\"incompleteChunkDlqDir\":")
                        && entity.getConfigParams().contains("\"maxIncompleteChunkDlqRecords\":1000")));
        verify(configSettingsRepository).save(argThat(entity ->
                Long.valueOf(99L).equals(entity.getId())
                        && CastrelyxSeedService.SEED_MARKER_KEY.equals(entity.getConfigKey())
                        && "4".equals(entity.getConfigValue())));
    }

    @Test
    void seedsDefaultAgentMappingWhenMarkerExistsButMappingIsMissing() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY))
                .thenReturn(Optional.of(ConfigSettingsEntity.builder().configValue("4").build()));
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY))
                .thenReturn(Optional.of(ConfigSettingsEntity.builder().build()));
        when(mappingRepository.findByMessageType("castrelyx-agent-item")).thenReturn(Optional.empty());

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        verify(mappingRepository).save(any(MappingConfiguration.class));
        verify(configSettingsRepository, never()).save(argThat(entity ->
                CastrelyxSeedService.MAPPING_SEED_MARKER_KEY.equals(entity.getConfigKey())));
    }

    @Test
    void seedsDefaultAgentMappingWhenMissing() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY))
                .thenReturn(Optional.of(ConfigSettingsEntity.builder().configValue("4").build()));
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(mappingRepository.findByMessageType("castrelyx-agent-item")).thenReturn(Optional.empty());

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        ArgumentCaptor<MappingConfiguration> mappingCaptor = ArgumentCaptor.forClass(MappingConfiguration.class);
        verify(mappingRepository).save(mappingCaptor.capture());

        MappingConfiguration mapping = mappingCaptor.getValue();
        assertEquals("castrelyx-agent-item-v1", mapping.getId());
        assertEquals("castrelyx-agent-item", mapping.getMessageType());
        assertTrue(hasCommonMapping(mapping, "observed_at", "event_time"));
        assertTrue(hasCommonMapping(mapping, "payload_observed_at", "event_time"));
        assertTrue(hasCommonMapping(mapping, "item_kind", "event_category"));
        assertTrue(hasCommonMapping(mapping, "item_type", "event_type"));
        assertTrue(hasCommonMapping(mapping, "item_key", "event_action"));
        assertTrue(hasCommonMapping(mapping, "payload_action", "event_action"));
        assertTrue(hasCommonMapping(mapping, "payload_outcome", "event_result"));
        assertTrue(hasCommonMapping(mapping, "source_id", "src_host"));
        assertTrue(hasCommonMapping(mapping, "source", "log_source"));
        assertTrue(hasNetworkRule(mapping, "payload_value", "bytes_in"));
        assertTrue(hasNetworkRule(mapping, "payload_value", "bytes_out"));

        verify(configSettingsRepository).save(argThat(entity ->
                CastrelyxSeedService.MAPPING_SEED_MARKER_KEY.equals(entity.getConfigKey())));
        verify(inputAdapterRepository, never()).save(any(InputAdapterEntity.class));
        verify(outputAdapterRepository, never()).save(any(OutputAdapterEntity.class));
    }

    @Test
    void doesNotOverwriteExistingAgentMapping() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY))
                .thenReturn(Optional.of(ConfigSettingsEntity.builder().configValue("4").build()));
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.MAPPING_SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(mappingRepository.findByMessageType("castrelyx-agent-item"))
                .thenReturn(Optional.of(new MappingConfiguration()));

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository,
                mappingRepository
        );

        service.seedDefaults();

        verify(mappingRepository, never()).save(any(MappingConfiguration.class));
        verify(configSettingsRepository).save(argThat(entity ->
                CastrelyxSeedService.MAPPING_SEED_MARKER_KEY.equals(entity.getConfigKey())));
    }

    private boolean hasCommonMapping(MappingConfiguration mapping, String sourceField, String targetField) {
        return mapping.getCommonMappings().stream()
                .anyMatch(field -> sourceField.equals(field.getSourceField())
                        && targetField.equals(field.getTargetField()));
    }

    private boolean hasNetworkRule(MappingConfiguration mapping, String sourceField, String targetField) {
        return mapping.getSubTableRules().stream()
                .flatMap(rule -> rule.getMappings().stream())
                .anyMatch(field -> sourceField.equals(field.getSourceField())
                        && targetField.equals(field.getTargetField()));
    }
}
