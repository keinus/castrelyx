package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpInputAdapterTest {

    private InputAdapterConfig config;
    private HttpInputAdapter adapter;
    private int testPort = 19083;

    @BeforeEach
    void setUp() {
        config = new InputAdapterConfig();
        config.setType("HttpInputAdapter");
        config.setPort(testPort);
        config.setMessagetype("http-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    @DisplayName("Constructor should throw exception if port is missing")
    void constructorMissingPort() {
        config.setPort(null);
        assertThrows(IllegalArgumentException.class, () -> new HttpInputAdapter(config));
    }

    @Test
    @DisplayName("Should receive HTTP request")
    void receiveHttpRequest() throws IOException, InterruptedException {
        adapter = new HttpInputAdapter(config);

        AtomicReference<LogEvent> receivedEvent = new AtomicReference<>();
        Thread adapterThread = new Thread(() -> {
            LogEvent event = adapter.run();
            receivedEvent.set(event);
        });
        adapterThread.start();

        // Send HTTP request
        try (Socket client = new Socket("localhost", testPort)) {
            OutputStream out = client.getOutputStream();
            String request = "POST / HTTP/1.1\r\n" +
                             "Host: localhost\r\n" +
                             "Content-Length: 10\r\n" +
                             "\r\n" +
                             "0123456789";
            out.write(request.getBytes());
            out.flush();
        }

        adapterThread.join(2000);
        
        assertThat(receivedEvent.get()).isNotNull();
        assertThat(receivedEvent.get().getOriginalText()).contains("0123456789");
        assertThat(receivedEvent.get().getMessageType()).isEqualTo("http-test");
    }

    @Test
    @DisplayName("Should handle Content-Length mismatch")
    void contentLengthMismatch() throws IOException, InterruptedException {
        adapter = new HttpInputAdapter(config);

        AtomicReference<LogEvent> receivedEvent = new AtomicReference<>();
        Thread adapterThread = new Thread(() -> {
            LogEvent event = adapter.run();
            receivedEvent.set(event);
        });
        adapterThread.start();

        try (Socket client = new Socket("localhost", testPort)) {
            OutputStream out = client.getOutputStream();
            String request = "POST / HTTP/1.1\r\n" +
                             "Content-Length: 20\r\n" +
                             "\r\n" +
                             "Too short";
            out.write(request.getBytes());
            out.flush();
            // Closing the socket will trigger end of stream
        }

        adapterThread.join(2000);
        assertThat(receivedEvent.get()).isNotNull();
        assertThat(receivedEvent.get().getOriginalText()).contains("Too short");
    }
}
