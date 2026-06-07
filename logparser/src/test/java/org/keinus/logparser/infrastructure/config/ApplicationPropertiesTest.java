package org.keinus.logparser.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.service.ConfigValidator;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationPropertiesTest {

    private ConfigValidator configValidator;
    private DatabaseConfigLoader databaseConfigLoader;
    private ApplicationProperties applicationProperties;

    @BeforeEach
    void setUp() {
        configValidator = mock(ConfigValidator.class);
        databaseConfigLoader = mock(DatabaseConfigLoader.class);
        applicationProperties = new ApplicationProperties(configValidator, databaseConfigLoader);
    }

    @Test
    void applyConfigurationCopiesListContainers() {
        DatabaseConfigLoader.PipelineConfiguration configuration = sampleConfiguration();

        applicationProperties.applyConfiguration(configuration);
        configuration.getInput().clear();
        configuration.getOutput().clear();
        configuration.getParser().clear();
        configuration.getTransform().clear();

        assertEquals(1, applicationProperties.getInput().size());
        assertEquals(1, applicationProperties.getOutput().size());
        assertEquals(1, applicationProperties.getParser().size());
        assertEquals(1, applicationProperties.getTransform().size());
        assertEquals(8, applicationProperties.getParserThreads());
        assertEquals(1500L, applicationProperties.getFlushInterval());
    }

    @Test
    void snapshotReturnsDetachedLists() {
        applicationProperties.applyConfiguration(sampleConfiguration());

        DatabaseConfigLoader.PipelineConfiguration snapshot = applicationProperties.snapshot();
        snapshot.getInput().clear();
        snapshot.getOutput().clear();
        snapshot.getParser().clear();
        snapshot.getTransform().clear();

        assertEquals(1, applicationProperties.getInput().size());
        assertEquals(1, applicationProperties.getOutput().size());
        assertEquals(1, applicationProperties.getParser().size());
        assertEquals(1, applicationProperties.getTransform().size());
        assertNotSame(snapshot.getInput(), applicationProperties.getInput());
        assertNotSame(snapshot.getOutput(), applicationProperties.getOutput());
    }

    @Test
    void loadConfigurationFromDatabaseFallsBackToEmptyConfigurationWhenLoadingFails() {
        when(databaseConfigLoader.loadConfiguration()).thenThrow(new IllegalStateException("broken"));

        applicationProperties.loadConfigurationFromDatabase();

        assertTrue(applicationProperties.getInput().isEmpty());
        assertTrue(applicationProperties.getOutput().isEmpty());
        assertTrue(applicationProperties.getParser().isEmpty());
        assertTrue(applicationProperties.getTransform().isEmpty());
        assertEquals(4, applicationProperties.getParserThreads());
        assertEquals(5000L, applicationProperties.getFlushInterval());
    }

    private DatabaseConfigLoader.PipelineConfiguration sampleConfiguration() {
        DatabaseConfigLoader.PipelineConfiguration configuration = new DatabaseConfigLoader.PipelineConfiguration();

        InputAdapterConfig input = new InputAdapterConfig();
        input.setId(1L);
        input.setType("TcpInputAdapter");
        input.setMessagetype("access");

        OutputAdapterConfig output = new OutputAdapterConfig();
        output.setId(2L);
        output.setType("ConsoleOutputAdapter");
        output.setMessagetype("all");

        ParserAdapterConfig parser = new ParserAdapterConfig();
        parser.setId(3L);
        parser.setType("RegexParser");
        parser.setMessagetype("access");

        TransformConfig transform = new TransformConfig();
        transform.setId(4L);
        transform.setType("Filter");
        transform.setMessagetype("access");

        configuration.setInput(new ArrayList<>(List.of(input)));
        configuration.setOutput(new ArrayList<>(List.of(output)));
        configuration.setParser(new ArrayList<>(List.of(parser)));
        configuration.setTransform(new ArrayList<>(List.of(transform)));
        configuration.setParserThreads(8);
        configuration.setFlushInterval(1500L);
        return configuration;
    }
}
