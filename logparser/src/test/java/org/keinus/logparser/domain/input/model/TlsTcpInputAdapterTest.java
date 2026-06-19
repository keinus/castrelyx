package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TlsTcpInputAdapterTest {

    @Test
    void constructorRequiresTlsConfigParams() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TlsTcpInputAdapter");
        config.setPort(19614);
        config.setMessagetype("tls-tcp-test");

        assertThrows(IOException.class, () -> new TlsTcpInputAdapter(config));
    }
}
