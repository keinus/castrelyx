package org.keinus.logparser.domain.parse.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

class RegexParserTest {

    private RegexParser parser;

    @BeforeEach
    void setUp() {
        parser = new RegexParser();
    }

    @Test
    void testParseValidRegex() {
        parser.init("(\\w+)=(\\w+)");
        LogEvent event = new LogEvent("key1=val1 key2=val2");
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals("val1", fields.get("key1"));
        assertEquals("val2", fields.get("key2"));
    }

    @Test
    void testParseInvalidPattern() {
        parser.init("(\\w+)"); // Only one group
        LogEvent event = new LogEvent("key1");
        boolean result = parser.parse(event);

        assertFalse(result);
    }

    @Test
    void testParseNoMatch() {
        parser.init("(\\w+)=(\\w+)");
        LogEvent event = new LogEvent("invalid data");
        boolean result = parser.parse(event);

        assertFalse(result);
    }

    @Test
    void testParseWithException() {
        parser.init("(\\w+)=(\\w+)");
        LogEvent event = new LogEvent(null);
        boolean result = parser.parse(event);

        assertFalse(result);
    }
}
