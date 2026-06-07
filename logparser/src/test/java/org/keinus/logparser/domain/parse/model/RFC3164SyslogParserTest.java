package org.keinus.logparser.domain.parse.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Map;

class RFC3164SyslogParserTest {

    private RFC3164SyslogParser parser;

    @BeforeEach
    void setUp() {
        parser = new RFC3164SyslogParser();
        parser.init(null);
    }

    @Test
    void testParseValidRFC3164() {
        String raw = "<34>Oct 11 22:14:15 mymachine su: 'su root' failed for lonvick on /dev/pts/8";
        LogEvent event = new LogEvent(raw);
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals(4, fields.get(RFC3164SyslogParser.SyslogHeaders.FACILITY));
        assertEquals(2, fields.get(RFC3164SyslogParser.SyslogHeaders.SEVERITY));
        assertEquals("mymachine", fields.get(RFC3164SyslogParser.SyslogHeaders.HOST));
        assertEquals("su", fields.get(RFC3164SyslogParser.SyslogHeaders.TAG));
        assertEquals("'su root' failed for lonvick on /dev/pts/8", fields.get(RFC3164SyslogParser.SyslogHeaders.MESSAGE));
    }

    @Test
    void testParseIptablesMessage() {
        String raw = "<34>Oct 11 22:14:15 mymachine kernel: IN=eth0 OUT= MAC=00:00:00:00:00:00 SRC=192.168.1.1 DST=192.168.1.2";
        LogEvent event = new LogEvent(raw);
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals("eth0", fields.get("in"));
        assertEquals("192.168.1.1", fields.get("src"));
        assertEquals("192.168.1.2", fields.get("dst"));
    }

    @Test
    void testParseInvalidRFC3164() {
        LogEvent event = new LogEvent("invalid syslog");
        boolean result = parser.parse(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertEquals("true", fields.get(RFC3164SyslogParser.SyslogHeaders.DECODE_ERRORS));
    }

    @Test
    void testParseNullMessage() {
        LogEvent event = new LogEvent(null);
        boolean result = parser.parse(event);

        assertFalse(result);
    }
}
