package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaTest {

    @Test
    void testAnnotationsPresent() throws NoSuchFieldException {
        // Since ConfigSchema contains only annotations, we check if they exist and can be used.
        // We'll use InputAdapterConfig which uses these annotations to verify they work.
        Field typeField = InputAdapterConfig.class.getDeclaredField("type");
        assertTrue(typeField.isAnnotationPresent(ConfigSchema.Required.class));
        assertTrue(typeField.isAnnotationPresent(ConfigSchema.Choice.class));
        assertTrue(typeField.isAnnotationPresent(ConfigSchema.Description.class));

        Field portField = InputAdapterConfig.class.getDeclaredField("port");
        assertTrue(portField.isAnnotationPresent(ConfigSchema.Range.class));
        assertTrue(portField.isAnnotationPresent(ConfigSchema.AdapterSpecific.class));
    }
}
