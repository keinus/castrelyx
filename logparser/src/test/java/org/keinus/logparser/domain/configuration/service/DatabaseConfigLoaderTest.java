package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseConfigLoaderTest {

    private ConfigManagementService configManagementService;
    private ConfigValidationService configValidationService;
    private DatabaseConfigLoader databaseConfigLoader;

    @BeforeEach
    void setUp() {
        configManagementService = mock(ConfigManagementService.class);
        configValidationService = mock(ConfigValidationService.class);
        when(configValidationService.validatePipelineIntegrity())
                .thenReturn(new ConfigValidationService.PipelineIntegrityResult(true, List.of(), List.of()));
        databaseConfigLoader = new DatabaseConfigLoader(configManagementService, configValidationService);
    }

    @Test
    void loadConfigurationParsesAndOrdersRuntimeConfiguration() {
        when(configManagementService.getEnabledInputAdapters()).thenReturn(List.of(
                InputAdapterEntity.builder()
                        .id(1L)
                        .type("TcpInputAdapter")
                        .messagetype("access")
                        .host("0.0.0.0")
                        .port(5140)
                        .configParams("{\"targets\":[{\"host\":\"192.0.2.10\"}],\"oids\":[\"1.3.6.1.2.1.1.5.0\"]}")
                        .enabled(true)
                        .build()
        ));
        when(configManagementService.getEnabledOutputAdapters()).thenReturn(List.of(
                OutputAdapterEntity.builder()
                        .id(10L)
                        .type("HttpOutputAdapter")
                        .messagetype(" ")
                        .url("https://example.com/logs")
                        .method("PATCH")
                        .headers("{\"X-Env\":\"test\"}")
                        .tagpass("{\"service\":[\"api\"]}")
                        .addOriginText(true)
                        .enabled(true)
                        .timeoutMs(2500)
                        .build()
        ));
        when(configManagementService.getEnabledParsers()).thenReturn(List.of(
                ParserEntity.builder()
                        .id(2L)
                        .type("RegexParser")
                        .messagetype("access")
                        .param("(late)=(\\\\w+)")
                        .priority(100)
                        .continueOnFailure(false)
                        .build(),
                ParserEntity.builder()
                        .id(1L)
                        .type("RegexParser")
                        .messagetype("access")
                        .param("(early)=(\\\\w+)")
                        .priority(0)
                        .continueOnFailure(true)
                        .build()
        ));
        when(configManagementService.getEnabledTransforms()).thenReturn(List.of(
                TransformEntity.builder()
                        .id(7L)
                        .type("RemoveProperty")
                        .messagetype("access")
                        .priority(50)
                        .removeProperties("[\"debug\"]")
                        .build(),
                TransformEntity.builder()
                        .id(6L)
                        .type("Filter")
                        .messagetype("access")
                        .priority(0)
                        .filterPass("{\"service\":\"api\"}")
                        .build()
        ));
        when(configManagementService.getConfigValue("parser_threads")).thenReturn("12");
        when(configManagementService.getConfigValue("flush_interval")).thenReturn("9000");

        DatabaseConfigLoader.PipelineConfiguration configuration = databaseConfigLoader.loadConfiguration();

        assertEquals(1, configuration.getInput().size());
        assertEquals(
                "{\"targets\":[{\"host\":\"192.0.2.10\"}],\"oids\":[\"1.3.6.1.2.1.1.5.0\"]}",
                configuration.getInput().get(0).getConfigParams()
        );
        assertEquals("all", configuration.getOutput().get(0).getMessagetype());
        assertEquals("PATCH", configuration.getOutput().get(0).getMethod());
        assertEquals(Map.of("X-Env", "test"), configuration.getOutput().get(0).getHeaders());
        assertEquals(Map.of("service", List.of("api")), configuration.getOutput().get(0).getTagpass());
        assertEquals(true, configuration.getOutput().get(0).getAddOriginText());

        assertEquals(List.of(1L, 2L), configuration.getParser().stream().map(parser -> parser.getId()).toList());
        assertEquals(true, configuration.getParser().get(0).getContinueOnFailure());
        assertEquals(false, configuration.getParser().get(1).getContinueOnFailure());

        assertEquals(List.of(6L, 7L), configuration.getTransform().stream().map(transform -> transform.getId()).toList());
        assertEquals(Map.of("service", "api"), configuration.getTransform().get(0).getParam().getPass());
        assertEquals(List.of("debug"), configuration.getTransform().get(1).getParam().getRemove());

        assertEquals(12, configuration.getParserThreads());
        assertEquals(9000L, configuration.getFlushInterval());
    }

    @Test
    void loadConfigurationIgnoresMalformedJsonFields() {
        when(configManagementService.getEnabledInputAdapters()).thenReturn(List.of());
        when(configManagementService.getEnabledOutputAdapters()).thenReturn(List.of(
                OutputAdapterEntity.builder()
                        .id(20L)
                        .type("HttpOutputAdapter")
                        .messagetype("all")
                        .url("http://localhost/logs")
                        .headers("{broken")
                        .tagpass("{broken")
                        .build()
        ));
        when(configManagementService.getEnabledParsers()).thenReturn(List.of());
        when(configManagementService.getEnabledTransforms()).thenReturn(List.of(
                TransformEntity.builder()
                        .id(30L)
                        .type("AddProperty")
                        .messagetype("access")
                        .addProperties("{broken")
                        .build()
        ));
        when(configManagementService.getConfigValue("parser_threads")).thenReturn(null);
        when(configManagementService.getConfigValue("flush_interval")).thenReturn(null);

        DatabaseConfigLoader.PipelineConfiguration configuration = databaseConfigLoader.loadConfiguration();

        assertNull(configuration.getOutput().get(0).getHeaders());
        assertNull(configuration.getOutput().get(0).getTagpass());
        assertNotNull(configuration.getTransform().get(0).getParam());
        assertNull(configuration.getTransform().get(0).getParam().getAdd());
        assertEquals(4, configuration.getParserThreads());
        assertEquals(5000L, configuration.getFlushInterval());
    }
}
