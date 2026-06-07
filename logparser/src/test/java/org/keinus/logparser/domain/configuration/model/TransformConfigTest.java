package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransformConfigTest {

    @Test
    void testGettersAndSetters() {
        TransformConfig config = new TransformConfig();
        config.setId(1L);
        config.setType("Filter");
        config.setMessagetype("syslog");
        config.setPriority(1);
        
        TransformParamConfig param = new TransformParamConfig();
        config.setParam(param);

        assertEquals(1L, config.getId());
        assertEquals("Filter", config.getType());
        assertEquals("syslog", config.getMessagetype());
        assertEquals(1, config.getPriority());
        assertEquals(param, config.getParam());
    }
}
