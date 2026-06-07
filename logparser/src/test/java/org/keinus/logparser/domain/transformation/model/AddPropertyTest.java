package org.keinus.logparser.domain.transformation.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AddPropertyTest {

    private AddProperty transform;

    @BeforeEach
    void setUp() {
        transform = new AddProperty();
    }

    @Test
    void testTransformAddProperty() {
        TransformParamConfig config = new TransformParamConfig();
        Map<String, List<String>> add = new HashMap<>();
        add.put("user", Arrays.asList("name", "email"));
        config.setAdd(add);
        transform.init(config);

        LogEvent event = new LogEvent("test");
        event.getFields().put("name", "john");
        event.getFields().put("email", "john@example.com");
        event.getFields().put("other", "data");

        boolean result = transform.transform(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertFalse(fields.containsKey("name"));
        assertFalse(fields.containsKey("email"));
        assertTrue(fields.containsKey("user"));
        assertTrue(fields.containsKey("other"));

        Object userValue = fields.get("user");
        assertTrue(userValue instanceof Map<?, ?>);
        Map<?, ?> user = (Map<?, ?>) userValue;
        assertEquals("john", user.get("name"));
        assertEquals("john@example.com", user.get("email"));
    }

    @Test
    void testInitWithNull() {
        TransformParamConfig config = new TransformParamConfig();
        config.setAdd(null);
        transform.init(config);
        
        LogEvent event = new LogEvent("test");
        boolean result = transform.transform(event);
        assertTrue(result);
    }
}
