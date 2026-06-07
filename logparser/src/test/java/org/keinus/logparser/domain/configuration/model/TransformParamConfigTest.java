package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TransformParamConfigTest {

    @Test
    void testGettersAndSetters() {
        TransformParamConfig config = new TransformParamConfig();
        
        Map<String, String> pass = Collections.singletonMap("level", "ERROR");
        Map<String, String> drop = Collections.singletonMap("level", "DEBUG");
        Map<String, List<String>> add = Collections.singletonMap("newField", Collections.singletonList("newValue"));
        List<String> remove = Collections.singletonList("oldField");

        config.setPass(pass);
        config.setDrop(drop);
        config.setAdd(add);
        config.setRemove(remove);

        assertEquals(pass, config.getPass());
        assertEquals(drop, config.getDrop());
        assertEquals(add, config.getAdd());
        assertEquals(remove, config.getRemove());
    }
}
