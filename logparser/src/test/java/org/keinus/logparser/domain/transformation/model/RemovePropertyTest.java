package org.keinus.logparser.domain.transformation.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.util.Arrays;
import java.util.Map;

class RemovePropertyTest {

    private RemoveProperty transform;

    @BeforeEach
    void setUp() {
        transform = new RemoveProperty();
    }

    @Test
    void testTransformRemoveProperty() {
        TransformParamConfig config = new TransformParamConfig();
        config.setRemove(Arrays.asList("secret", "sensitive"));
        transform.init(config);

        LogEvent event = new LogEvent("test");
        event.getFields().put("secret", "123");
        event.getFields().put("sensitive", "abc");
        event.getFields().put("public", "xyz");

        boolean result = transform.transform(event);

        assertTrue(result);
        Map<String, Object> fields = event.getFields();
        assertFalse(fields.containsKey("secret"));
        assertFalse(fields.containsKey("sensitive"));
        assertTrue(fields.containsKey("public"));
    }

    @Test
    void testInitWithNull() {
        TransformParamConfig config = new TransformParamConfig();
        config.setRemove(null);
        transform.init(config);

        LogEvent event = new LogEvent("test");
        boolean result = transform.transform(event);
        assertTrue(result);
    }
}
