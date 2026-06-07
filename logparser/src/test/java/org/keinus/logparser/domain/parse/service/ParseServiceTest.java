package org.keinus.logparser.domain.parse.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;

class ParseServiceTest {

    private ParseService parseService;
    private ApplicationProperties applicationProperties;
    private DatabaseConfigLoader databaseConfigLoader;

    @BeforeEach
    void setUp() {
        applicationProperties = mock(ApplicationProperties.class);
        databaseConfigLoader = mock(DatabaseConfigLoader.class);
        when(applicationProperties.getParser()).thenReturn(new ArrayList<>());
        
        parseService = new ParseService(applicationProperties, databaseConfigLoader);
    }

    @Test
    void testTestParser_Grok_Success() {
        String parserType = "GrokParser";
        String pattern = "%{COMMONAPACHELOG}";
        String sampleData = "127.0.0.1 - - [28/Jul/2006:10:27:10 -0300] \"GET /cgi-bin/try/ HTTP/1.0\" 200 3395";

        Map<String, Object> result = parseService.testParser(parserType, pattern, sampleData);

        assertNotNull(result);
        assertEquals("127.0.0.1", result.get("clientip"));
        assertEquals("GET", result.get("verb"));
        assertEquals("200", result.get("response"));
    }

    @Test
    void testTestParser_Grok_NoMatch() {
        String parserType = "GrokParser";
        String pattern = "%{COMMONAPACHELOG}";
        String sampleData = "Invalid Data";

        Map<String, Object> result = parseService.testParser(parserType, pattern, sampleData);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testTestParser_Regex_Success() {
        String parserType = "RegexParser";
        String pattern = "(\\w+)=(\\w+)";
        String sampleData = "key1=value1 key2=value2";

        Map<String, Object> result = parseService.testParser(parserType, pattern, sampleData);

        assertNotNull(result);
        assertEquals("value1", result.get("key1"));
        assertEquals("value2", result.get("key2"));
    }

    @Test
    void parseUsesPriorityOrder() {
        parseService.reload(List.of(
                parserConfig(2L, 10, false, "(alpha)=(\\w+)"),
                parserConfig(1L, 0, false, "(beta)=(\\w+)")
        ));

        LogEvent event = new LogEvent("alpha=one beta=two", "localhost", "test");

        boolean parsed = parseService.parse(event);

        assertTrue(parsed);
        assertEquals("two", event.getField("beta"));
        assertEquals(null, event.getField("alpha"));
    }

    @Test
    void parseContinuesWhenContinueOnFailureEnabled() {
        parseService.reload(List.of(
                parserConfig(1L, 0, true, "(missing)=(\\w+)"),
                parserConfig(2L, 1, false, "(\\w+)=(\\w+)")
        ));

        LogEvent event = new LogEvent("key=value", "localhost", "test");

        boolean parsed = parseService.parse(event);

        assertTrue(parsed);
        assertEquals("value", event.getField("key"));
    }

    @Test
    void parseStopsWhenContinueOnFailureDisabled() {
        parseService.reload(List.of(
                parserConfig(1L, 0, false, "(missing)=(\\w+)"),
                parserConfig(2L, 1, false, "(\\w+)=(\\w+)")
        ));

        LogEvent event = new LogEvent("key=value", "localhost", "test");

        boolean parsed = parseService.parse(event);

        assertFalse(parsed);
        assertTrue(event.getFields().isEmpty());
    }

    private ParserAdapterConfig parserConfig(Long id, int priority, boolean continueOnFailure, String pattern) {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setId(id);
        config.setType("RegexParser");
        config.setMessagetype("test");
        config.setParam(pattern);
        config.setPriority(priority);
        config.setContinueOnFailure(continueOnFailure);
        return config;
    }
}
