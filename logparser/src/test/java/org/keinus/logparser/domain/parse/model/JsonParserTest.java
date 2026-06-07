package org.keinus.logparser.domain.parse.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

class JsonParserTest {

    private JsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonParser();
        parser.init(null);
    }

    @Test
    void testParseValidJson() {
        LogEvent event = new LogEvent("{\"foo\": \"bar\", \"count\": 123}");
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals("bar", fields.get("foo"));
        assertEquals(123, fields.get("count"));
    }

    @Test
    void testParseInvalidJson() {
        LogEvent event = new LogEvent("invalid json");
        boolean result = parser.parse(event);

        assertFalse(result);
        assertTrue(event.getOriginalText().contains("invalid json"));
        // Depending on LogEvent implementation, markAsError might set some fields or flags.
    }

    @Test
    void testParseEmptyJson() {
        LogEvent event = new LogEvent("{}");
        boolean result = parser.parse(event);

        assertFalse(result);
    }

    @Test
    void testParseNullJson() {
        LogEvent event = new LogEvent(null);
        boolean result = parser.parse(event);

        assertFalse(result);
    }
}
