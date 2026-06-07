package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TcpInputAdapterTest {

    private InputAdapterConfig config;
    private TcpInputAdapter adapter;
    private int testPort = 19081;

    @BeforeEach
    void setUp() {
        config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setPort(testPort);
        config.setMessagetype("tcp-test");
        config.setHost("localhost");
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
        assertThrows(IllegalArgumentException.class, () -> new TcpInputAdapter(config));
    }

    @Test
    @DisplayName("Should receive message from TCP client")
    void receiveMessage() throws IOException, InterruptedException {
        adapter = new TcpInputAdapter(config);
        
        // Start a thread to run the adapter since run() might block or we need to poll
        AtomicReference<LogEvent> receivedEvent = new AtomicReference<>();
        Thread adapterThread = new Thread(() -> {
            // TcpInputAdapter.run() polls from an internal queue or accepts new connection
            // We need to call it multiple times in a loop to simulate the processing loop
            while (receivedEvent.get() == null && !Thread.currentThread().isInterrupted()) {
                LogEvent event = adapter.run();
                if (event != null) {
                    receivedEvent.set(event);
                }
            }
        });
        adapterThread.start();

        // Connect and send a message
        try (Socket client = new Socket("localhost", testPort)) {
            OutputStream out = client.getOutputStream();
            out.write("Hello TCP\n".getBytes());
            out.flush();
        }

        // Wait for event
        long start = System.currentTimeMillis();
        while (receivedEvent.get() == null && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(100);
        }

        adapterThread.interrupt();
        
        assertThat(receivedEvent.get()).isNotNull();
        assertThat(receivedEvent.get().getOriginalText()).isEqualTo("Hello TCP");
        assertThat(receivedEvent.get().getMessageType()).isEqualTo("tcp-test");
    }

    @Test
    @DisplayName("Should handle multiple messages from one client")
    void multipleMessages() throws IOException, InterruptedException {
        adapter = new TcpInputAdapter(config);
        
        AtomicReference<Integer> count = new AtomicReference<>(0);
        Thread adapterThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                LogEvent event = adapter.run();
                if (event != null) {
                    count.updateAndGet(v -> v + 1);
                }
            }
        });
        adapterThread.start();

        try (Socket client = new Socket("localhost", testPort)) {
            OutputStream out = client.getOutputStream();
            out.write("Msg1\nMsg2\n".getBytes());
            out.flush();
        }

        Thread.sleep(500);
        adapterThread.interrupt();
        
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle empty lines")
    void emptyLines() throws IOException, InterruptedException {
        adapter = new TcpInputAdapter(config);
        
        AtomicReference<Integer> count = new AtomicReference<>(0);
        Thread adapterThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                LogEvent event = adapter.run();
                if (event != null) {
                    count.updateAndGet(v -> v + 1);
                }
            }
        });
        adapterThread.start();

        try (Socket client = new Socket("localhost", testPort)) {
            OutputStream out = client.getOutputStream();
            out.write("\n  \nMsg\n".getBytes());
            out.flush();
        }

        Thread.sleep(500);
        adapterThread.interrupt();
        
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Close should shutdown everything")
    void closeAdapter() throws IOException {
        adapter = new TcpInputAdapter(config);
        adapter.close();

        assertNull(adapter.run());
        try (ServerSocket socket = new ServerSocket(testPort)) {
            assertTrue(socket.isBound());
        }
    }
}
