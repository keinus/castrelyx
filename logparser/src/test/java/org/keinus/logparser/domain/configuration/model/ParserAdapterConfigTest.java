package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserAdapterConfigTest {

    @Test
    void testGrokParserValidation() {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setType("GrokParser");
        config.setMessagetype("syslog");
        
        // Missing param
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setParam("%{COMMONLOGBASE}");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testRegexParserValidation() {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setType("RegexParser");
        config.setMessagetype("custom");
        
        // Missing param
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setParam(".*");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testJsonParserValidation() {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setType("JsonParser");
        config.setMessagetype("json");
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testGettersAndSetters() {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setId(1L);
        config.setPriority(10);
        config.setEnabled(false);
        config.setContinueOnFailure(true);

        assertEquals(1L, config.getId());
        assertEquals(10, config.getPriority());
        assertFalse(config.getEnabled());
        assertTrue(config.getContinueOnFailure());
    }
}
