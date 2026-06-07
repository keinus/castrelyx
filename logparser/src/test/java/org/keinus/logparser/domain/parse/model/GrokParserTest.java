package org.keinus.logparser.domain.parse.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

class GrokParserTest {

    private GrokParser parser;

    @BeforeEach
    void setUp() {
        parser = new GrokParser();
    }

    @Test
    void testParseValidGrok() {
        parser.init("%{IP:client} %{WORD:method} %{URIPATHPARAM:request}");
        LogEvent event = new LogEvent("127.0.0.1 GET /index.html");
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals("127.0.0.1", fields.get("client"));
        assertEquals("GET", fields.get("method"));
        assertEquals("/index.html", fields.get("request"));
    }

    @Test
    void testParseNoMatch() {
        parser.init("%{IP:client}");
        LogEvent event = new LogEvent("not an ip");
        boolean result = parser.parse(event);

        assertFalse(result);
    }

    @Test
    void testParseNullMessage() {
        parser.init("%{IP:client}");
        LogEvent event = new LogEvent(null);
        boolean result = parser.parse(event);

        assertFalse(result);
    }

    @Test
    void testParseEmptyMessage() {
        parser.init("%{IP:client}");
        LogEvent event = new LogEvent("");
        boolean result = parser.parse(event);

        assertFalse(result);
    }
}
