package org.keinus.logparser.domain.configuration.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputAdapterConfigTest {

    @Test
    void testFileInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("FileInputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setPath("/var/log/syslog");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testTcpInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setPort(514);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testKafkaInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("KafkaInputAdapter");
        
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setTopicid("logs");
        assertThrows(IllegalArgumentException.class, config::validate);
        
        config.setBootstrapservers("localhost:9092");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testSnmpInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("SnmpInputAdapter");

        assertThrows(IllegalArgumentException.class, config::validate);

        config.setConfigParams("{\"targets\":[{\"host\":\"192.0.2.10\",\"community\":\"public\"}],\"oids\":[\"1.3.6.1.2.1.1.5.0\"]}");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testRabbitMqInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("RabbitMqInputAdapter");

        assertThrows(IllegalArgumentException.class, config::validate);

        config.setConfigParams("{\"queue\":\"logs.input\"}");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testTcpMtlsGzipInputAdapterValidation() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpMtlsGzipInputAdapter");

        assertThrows(IllegalArgumentException.class, config::validate);

        config.setPort(9443);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setConfigParams("""
                {"keyStorePath":"/app/certs/logparser-server.p12","keyStorePasswordEnv":"LOGPARSER_KEYSTORE_PASSWORD","trustStorePath":"/app/certs/agent-truststore.p12","trustStorePasswordEnv":"LOGPARSER_TRUSTSTORE_PASSWORD","maxFrameBytes":10485760,"ackMode":"queueAccepted"}
                """);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testGettersAndSetters() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(1L);
        config.setMessagetype("syslog");
        config.setHost("127.0.0.1");
        config.setIsFromBeginning(true);
        config.setGroupId("group1");
        config.setCodec("json");
        config.setPath_pattern("/api/v1");
        config.setBufferSize(1024);
        config.setTimeoutMs(1000);
        config.setEnabled(true);
        config.setWorkerThreads(4);
        config.setQueueSize(5000);
        config.setConfigParams("{\"targets\":[],\"oids\":[]}");

        assertEquals(1L, config.getId());
        assertEquals("syslog", config.getMessagetype());
        assertEquals("127.0.0.1", config.getHost());
        assertTrue(config.getIsFromBeginning());
        assertEquals("group1", config.getGroupId());
        assertEquals("json", config.getCodec());
        assertEquals("/api/v1", config.getPath_pattern());
        assertEquals(1024, config.getBufferSize());
        assertEquals(1000, config.getTimeoutMs());
        assertTrue(config.getEnabled());
        assertEquals(4, config.getWorkerThreads());
        assertEquals(5000, config.getQueueSize());
        assertEquals("{\"targets\":[],\"oids\":[]}", config.getConfigParams());
    }
}
