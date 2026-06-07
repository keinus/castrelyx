package org.keinus.logparser.infrastructure.util.converter;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class NumberParserTest {

    @Test
    void testParseLong() {
        assertThat(NumberParser.parseLong(123L)).isEqualTo(123L);
        assertThat(NumberParser.parseLong("123")).isEqualTo(123L);
        assertThat(NumberParser.parseLong("1,234")).isEqualTo(1234L);
        assertThat(NumberParser.parseLong("123.45")).isEqualTo(123L);
        assertThat(NumberParser.parseLong(null)).isNull();
        assertThat(NumberParser.parseLong("")).isNull();
        assertThat(NumberParser.parseLong("abc")).isNull();
    }

    @Test
    void testParseInt() {
        assertThat(NumberParser.parseInt(123)).isEqualTo(123);
        assertThat(NumberParser.parseInt("123")).isEqualTo(123);
        assertThat(NumberParser.parseInt(null)).isNull();
    }

    @Test
    void testParseDouble() {
        assertThat(NumberParser.parseDouble(123.45)).isEqualTo(123.45);
        assertThat(NumberParser.parseDouble("123.45")).isEqualTo(123.45);
        assertThat(NumberParser.parseDouble("1,234.56")).isEqualTo(1234.56);
        assertThat(NumberParser.parseDouble(null)).isNull();
        assertThat(NumberParser.parseDouble("")).isNull();
        assertThat(NumberParser.parseDouble("abc")).isNull();
    }

    @Test
    void testParseBigDecimal() {
        assertThat(NumberParser.parseBigDecimal("123.45")).isEqualTo(new BigDecimal("123.45"));
        assertThat(NumberParser.parseBigDecimal("1,234.56")).isEqualTo(new BigDecimal("1234.56"));
        assertThat(NumberParser.parseBigDecimal(null)).isNull();
        assertThat(NumberParser.parseBigDecimal("")).isNull();
        assertThat(NumberParser.parseBigDecimal("abc")).isNull();
    }
}
