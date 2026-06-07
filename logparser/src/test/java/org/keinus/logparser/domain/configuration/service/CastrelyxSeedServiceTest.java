package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.ConfigSettingsRepository;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.OutputAdapterRepository;
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

    @Test
    void createsDisabledInputAndOutputRowsWhenMissing() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(inputAdapterRepository.findByType("TcpMtlsGzipInputAdapter")).thenReturn(List.of());
        when(outputAdapterRepository.findByType("MariaDbOutputAdapter")).thenReturn(List.of());

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository
        );

        service.seedDefaults();

        ArgumentCaptor<InputAdapterEntity> inputCaptor = ArgumentCaptor.forClass(InputAdapterEntity.class);
        verify(inputAdapterRepository).save(inputCaptor.capture());
        assertEquals("TcpMtlsGzipInputAdapter", inputCaptor.getValue().getType());
        assertEquals("castrelyx-agent-item", inputCaptor.getValue().getMessagetype());
        assertEquals(9443, inputCaptor.getValue().getPort());
        assertFalse(inputCaptor.getValue().getEnabled());
        assertTrue(inputCaptor.getValue().getConfigParams().contains("LOGPARSER_KEYSTORE_PASSWORD"));

        ArgumentCaptor<OutputAdapterEntity> outputCaptor = ArgumentCaptor.forClass(OutputAdapterEntity.class);
        verify(outputAdapterRepository).save(outputCaptor.capture());
        assertEquals("MariaDbOutputAdapter", outputCaptor.getValue().getType());
        assertEquals("castrelyx-agent-item", outputCaptor.getValue().getMessagetype());
        assertFalse(outputCaptor.getValue().getEnabled());
        assertTrue(outputCaptor.getValue().getConfigParams().contains("CASTRELYX_DB_PASSWORD"));

        verify(configSettingsRepository).save(any(ConfigSettingsEntity.class));
    }

    @Test
    void doesNotOverwriteExistingRows() {
        when(configSettingsRepository.findByConfigKey(CastrelyxSeedService.SEED_MARKER_KEY)).thenReturn(Optional.empty());
        when(inputAdapterRepository.findByType("TcpMtlsGzipInputAdapter"))
                .thenReturn(List.of(InputAdapterEntity.builder().id(1L).build()));
        when(outputAdapterRepository.findByType("MariaDbOutputAdapter"))
                .thenReturn(List.of(OutputAdapterEntity.builder().id(2L).build()));

        CastrelyxSeedService service = new CastrelyxSeedService(
                inputAdapterRepository,
                outputAdapterRepository,
                configSettingsRepository
        );

        service.seedDefaults();

        verify(inputAdapterRepository, never()).save(any(InputAdapterEntity.class));
        verify(outputAdapterRepository, never()).save(any(OutputAdapterEntity.class));
        verify(configSettingsRepository).save(any(ConfigSettingsEntity.class));
    }
}
