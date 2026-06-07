package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class OutputAdapterConfigTest {

    @Test
    void testHttpOutputAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("HttpOutputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setUrl("http://localhost:8080");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testTcpOutputAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("TcpOutputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setPort(9000);
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setHost("localhost");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testKafkaOutputAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("KafkaOutputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setTopicid("logs");
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setBootstrapservers("localhost:9092");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testOpenSearchOutputAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("OpenSearchOutputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setUrl("http://localhost:9200");
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setIndex("logs-%{yyMMdd}");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testRabbitMQAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("RabbitMQAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setHost("localhost");
        config.setRoutingkey("logs");
        config.setExchange("amq.direct");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testMariaDbOutputAdapterValidation() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("MariaDbOutputAdapter");

        assertThrows(IllegalArgumentException.class, config::validate);

        config.setConfigParams("""
                {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
                """);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testGettersAndSetters() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setId(1L);
        config.setMessagetype("all");
        config.setAddOriginText(true);
        config.setMethod("POST");
        config.setHeaders(Collections.singletonMap("Content-Type", "application/json"));
        config.setKey("key1");
        config.setOsUsername("admin");
        config.setOsPassword("pass");
        config.setAction("create");
        config.setRmqUsername("user");
        config.setRmqPassword("pass");
        config.setRmqPort(5672);
        config.setTagpass(Collections.singletonMap("tag", Collections.singletonList("val")));
        config.setBatchSize(500);
        config.setFlushIntervalMs(1000);
        config.setRetryCount(5);
        config.setRetryDelayMs(500);
        config.setEnabled(true);
        config.setTimeoutMs(5000);
        config.setConfigParams("{\"jdbcUrl\":\"jdbc:mariadb://mariadb:3306/castrelyx\"}");

        assertEquals(1L, config.getId());
        assertEquals("all", config.getMessagetype());
        assertTrue(config.getAddOriginText());
        assertEquals("POST", config.getMethod());
        assertEquals("application/json", config.getHeaders().get("Content-Type"));
        assertEquals("key1", config.getKey());
        assertEquals("admin", config.getOsUsername());
        assertEquals("pass", config.getOsPassword());
        assertEquals("create", config.getAction());
        assertEquals("user", config.getRmqUsername());
        assertEquals("pass", config.getRmqPassword());
        assertEquals(5672, config.getRmqPort());
        assertTrue(config.getTagpass().get("tag").contains("val"));
        assertEquals(500, config.getBatchSize());
        assertEquals(1000, config.getFlushIntervalMs());
        assertEquals(5, config.getRetryCount());
        assertEquals(500, config.getRetryDelayMs());
        assertTrue(config.getEnabled());
        assertEquals(5000, config.getTimeoutMs());
        assertEquals("{\"jdbcUrl\":\"jdbc:mariadb://mariadb:3306/castrelyx\"}", config.getConfigParams());
    }
}
