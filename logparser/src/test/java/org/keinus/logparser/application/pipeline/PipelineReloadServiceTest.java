package org.keinus.logparser.application.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineReloadServiceTest {

    private ConfigValidationService validationService;
    private ConfigManagementService configManagementService;
    private DatabaseConfigLoader databaseConfigLoader;
    private ApplicationProperties applicationProperties;
    private MessageDispatcher messageDispatcher;
    private InputAdapterComponent inputAdapterComponent;
    private OutputAdapterComponent outputAdapterComponent;
    private ParseService parseService;
    private TransformService transformService;
    private StructuredTransformService structuredTransformService;
    private PipelineReloadService pipelineReloadService;

    @BeforeEach
    void setUp() {
        validationService = mock(ConfigValidationService.class);
        configManagementService = mock(ConfigManagementService.class);
        databaseConfigLoader = mock(DatabaseConfigLoader.class);
        applicationProperties = mock(ApplicationProperties.class);
        messageDispatcher = mock(MessageDispatcher.class);
        inputAdapterComponent = mock(InputAdapterComponent.class);
        outputAdapterComponent = mock(OutputAdapterComponent.class);
        parseService = mock(ParseService.class);
        transformService = mock(TransformService.class);
        structuredTransformService = mock(StructuredTransformService.class);

        when(validationService.validatePipelineIntegrity())
                .thenReturn(new ConfigValidationService.PipelineIntegrityResult(true, List.of(), List.of()));
        when(messageDispatcher.awaitIdle(30_000)).thenReturn(true);
        when(applicationProperties.snapshot()).thenReturn(new DatabaseConfigLoader.PipelineConfiguration());

        pipelineReloadService = new PipelineReloadService(
                validationService,
                configManagementService,
                databaseConfigLoader,
                applicationProperties,
                messageDispatcher,
                inputAdapterComponent,
                outputAdapterComponent,
                parseService,
                transformService,
                structuredTransformService
        );
    }

    @Test
    void getPipelineStatusUsesSingleInputQueueMetrics() {
        when(configManagementService.getEnabledInputAdapters()).thenReturn(List.of(mock(InputAdapterEntity.class)));
        when(configManagementService.getEnabledParsers()).thenReturn(List.of(mock(ParserEntity.class)));
        when(configManagementService.getEnabledTransforms()).thenReturn(List.of(mock(TransformEntity.class)));
        when(configManagementService.getEnabledOutputAdapters()).thenReturn(List.of(mock(OutputAdapterEntity.class)));
        when(messageDispatcher.getDispatcherMetrics())
                .thenReturn(new MessageDispatcher.DispatcherMetrics(7, 10000, 1, 2, 12.5));

        PipelineReloadService.PipelineStatusInfo status = pipelineReloadService.getPipelineStatus();

        assertEquals(PipelineReloadService.PipelineStatus.RUNNING, status.status());
        assertEquals(1, status.inputAdapterCount());
        assertEquals(1, status.parserCount());
        assertEquals(1, status.transformCount());
        assertEquals(1, status.outputAdapterCount());
        assertEquals(7, status.queueSize());
        assertEquals(10000, status.queueCapacity());
        assertEquals(12.5, status.throughput());
    }

    @Test
    void restartPipelinePerformsRealStopAndStartSequence() {
        DatabaseConfigLoader.PipelineConfiguration configuration = new DatabaseConfigLoader.PipelineConfiguration();
        when(databaseConfigLoader.loadConfiguration()).thenReturn(configuration);

        pipelineReloadService.restartPipeline();

        var order = inOrder(
                inputAdapterComponent,
                messageDispatcher,
                outputAdapterComponent,
                applicationProperties,
                parseService,
                transformService,
                structuredTransformService
        );
        order.verify(inputAdapterComponent).stopPipeline();
        order.verify(messageDispatcher).awaitIdle(30_000);
        order.verify(messageDispatcher).stopProcessingWorkers();
        order.verify(outputAdapterComponent).stopPipeline();
        order.verify(applicationProperties).applyConfiguration(configuration);
        order.verify(parseService).reload(configuration.getParser());
        order.verify(transformService).reload(configuration.getTransform());
        order.verify(structuredTransformService).reload();
        order.verify(outputAdapterComponent).startPipeline();
        order.verify(messageDispatcher).restartWorkersFromCurrentConfiguration();
        order.verify(inputAdapterComponent).startPipeline();
    }

    @Test
    void reloadRollsBackWhenRestartFails() {
        DatabaseConfigLoader.PipelineConfiguration targetConfiguration = new DatabaseConfigLoader.PipelineConfiguration();
        DatabaseConfigLoader.PipelineConfiguration previousConfiguration = new DatabaseConfigLoader.PipelineConfiguration();
        targetConfiguration.setParser(List.of(parserConfig("target")));
        targetConfiguration.setTransform(List.of(transformConfig("target")));
        previousConfiguration.setParser(List.of(parserConfig("previous")));
        previousConfiguration.setTransform(List.of(transformConfig("previous")));
        when(databaseConfigLoader.loadConfiguration()).thenReturn(targetConfiguration);
        when(applicationProperties.snapshot()).thenReturn(previousConfiguration);
        doThrow(new RuntimeException("boom"))
                .doNothing()
                .when(outputAdapterComponent).startPipeline();

        assertThrows(RuntimeException.class, () -> pipelineReloadService.reloadConfiguration());

        verify(applicationProperties).applyConfiguration(targetConfiguration);
        verify(applicationProperties).applyConfiguration(previousConfiguration);
        verify(parseService).reload(previousConfiguration.getParser());
        verify(transformService).reload(previousConfiguration.getTransform());
    }

    private ParserAdapterConfig parserConfig(String messageType) {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setMessagetype(messageType);
        config.setType("RegexParser");
        config.setPriority(0);
        return config;
    }

    private TransformConfig transformConfig(String messageType) {
        TransformConfig config = new TransformConfig();
        config.setMessagetype(messageType);
        config.setType("Filter");
        config.setPriority(0);
        return config;
    }
}
