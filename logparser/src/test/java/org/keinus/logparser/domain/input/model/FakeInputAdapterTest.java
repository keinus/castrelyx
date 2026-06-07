package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class FakeInputAdapterTest {

    private InputAdapterConfig config;

    @BeforeEach
    void setUp() {
        config = new InputAdapterConfig();
        config.setType("FakeInputAdapter");
        config.setMessagetype("fake-test");
    }

    @Test
    @DisplayName("Should generate fake logs")
    void generateLogs() throws IOException {
        FakeInputAdapter adapter = new FakeInputAdapter(config);
        
        LogEvent event = adapter.run();
        
        assertThat(event).isNotNull();
        assertThat(event.getOriginalText()).contains("\"event_type\":\"alert\"");
        assertThat(event.getMessageType()).isEqualTo("fake-test");
    }

    @Test
    @DisplayName("Should close without error")
    void closeAdapter() throws IOException {
        FakeInputAdapter adapter = new FakeInputAdapter(config);
        assertDoesNotThrow(adapter::close);
    }
}
