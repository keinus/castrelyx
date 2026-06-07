package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.domain.event.InputAdapterChangedEvent;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.*;
import org.keinus.logparser.interfaces.exception.ConfigNotFoundException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigManagementServiceTest {

    @Mock private InputAdapterRepository inputAdapterRepository;
    @Mock private ParserRepository parserRepository;
    @Mock private TransformRepository transformRepository;
    @Mock private OutputAdapterRepository outputAdapterRepository;
    @Mock private ConfigSettingsRepository configSettingsRepository;
    @Mock private MappingRepository mappingRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConfigValidationService validationService;

    @InjectMocks
    private ConfigManagementService service;

    private InputAdapterEntity inputEntity;

    @BeforeEach
    void setUp() {
        inputEntity = new InputAdapterEntity();
        inputEntity.setId(1L);
        inputEntity.setType("TcpInputAdapter");
        inputEntity.setMessagetype("test");
        inputEntity.setPort(5514);
        inputEntity.setEnabled(true);

        lenient().when(validationService.validateInputAdapter(any()))
                .thenReturn(new ConfigValidationService.ValidationResult(true, List.of()));
        lenient().when(validationService.validateParser(any()))
                .thenReturn(new ConfigValidationService.ValidationResult(true, List.of()));
        lenient().when(validationService.validateTransform(any()))
                .thenReturn(new ConfigValidationService.ValidationResult(true, List.of()));
        lenient().when(validationService.validateOutputAdapter(any()))
                .thenReturn(new ConfigValidationService.ValidationResult(true, List.of()));
        lenient().when(mappingRepository.findAll()).thenReturn(Collections.emptyList());
    }

    @Test
    void testCreateInputAdapter() {
        when(inputAdapterRepository.save(any())).thenReturn(inputEntity);
        
        InputAdapterEntity saved = service.createInputAdapter(inputEntity);
        
        assertNotNull(saved);
        verify(inputAdapterRepository).save(inputEntity);
        verify(eventPublisher).publishEvent(any(InputAdapterChangedEvent.class));
    }

    @Test
    void createInputAdapterPublishesConfigParams() {
        inputEntity.setType("SnmpInputAdapter");
        inputEntity.setConfigParams("{\"targets\":[{\"host\":\"192.0.2.10\"}],\"oids\":[\"1.3.6.1.2.1.1.5.0\"]}");
        when(inputAdapterRepository.save(any())).thenReturn(inputEntity);

        service.createInputAdapter(inputEntity);

        ArgumentCaptor<InputAdapterChangedEvent> eventCaptor = ArgumentCaptor.forClass(InputAdapterChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(inputEntity.getConfigParams(), eventCaptor.getValue().getConfig().getConfigParams());
    }

    @Test
    void testGetInputAdapter() {
        when(inputAdapterRepository.findById(1L)).thenReturn(Optional.of(inputEntity));
        
        InputAdapterEntity found = service.getInputAdapter(1L);
        
        assertEquals(inputEntity, found);
    }

    @Test
    void testGetInputAdapterNotFound() {
        when(inputAdapterRepository.findById(1L)).thenReturn(Optional.empty());
        
        assertThrows(ConfigNotFoundException.class, () -> service.getInputAdapter(1L));
    }

    @Test
    void testUpdateInputAdapter() {
        when(inputAdapterRepository.findById(1L)).thenReturn(Optional.of(inputEntity));
        when(inputAdapterRepository.save(any())).thenReturn(inputEntity);
        
        InputAdapterEntity updated = service.updateInputAdapter(1L, inputEntity);
        
        assertNotNull(updated);
        verify(inputAdapterRepository).save(inputEntity);
    }

    @Test
    void testDeleteInputAdapter() {
        when(inputAdapterRepository.existsById(1L)).thenReturn(true);
        
        service.deleteInputAdapter(1L);
        
        verify(inputAdapterRepository).deleteById(1L);
        verify(eventPublisher).publishEvent(any(InputAdapterChangedEvent.class));
    }

    @Test
    void testGetAllInputAdapters() {
        Page<InputAdapterEntity> page = new PageImpl<>(Collections.singletonList(inputEntity));
        when(inputAdapterRepository.findAll(any(Pageable.class))).thenReturn(page);
        
        Page<InputAdapterEntity> result = service.getAllInputAdapters(Pageable.unpaged());
        
        assertFalse(result.isEmpty());
    }

    @Test
    void testCreateParser() {
        ParserEntity parser = new ParserEntity();
        parser.setId(2L);
        parser.setType("GrokParser");
        parser.setMessagetype("test");
        when(parserRepository.save(any())).thenReturn(parser);
        
        ParserEntity saved = service.createParser(parser);
        
        assertNotNull(saved);
        verify(parserRepository).save(parser);
    }

    @Test
    void testCreateTransform() {
        TransformEntity transform = new TransformEntity();
        transform.setId(3L);
        transform.setType("Filter");
        transform.setMessagetype("test");
        when(transformRepository.save(any())).thenReturn(transform);
        
        TransformEntity saved = service.createTransform(transform);
        
        assertNotNull(saved);
        verify(transformRepository).save(transform);
    }

    @Test
    void testCreateOutputAdapter() {
        OutputAdapterEntity output = new OutputAdapterEntity();
        output.setId(4L);
        output.setType("ConsoleOutputAdapter");
        output.setMessagetype("test");
        when(outputAdapterRepository.save(any())).thenReturn(output);
        
        OutputAdapterEntity saved = service.createOutputAdapter(output);
        
        assertNotNull(saved);
        verify(outputAdapterRepository).save(output);
    }

    @Test
    void testGetPipelineTopologyFull() {
        when(inputAdapterRepository.findAll()).thenReturn(Collections.singletonList(inputEntity));
        
        ParserEntity parser = new ParserEntity();
        parser.setMessagetype("test");
        parser.setType("GrokParser");
        parser.setEnabled(true);
        when(parserRepository.findAll()).thenReturn(Collections.singletonList(parser));
        
        TransformEntity transform = new TransformEntity();
        transform.setMessagetype("test");
        transform.setType("Filter");
        transform.setEnabled(true);
        when(transformRepository.findAll()).thenReturn(Collections.singletonList(transform));
        
        OutputAdapterEntity output = new OutputAdapterEntity();
        output.setMessagetype("test");
        output.setType("ConsoleOutputAdapter");
        output.setEnabled(true);
        when(outputAdapterRepository.findAll()).thenReturn(Collections.singletonList(output));
        
        var topology = service.getPipelineTopology();
        
        assertEquals(1, topology.size());
        assertEquals(1, topology.get(0).getInputs().size());
        assertEquals(2, topology.get(0).getProcessing().size());
        assertEquals(1, topology.get(0).getOutputs().size());
    }

    @Test
    void testGetPipelineTopologyIncludesSchemaMap() {
        when(inputAdapterRepository.findAll()).thenReturn(Collections.singletonList(inputEntity));

        MappingConfiguration mapping = new MappingConfiguration();
        mapping.setMessageType("test");
        when(mappingRepository.findAll()).thenReturn(Collections.singletonList(mapping));

        var topology = service.getPipelineTopology();

        assertEquals(1, topology.size());
        assertTrue(topology.get(0).getProcessing().stream()
                .anyMatch(stage -> "SCHEMA".equals(stage.getBadge())
                        && "Schema Map".equals(stage.getName())));
    }

}
