package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpsInputAdapterTest {

    @Test
    void constructorRequiresTlsConfigParams() {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("HttpsInputAdapter");
        config.setPort(19443);
        config.setMessagetype("https-test");

        assertThrows(IOException.class, () -> new HttpsInputAdapter(config));
    }
}
