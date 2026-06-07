package org.keinus.logparser.domain.transformation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransformServiceTest {

    private TransformService transformService;

    @BeforeEach
    void setUp() {
        ApplicationProperties applicationProperties = mock(ApplicationProperties.class);
        DatabaseConfigLoader databaseConfigLoader = mock(DatabaseConfigLoader.class);
        when(applicationProperties.getTransform()).thenReturn(List.of(addPropertyTransform()));

        transformService = new TransformService(applicationProperties, databaseConfigLoader);
    }

    @Test
    void loadsTransformsFromConfiguredPackage() {
        LogEvent event = new LogEvent("raw", "localhost", "test");
        event.setField("service", "api");

        boolean transformed = transformService.transform(event);

        assertTrue(transformed);
        Map<?, ?> environment = assertInstanceOf(Map.class, event.getField("environment"));
        assertEquals("api", environment.get("service"));
    }

    @Test
    void ignoresLegacyStructureTransformBecauseStructuredTransformRunsCentrally() {
        TransformConfig structureTransform = new TransformConfig();
        structureTransform.setId(2L);
        structureTransform.setType("Structure");
        structureTransform.setMessagetype("test");
        structureTransform.setPriority(0);

        transformService.reload(List.of(structureTransform, addPropertyTransform()));

        LogEvent event = new LogEvent("raw", "localhost", "test");
        event.setField("service", "api");

        boolean transformed = transformService.transform(event);

        assertTrue(transformed);
        Map<?, ?> environment = assertInstanceOf(Map.class, event.getField("environment"));
        assertEquals("api", environment.get("service"));
    }

    private TransformConfig addPropertyTransform() {
        TransformParamConfig param = new TransformParamConfig();
        param.setAdd(Map.of("environment", List.of("service")));

        TransformConfig config = new TransformConfig();
        config.setId(1L);
        config.setType("AddProperty");
        config.setMessagetype("test");
        config.setPriority(0);
        config.setParam(param);
        return config;
    }
}
