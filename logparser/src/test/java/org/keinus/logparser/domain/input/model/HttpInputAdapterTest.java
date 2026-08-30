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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void receivesUtf8BodyWithoutWaitingForClientDisconnect() throws Exception {
        adapter = new HttpInputAdapter(config);
        var executor = Executors.newSingleThreadExecutor();
        try (Socket client = new Socket("localhost", testPort)) {
            var received = executor.submit(adapter::run);
            String body = "한글🙂" + "a".repeat(8190) + "마지막";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(("POST / HTTP/1.1\r\nContent-Length: " + payload.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(payload);
            client.getOutputStream().flush();

            assertThat(received.get(2, TimeUnit.SECONDS).getOriginalText()).endsWith(body);
        } finally {
            adapter.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeReleasesAnAcceptedClientWithAnIncompleteRequest() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        ServerSocket listener = new ServerSocket(0) {
            @Override
            public Socket accept() throws IOException {
                Socket socket = super.accept();
                accepted.countDown();
                return socket;
            }
        };
        adapter = new HttpInputAdapter(config, (ignored, port) -> listener, "HTTP");
        var executor = Executors.newSingleThreadExecutor();
        try (Socket client = new Socket("localhost", listener.getLocalPort())) {
            var received = executor.submit(adapter::run);
            client.getOutputStream().write("POST / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(accepted.await(2, TimeUnit.SECONDS)).isTrue();

            adapter.close();

            assertThat(received.get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            adapter.close();
            executor.shutdownNow();
        }
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
