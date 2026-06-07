package org.keinus.logparser.infrastructure.persistence.converter;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class JsonConverterTest {

    private final JsonConverter converter = new JsonConverter();

    @Test
    void testConvertToDatabaseColumn() {
        Map<String, String> map = Map.of("key", "value");
        String json = converter.convertToDatabaseColumn(map);
        assertThat(json).contains("\"key\":\"value\"");
    }

    @Test
    void testConvertToDatabaseColumnNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void testConvertToEntityAttribute() {
        String json = "{\"key\":\"value\"}";
        Object obj = converter.convertToEntityAttribute(json);
        assertThat(obj).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) obj;
        assertThat(map.get("key")).isEqualTo("value");
    }

    @Test
    void testConvertToEntityAttributeNullOrEmpty() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isNull();
    }

    @Test
    void testConvertToEntityAttributeInvalidJson() {
        assertThat(converter.convertToEntityAttribute("invalid-json")).isNull();
    }
}
