package org.keinus.logparser.domain.parse.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

class RFC5424SyslogParserTest {

    private RFC5424SyslogParser parser;

    @BeforeEach
    void setUp() {
        parser = new RFC5424SyslogParser();
        parser.init(null);
    }

    @Test
    void testParseValidRFC5424() {
        String raw = "<34>1 2003-10-11T22:14:15.003Z mymachine.example.com su - ID47 - BOM'su root' failed for lonvick on /dev/pts/8";
        LogEvent event = new LogEvent(raw);
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals(4, fields.get(RFC5424SyslogParser.SyslogHeaders.FACILITY));
        assertEquals(2, fields.get(RFC5424SyslogParser.SyslogHeaders.SEVERITY));
        assertEquals("mymachine.example.com", fields.get(RFC5424SyslogParser.SyslogHeaders.HOST));
        assertEquals("su", fields.get(RFC5424SyslogParser.SyslogHeaders.APP_NAME));
        assertEquals("BOM'su root' failed for lonvick on /dev/pts/8", fields.get(RFC5424SyslogParser.SyslogHeaders.MESSAGE));
    }

    @Test
    void testParseInvalidRFC5424() {
        LogEvent event = new LogEvent("invalid syslog");
        boolean result = parser.parse(event);

        assertTrue(result); // It still returns true but with decode errors
        Map<String, Object> fields = event.getFields();
        assertEquals("true", fields.get(RFC5424SyslogParser.SyslogHeaders.DECODE_ERRORS));
    }

    @Test
    void testParseNullMessage() {
        LogEvent event = new LogEvent(null);
        boolean result = parser.parse(event);

        assertFalse(result);
    }
}
