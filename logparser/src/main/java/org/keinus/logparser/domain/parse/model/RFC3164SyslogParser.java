package org.keinus.logparser.domain.parse.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.util.PatternCache;

public class RFC3164SyslogParser implements IParser {
    /**
     * Parser for RFC3164 (BSD syslog) formatted log messages.
     * Extracts priority, timestamp, hostname, and message body into a map.
     */
    protected static final char SPACE = ' ';

    // Pattern 캐싱: split()은 내부적으로 Pattern.compile()을 호출하므로 미리 컴파일
    private static final PatternCache PATTERN_CACHE = PatternCache.getInstance();
    private static final Pattern WHITESPACE_PATTERN = PATTERN_CACHE.compile("\\s+");

    public RFC3164SyslogParser() {
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
                // <PRI>
                r.expect('<');
                int pri = r.readInt();
                r.expect('>');

                // RFC3164 TIMESTAMP (MMM dd HH:mm:ss)
                String timestamp = getTimestamp(r);

                // HOST
                String host = r.getIdentifier();

                // TAG (until ':' or SPACE)
                String tag = r.getIdentifierUntil(':');
                if (r.is(':')) {
                    r.getc(); // skip ':'
                }
                if (r.is(SPACE)) {
                    r.getc();
                }

                // MESSAGE (rest of line)
                String message = r.rest();

                // 기본 syslog 메타데이터
                int severity = pri & 0x7;
                int facility = pri >> 3;
                map.put(SyslogHeaders.FACILITY, facility);
                map.put(SyslogHeaders.SEVERITY, severity);
                map.put(SyslogHeaders.SEVERITY_TEXT, Severity.parseInt(severity).label());
                map.put(SyslogHeaders.TIMESTAMP, timestamp);
                map.put(SyslogHeaders.HOST, host);
                map.put(SyslogHeaders.TAG, tag);
                map.put(SyslogHeaders.MESSAGE, message);
                map.put(SyslogHeaders.DECODE_ERRORS, "false");

                // iptables key=value 필드 파싱
                parseKeyValueFields(message, map);

            } catch (IllegalStateException | StringIndexOutOfBoundsException ex) {
                map.put(SyslogHeaders.DECODE_ERRORS, "true");
                map.put(SyslogHeaders.ERRORS,
                        (ex instanceof StringIndexOutOfBoundsException ? "Unexpected end of message: " : "")
                                + ex.getMessage());
                map.put(SyslogHeaders.UNDECODED, line);
            }

            if (!map.isEmpty()) {
                logEvent.setFields(map);
                return true;
            }
        } catch (Exception e) {
            logEvent.markAsError("RFC3164 parsing failed: " + e.getMessage());
        }
        return false;
    }


    /** iptables 메시지에서 key=value 형태 필드 추출 */
    private void parseKeyValueFields(String message, Map<String, Object> map) {
        String[] parts = WHITESPACE_PATTERN.split(message);
        for (String part : parts) {
            if (part.contains("=")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0].toLowerCase(), kv[1]);
                }
            }
        }
    }

    protected String getTimestamp(Reader r) {
        String month = r.getToken();
        String day = r.getToken();
        String time = r.getToken();
        return month + SPACE + day + SPACE + time;
    }

    protected static class Reader {
        private final String line;
        private int idx;

        public Reader(String l) {
            this.line = l;
        }

        public int getc() {
            return this.line.charAt(this.idx++);
        }

        public boolean is(char c) {
            return this.idx < this.line.length() && this.line.charAt(this.idx) == c;
        }

        public void expect(char c) {
            if (getc() != c) {
                throw new IllegalStateException("Expected '" + c + "' @" + this.idx);
            }
        }

        public boolean isDigit() {
            return this.idx < this.line.length() && Character.isDigit(this.line.charAt(this.idx));
        }

        public int readInt() {
            int val = 0;
            while (isDigit()) {
                val = (val * 10) + (getc() - '0');
            }
            return val;
        }

        public String rest() {
            return this.idx < this.line.length() ? this.line.substring(this.idx) : "";
        }

        public String getIdentifier() {
            StringBuilder sb = new StringBuilder();
            while (this.idx < this.line.length() && !Character.isWhitespace(this.line.charAt(this.idx))) {
                sb.append((char) getc());
            }
            if (is(SPACE)) {
                getc();
            }
            return sb.toString();
        }

        public String getIdentifierUntil(char stopChar) {
            StringBuilder sb = new StringBuilder();
            while (this.idx < this.line.length() && !is(stopChar) && !Character.isWhitespace(this.line.charAt(this.idx))) {
                sb.append((char) getc());
            }
            return sb.toString();
        }

        public String getToken() {
            skipWhitespace();
            StringBuilder sb = new StringBuilder();
            while (this.idx < this.line.length() && !Character.isWhitespace(this.line.charAt(this.idx))) {
                sb.append((char) getc());
            }
            skipWhitespace();
            return sb.toString();
        }

        private void skipWhitespace() {
            while (this.idx < this.line.length() && Character.isWhitespace(this.line.charAt(this.idx))) {
                this.idx++;
            }
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
            switch (syslogSeverity) {
                case 7: return DEBUG;
                case 6: return INFO;
                case 5: return NOTICE;
                case 4: return WARN;
                case 3: return ERROR;
                case 2: return CRITICAL;
                case 1: return ALERT;
                case 0: return EMERGENCY;
                default: return UNDEFINED;
            }
        }
    }

    public final class SyslogHeaders {
        private SyslogHeaders() {}
        public static final String FACILITY = "FACILITY";
        public static final String SEVERITY = "SEVERITY";
        public static final String TIMESTAMP = "TIMESTAMP";
        public static final String HOST = "HOST";
        public static final String TAG = "TAG";
        public static final String MESSAGE = "MESSAGE";
        public static final String SEVERITY_TEXT = "SEVERITY_TEXT";
        public static final String UNDECODED = "UNDECODED";
        public static final String DECODE_ERRORS = "DECODE_ERRORS";
        public static final String ERRORS = "ERRORS";
    }
}
