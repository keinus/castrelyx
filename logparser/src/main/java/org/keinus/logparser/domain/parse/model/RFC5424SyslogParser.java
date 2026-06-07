package org.keinus.logparser.domain.parse.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keinus.logparser.domain.model.LogEvent;
import org.springframework.util.Assert;

public class RFC5424SyslogParser implements IParser {
    /**
     * Parser for RFC5424 (modern syslog) formatted log messages.
     * Extracts priority, version, timestamp, hostname, app name, process ID, message ID, and structured data.
     */
    protected static final char NILVALUE = '-';
    protected static final char SPACE = ' ';

    public RFC5424SyslogParser() {
        // Nothing
    }

    @Override
    public void init(Object param) {
        // Nothing
    }

    @Override
    public boolean parse(LogEvent logEvent) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            String line = logEvent.getOriginalText();
            Reader r = new Reader(line);

            try {
                r.expect('<');
                int pri = r.readInt();
                r.expect('>');

                int version = r.readInt();
                r.expect(SPACE);

                Object timestamp = getTimestamp(r);

                String host = r.getIdentifier();
                String app = r.getIdentifier();
                String procId = r.getIdentifier();
                String msgId = r.getIdentifier();

                Object structuredData = getStructuredData(r);

                String message;
                if (r.is(SPACE)) {
                    r.getc();
                    message = r.rest();
                } else {
                    message = "";
                }

                int severity = pri & 0x7; // NOSONAR magic number
                int facility = pri >> 3; // NOSONAR magic number
                map.put(SyslogHeaders.FACILITY, facility);
                map.put(SyslogHeaders.SEVERITY, severity);
                map.put(SyslogHeaders.SEVERITY_TEXT, Severity.parseInt(severity).label());
                map.put(SyslogHeaders.VERSION, version);
                map.put(SyslogHeaders.MESSAGE, message);
                map.put(SyslogHeaders.DECODE_ERRORS, "false");
                map.put(SyslogHeaders.TIMESTAMP, timestamp);
                map.put(SyslogHeaders.HOST, host);
                map.put(SyslogHeaders.APP_NAME, app);
                map.put(SyslogHeaders.PROCID, procId);
                map.put(SyslogHeaders.MSGID, msgId);
                map.put(SyslogHeaders.STRUCTURED_DATA, structuredData);
            } catch (IllegalStateException | StringIndexOutOfBoundsException ex) {
                map.put(SyslogHeaders.DECODE_ERRORS, "true");
                map.put(SyslogHeaders.ERRORS,
                        (ex instanceof StringIndexOutOfBoundsException ? "Unexpected end of message: " : "") // NOSONAR
                                + ex.getMessage());
                map.put(SyslogHeaders.UNDECODED, line);
            }

            if (!map.isEmpty()) {
                logEvent.setFields(map);
                return true;
            }
        } catch (Exception e) {
            logEvent.markAsError("RFC5424 parsing failed: " + e.getMessage());
        }
        return false;
    }


    protected Object getTimestamp(Reader r) {

        int c = r.getc();

        if (c == NILVALUE) {
            return null;
        }

        if (!Character.isDigit(c)) {
            throw new IllegalStateException("Year expected @" + r.getIndex());
        }

        StringBuilder dateBuilder = new StringBuilder();
        dateBuilder.append((char) c);
        while ((c = r.getc()) != SPACE) {
            dateBuilder.append((char) c);
        }

        return dateBuilder.toString();
    }

    private Object getStructuredData(Reader r) {
        if (r.is(NILVALUE)) {
            r.getc();
            return null;
        }
        return parseStructuredDataElements(r);
    }

    protected Object parseStructuredDataElements(Reader r) {
        List<String> fragments = new ArrayList<>();
        while (r.is('[')) {
            r.mark();
            r.skipTo(']');
            fragments.add(r.getMarkedSegment());
        }
        return fragments;
    }

    protected static class Reader {

        private final String line;

        private int idx;

        private int mark;

        public Reader(String l) {
            this.line = l;
        }

        public int getIndex() {
            return this.idx;
        }

        public void mark() {
            this.mark = this.idx;
        }

        public String getMarkedSegment() {
            Assert.state(this.mark <= this.idx, "mark is greater than this.idx");
            return this.line.substring(this.mark, this.idx);
        }

        public int current() {
            return this.line.charAt(this.idx);
        }

        public int prev() {
            return this.line.charAt(this.idx - 1);
        }

        public int getc() {
            return this.line.charAt(this.idx++);
        }

        public int peek() {
            return this.line.charAt(this.idx + 1);
        }

        public void ungetc() {
            this.idx--;
        }

        public int getInt() {
            int c = getc();
            if (!Character.isDigit(c)) {
                ungetc();
                return -1;
            }

            return c - '0';
        }

        /**
         * Read characters building an int until a non-digit is found
         * 
         * @return int
         */
        public int readInt() {
            int val = 0;
            while (isDigit()) {
                val = (val * 10) + getInt(); // NOSONAR magic number
            }
            return val;
        }

        public boolean is(char c) {
            return this.line.charAt(this.idx) == c;
        }

        public boolean was(char c) {
            return this.line.charAt(this.idx - 1) == c;
        }

        public boolean isDigit() {
            return Character.isDigit(this.line.charAt(this.idx));
        }

        public void expect(char c) {
            if (this.line.charAt(this.idx++) != c) {
                throw new IllegalStateException("Expected '" + c + "' @" + this.idx);
            }
        }

        public void skipTo(char searchChar) {
            while (!is(searchChar) || was('\\')) {
                getc();
            }
            getc();
        }

        public String rest() {
            return this.line.substring(this.idx);
        }

        public String getIdentifier() {
            StringBuilder sb = new StringBuilder();
            int c;
            while (true) {
                c = getc();
                if (c >= 33 && c <= 127) { // NOSONAR magic number
                    sb.append((char) c);
                } else {
                    break;
                }
            }
            return sb.toString();
        }

    }

    protected enum Severity {

        DEBUG(7, "DEBUG"),

        INFO(6, "INFO"),

        NOTICE(5, "NOTICE"),

        WARN(4, "WARN"),

        ERROR(3, "ERRORS"),

        CRITICAL(2, "CRITICAL"),

        ALERT(1, "ALERT"),

        EMERGENCY(0, "EMERGENCY"),

        UNDEFINED(-1, "UNDEFINED");

        private final int level;

        private final String label;

        Severity(int level, String label) {
            this.level = level;
            this.label = label;
        }

        public int level() {
            return this.level;
        }

        public String label() {
            return this.label;
        }

        public static Severity parseInt(int syslogSeverity) {
            if (syslogSeverity == 7) { // NOSONAR magic number
                return DEBUG;
            }
            if (syslogSeverity == 6) { // NOSONAR magic number
                return INFO;
            }
            if (syslogSeverity == 5) { // NOSONAR magic number
                return NOTICE;
            }
            if (syslogSeverity == 4) { // NOSONAR magic number
                return WARN;
            }
            if (syslogSeverity == 3) { // NOSONAR magic number
                return ERROR;
            }
            if (syslogSeverity == 2) {
                return CRITICAL;
            }
            if (syslogSeverity == 1) {
                return ALERT;
            }
            if (syslogSeverity == 0) {
                return EMERGENCY;
            }
            return UNDEFINED;
        }

    }

    public final class SyslogHeaders {

        private SyslogHeaders() {
        }

        public static final String PREFIX = "syslog_";
        public static final String FACILITY = PREFIX + "FACILITY";
        public static final String SEVERITY = PREFIX + "SEVERITY";
        public static final String TIMESTAMP = PREFIX + "TIMESTAMP";
        public static final String HOST = PREFIX + "HOST";
        public static final String TAG = PREFIX + "TAG";

        // RFC 5424 Parser:
        public static final String MESSAGE = PREFIX + "MESSAGE";
        public static final String APP_NAME = PREFIX + "APP_NAME";
        public static final String PROCID = PREFIX + "PROCID";
        public static final String MSGID = PREFIX + "MSGID";
        public static final String VERSION = PREFIX + "VERSION";
        public static final String STRUCTURED_DATA = PREFIX + "STRUCTURED_DATA";

        // Text versions of syslog numeric values
        public static final String SEVERITY_TEXT = PREFIX + "SEVERITY_TEXT";

        // Additional fields
        public static final String SOURCE_TYPE = PREFIX + "SOURCE_TYPE";
        public static final String SOURCE = PREFIX + "SOURCE";

        // full line when parse errors or retained original
        public static final String UNDECODED = PREFIX + "UNDECODED";
        public static final String DECODE_ERRORS = PREFIX + "DECODE_ERRORS";
        public static final String ERRORS = PREFIX + "ERRORS";
    }

    
}