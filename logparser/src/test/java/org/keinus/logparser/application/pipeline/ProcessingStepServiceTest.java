package org.keinus.logparser.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;

class ProcessingStepServiceTest {
    private ProcessingStepService processingStepService;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = mock(ApplicationProperties.class);
        DatabaseConfigLoader loader = mock(DatabaseConfigLoader.class);
        when(properties.getParser()).thenReturn(List.of());
        when(properties.getTransform()).thenReturn(List.of());
        ParseService parseService = new ParseService(properties, loader);
        TransformService transformService = new TransformService(properties, loader);
        processingStepService = new ProcessingStepService(properties, loader, parseService, transformService);
    }

    @Test
    void executesParserAndTransformInOnePriorityOrderedChain() {
        ParserAdapterConfig firstParser = parser(1L, 10, "(message)=(\\w+)", null, false);
        TransformConfig group = transform(2L, 20, "AddProperty");
        TransformParamConfig groupParam = new TransformParamConfig();
        groupParam.setAdd(Map.of("payload", List.of("message")));
        group.setParam(groupParam);
        ParserAdapterConfig fieldParser = parser(3L, 30, null, "payload", false);
        fieldParser.setType("JsonParser");

        processingStepService.reload(List.of(firstParser, fieldParser), List.of(group));
        LogEvent event = new LogEvent("message=hello", "localhost", "test");

        assertEquals(ProcessingStepService.ProcessingResult.SUCCESS, processingStepService.process(event));
        assertEquals("hello", event.getField("message"));
        assertEquals(Map.of("message", "hello"), event.getField("payload"));
    }

    @Test
    void parserFailureCanContinueToTheNextProcessingStep() {
        ParserAdapterConfig missing = parser(1L, 10, "(never)=(\\w+)", "missing", true);
        ParserAdapterConfig succeeding = parser(2L, 20, "(key)=(\\w+)", null, false);

        processingStepService.reload(List.of(missing, succeeding), List.of());
        LogEvent event = new LogEvent("key=value", "localhost", "test");

        assertEquals(ProcessingStepService.ProcessingResult.SUCCESS, processingStepService.process(event));
        assertEquals("value", event.getField("key"));
        assertFalse(event.hasError());
    }

    @Test
    void transformDropStopsFollowingSteps() {
        TransformConfig filter = transform(1L, 10, "Filter");
        TransformParamConfig filterParam = new TransformParamConfig();
        filterParam.setPass(Map.of("must_exist", "yes"));
        filter.setParam(filterParam);
        ParserAdapterConfig parser = parser(2L, 20, "(key)=(\\w+)", null, false);

        processingStepService.reload(List.of(parser), List.of(filter));
        LogEvent event = new LogEvent("key=value", "localhost", "test");

        assertEquals(ProcessingStepService.ProcessingResult.FILTERED, processingStepService.process(event));
        assertTrue(event.getFields().isEmpty());
    }

    private ParserAdapterConfig parser(Long id, int priority, String pattern, String sourceField, boolean continueOnFailure) {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setId(id);
        config.setType("RegexParser");
        config.setMessagetype("test");
        config.setParam(pattern);
        config.setSourceField(sourceField);
        config.setPriority(priority);
        config.setContinueOnFailure(continueOnFailure);
        return config;
    }

    private TransformConfig transform(Long id, int priority, String type) {
        TransformConfig config = new TransformConfig();
        config.setId(id);
        config.setType(type);
        config.setMessagetype("test");
        config.setPriority(priority);
        return config;
    }
}
