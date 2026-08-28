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
    void namedCapturesBecomeFields() {
        parser.init("^\\[(?<sdid>\\w+)@(?<id>\\d+)\\s+(?<attributes>.*)\\]$");
        LogEvent event = new LogEvent("[exampleSDID@32473 iut=\"3\" eventSource=\"Application\"]");

        assertTrue(parser.parse(event));
        assertEquals("exampleSDID", event.getField("sdid"));
        assertEquals("32473", event.getField("id"));
        assertEquals("iut=\"3\" eventSource=\"Application\"", event.getField("attributes"));
        assertEquals("3", event.getField("iut"));
        assertEquals("Application", event.getField("eventSource"));
    }

    @Test
    void singleNamedGroupWorksAndUnmatchedOptionalGroupsAreOmitted() {
        parser.init("(?<level>INFO)(?: (?<detail>\\w+))?");
        LogEvent event = new LogEvent("INFO");

        assertTrue(parser.parse(event));
        assertEquals(Map.of("level", "INFO"), event.getFields());
    }

    @Test
    void namedGroupsIgnoreUnnamedGroupsAndUseLastMatchingValue() {
        parser.init("(level)=(?<severity>\\w+)");
        LogEvent event = new LogEvent("level=INFO level=WARN");

        assertTrue(parser.parse(event));
        assertEquals(Map.of("severity", "WARN"), event.getFields());
    }

    @Test
    void quotedGroupSyntaxDoesNotCreateNamedFields() {
        parser.init("\\Q(?<literal>)\\E(\\w+)=(\\w+)");
        LogEvent event = new LogEvent("(?<literal>)key=value");

        assertTrue(parser.parse(event));
        assertEquals(Map.of("key", "value"), event.getFields());
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
