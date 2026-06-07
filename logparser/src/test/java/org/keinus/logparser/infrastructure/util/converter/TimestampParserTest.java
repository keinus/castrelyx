package org.keinus.logparser.infrastructure.util.converter;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class TimestampParserTest {

    @Test
    void testParseInstant() {
        Instant now = Instant.now();
        assertThat(TimestampParser.parse(now)).isEqualTo(now);
    }

    @Test
    void testParseLong() {
        long millis = 1627650000000L;
        assertThat(TimestampParser.parse(millis)).isEqualTo(Instant.ofEpochMilli(millis));
        
        long seconds = 1627650000L;
        assertThat(TimestampParser.parse(seconds)).isEqualTo(Instant.ofEpochSecond(seconds));
    }

    @Test
    void testParseStringEpoch() {
        assertThat(TimestampParser.parse("1627650000000")).isEqualTo(Instant.ofEpochMilli(1627650000000L));
        assertThat(TimestampParser.parse("1627650000")).isEqualTo(Instant.ofEpochSecond(1627650000L));
    }

    @Test
    void testParseIsoFormats() {
        assertThat(TimestampParser.parse("2021-07-30T10:00:00Z")).isEqualTo(Instant.parse("2021-07-30T10:00:00Z"));
    }

    @Test
    void testParseCommonFormats() {
        // yyyy-MM-dd HH:mm:ss
        assertThat(TimestampParser.parse("2021-07-30 10:00:00")).isNotNull();
        // MMM dd HH:mm:ss (Syslog) - This might fail if no year is provided and it assumes current year
        assertThat(TimestampParser.parse("Jul 30 10:00:00")).isNotNull();
    }

    @Test
    void testParseInvalid() {
        assertThat(TimestampParser.parse(null)).isNull();
        assertThat(TimestampParser.parse("invalid-date")).isNull();
    }
}
