package org.keinus.logparser.domain.input.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.FakeInputAdapter;

class InputFactoryTest {

    @Test
    void createsAdapterFromAlias() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(1L);
        config.setType("fake");
        config.setMessagetype("test");

        assertInstanceOf(FakeInputAdapter.class, InputFactory.getInputAdapter(config));
    }

    @Test
    void preservesAdapterConstructorFailureCause() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(2L);
        config.setType("TcpMtlsGzipInputAdapter");
        config.setMessagetype("castrelyx-agent-item");
        config.setPort(9443);
        config.setConfigParams("""
                {
                  "keyStorePath": "missing-server.p12",
                  "keyStorePasswordEnv": "LOGPARSER_TEST_MISSING_KEYSTORE_PASSWORD",
                  "trustStorePath": "missing-truststore.p12",
                  "trustStorePasswordEnv": "LOGPARSER_TEST_MISSING_TRUSTSTORE_PASSWORD"
                }
                """);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> InputFactory.getInputAdapter(config));

        assertTrue(error.getMessage().contains("Failed to initialize input adapter TcpMtlsGzipInputAdapter"));
        assertTrue(error.getMessage().contains("Environment variable LOGPARSER_TEST_MISSING_KEYSTORE_PASSWORD is required"));
        assertEquals(IllegalArgumentException.class, error.getCause().getClass());
    }

    @Test
    void reportsUnsupportedType() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("MissingInputAdapter");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> InputFactory.getInputAdapter(config));

        assertTrue(error.getMessage().contains("MissingInputAdapter"));
        assertTrue(error.getCause() instanceof ClassNotFoundException);
    }
}
