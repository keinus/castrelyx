package org.keinus.logparser.application.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.output.model.OutputAdapter;
import org.keinus.logparser.domain.output.model.OutputDeliveryException;
import org.keinus.logparser.domain.output.service.OutputFactory;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutputAdapterComponentTest {

    @Mock
    private ApplicationProperties appProp;

    private OutputAdapterComponent outputAdapterComponent;

    @BeforeEach
    void setUp() {
        outputAdapterComponent = new OutputAdapterComponent(appProp);
    }

    @Test
    void testDeliverToMatchingAdapterAndCollectMetrics() throws Exception {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setId(1L);
        config.setType("MockAdapter");
        config.setMessagetype("test");
        config.setEnabled(true);

        when(appProp.getOutput()).thenReturn(List.of(config));

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        AtomicBoolean invoked = new AtomicBoolean(false);

        OutputAdapter mockAdapter = new OutputAdapter(Map.of("id", "1", "messagetype", "test")) {
            @Override
            public void send(LogEvent logEvent) {
                invoked.set(true);
            }

            @Override
            public void close() throws IOException {}

            @Override
            public Long getId() {
                return 1L;
            }
        };

        try (MockedStatic<OutputFactory> mockedFactory = mockStatic(OutputFactory.class)) {
            mockedFactory.when(() -> OutputFactory.getOutputAdapter(any())).thenReturn(mockAdapter);

            outputAdapterComponent.startPipeline();
            OutputAdapterComponent.DeliverySummary summary = outputAdapterComponent.deliver(logEvent);

            assertTrue(invoked.get(), "Message should be delivered to matching adapter");
            assertEquals(1, summary.attempted());
            assertEquals(1, summary.succeeded());
            assertEquals(0, summary.failed());
            assertEquals(1, outputAdapterComponent.getAdapterMetrics().size());
            assertEquals(1L, outputAdapterComponent.getAdapterMetrics().get(0).sentCount());

            outputAdapterComponent.close();
        }
    }

    @Test
    void testBroadcastToSpecificAndGlobalAdapters() throws Exception {
        OutputAdapterConfig config1 = new OutputAdapterConfig();
        config1.setId(1L);
        config1.setType("SpecificAdapter");
        config1.setMessagetype("test");
        config1.setEnabled(true);

        OutputAdapterConfig config2 = new OutputAdapterConfig();
        config2.setId(2L);
        config2.setType("GlobalAdapter");
        config2.setMessagetype("all");
        config2.setEnabled(true);

        when(appProp.getOutput()).thenReturn(List.of(config1, config2));

        LogEvent logEvent = spy(new LogEvent("test message", "localhost", "test"));
        AtomicInteger sendCount = new AtomicInteger(0);

        OutputAdapter mockAdapter1 = new OutputAdapter(Map.of("messagetype", "test", "id", "1")) {
            @Override
            public void send(LogEvent logEvent) {
                sendCount.incrementAndGet();
            }

            @Override
            public void close() throws IOException {}
        };

        OutputAdapter mockAdapter2 = new OutputAdapter(Map.of("messagetype", "all", "id", "2")) {
            @Override
            public void send(LogEvent logEvent) {
                sendCount.incrementAndGet();
            }

            @Override
            public void close() throws IOException {}
        };

        try (MockedStatic<OutputFactory> mockedFactory = mockStatic(OutputFactory.class)) {
            mockedFactory.when(() -> OutputFactory.getOutputAdapter(any())).thenAnswer(invocation -> {
                OutputAdapterConfig c = invocation.getArgument(0);
                if (c.getId() == 1L) {
                    return mockAdapter1;
                }
                return mockAdapter2;
            });

            outputAdapterComponent.startPipeline();
            OutputAdapterComponent.DeliverySummary summary = outputAdapterComponent.deliver(logEvent);

            assertEquals(2, sendCount.get(), "Message should be delivered to both specific and global adapters");
            assertEquals(2, summary.attempted());
            assertEquals(2, summary.succeeded());
            assertFalse(summary.hasFailures());

            outputAdapterComponent.close();
        }
    }

    @Test
    void testBlankMessageTypeIsTreatedAsGlobalAdapter() throws Exception {
        OutputAdapterConfig specificConfig = new OutputAdapterConfig();
        specificConfig.setId(1L);
        specificConfig.setType("SpecificAdapter");
        specificConfig.setMessagetype("test");
        specificConfig.setEnabled(true);

        OutputAdapterConfig blankGlobalConfig = new OutputAdapterConfig();
        blankGlobalConfig.setId(2L);
        blankGlobalConfig.setType("BlankGlobalAdapter");
        blankGlobalConfig.setMessagetype(" ");
        blankGlobalConfig.setEnabled(true);

        when(appProp.getOutput()).thenReturn(List.of(specificConfig, blankGlobalConfig));

        AtomicInteger sendCount = new AtomicInteger(0);
        OutputAdapter specificAdapter = new OutputAdapter(Map.of("messagetype", "test", "id", "1")) {
            @Override
            public void send(LogEvent logEvent) {
                sendCount.incrementAndGet();
            }

            @Override
            public void close() throws IOException {}
        };
        OutputAdapter blankGlobalAdapter = new OutputAdapter(Map.of("messagetype", " ", "id", "2")) {
            @Override
            public void send(LogEvent logEvent) {
                sendCount.incrementAndGet();
            }

            @Override
            public void close() throws IOException {}
        };

        try (MockedStatic<OutputFactory> mockedFactory = mockStatic(OutputFactory.class)) {
            mockedFactory.when(() -> OutputFactory.getOutputAdapter(any())).thenAnswer(invocation -> {
                OutputAdapterConfig config = invocation.getArgument(0);
                return config.getId() == 1L ? specificAdapter : blankGlobalAdapter;
            });

            outputAdapterComponent.startPipeline();
            OutputAdapterComponent.DeliverySummary summary =
                    outputAdapterComponent.deliver(new LogEvent("test message", "localhost", "test"));

            assertEquals(2, sendCount.get());
            assertEquals(2, summary.attempted());
            assertEquals(2, summary.succeeded());
            assertFalse(summary.hasFailures());

            outputAdapterComponent.close();
        }
    }

    @Test
    void testDeliveryContinuesWhenOneAdapterFails() throws Exception {
        OutputAdapterConfig failingConfig = new OutputAdapterConfig();
        failingConfig.setId(1L);
        failingConfig.setType("FailingAdapter");
        failingConfig.setMessagetype("test");
        failingConfig.setEnabled(true);

        OutputAdapterConfig succeedingConfig = new OutputAdapterConfig();
        succeedingConfig.setId(2L);
        succeedingConfig.setType("SucceedingAdapter");
        succeedingConfig.setMessagetype("test");
        succeedingConfig.setEnabled(true);

        when(appProp.getOutput()).thenReturn(List.of(failingConfig, succeedingConfig));

        AtomicInteger successCount = new AtomicInteger(0);

        OutputAdapter failingAdapter = new OutputAdapter(Map.of("messagetype", "test", "id", "1")) {
            @Override
            public void send(LogEvent logEvent) {
                throw new OutputDeliveryException("forced failure");
            }

            @Override
            public void close() throws IOException {}
        };

        OutputAdapter succeedingAdapter = new OutputAdapter(Map.of("messagetype", "test", "id", "2")) {
            @Override
            public void send(LogEvent logEvent) {
                successCount.incrementAndGet();
            }

            @Override
            public void close() throws IOException {}
        };

        try (MockedStatic<OutputFactory> mockedFactory = mockStatic(OutputFactory.class)) {
            mockedFactory.when(() -> OutputFactory.getOutputAdapter(any())).thenAnswer(invocation -> {
                OutputAdapterConfig config = invocation.getArgument(0);
                if (config.getId() == 1L) {
                    return failingAdapter;
                }
                return succeedingAdapter;
            });

            outputAdapterComponent.startPipeline();
            OutputAdapterComponent.DeliverySummary summary =
                    outputAdapterComponent.deliver(new LogEvent("test message", "localhost", "test"));

            assertEquals(2, summary.attempted());
            assertEquals(1, summary.succeeded());
            assertEquals(1, summary.failed());
            assertTrue(summary.hasFailures());
            assertEquals(1, successCount.get(), "Second adapter should still receive the message");

            outputAdapterComponent.close();
        }
    }

    @Test
    void testSlowSinkIncreasesDeliveryLatency() throws Exception {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setId(1L);
        config.setType("SlowAdapter");
        config.setMessagetype("test");
        config.setEnabled(true);

        when(appProp.getOutput()).thenReturn(List.of(config));

        OutputAdapter slowAdapter = new OutputAdapter(Map.of("messagetype", "test", "id", "1")) {
            @Override
            public void send(LogEvent logEvent) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OutputDeliveryException("interrupted", e);
                }
            }

            @Override
            public void close() throws IOException {}
        };

        try (MockedStatic<OutputFactory> mockedFactory = mockStatic(OutputFactory.class)) {
            mockedFactory.when(() -> OutputFactory.getOutputAdapter(any())).thenReturn(slowAdapter);

            outputAdapterComponent.startPipeline();
            long startedAt = System.nanoTime();
            OutputAdapterComponent.DeliverySummary summary =
                    outputAdapterComponent.deliver(new LogEvent("test message", "localhost", "test"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            OutputAdapterComponent.AdapterMetricsSnapshot metrics = outputAdapterComponent.getAdapterMetrics().get(0);

            assertEquals(1, summary.attempted());
            assertEquals(1, summary.succeeded());
            assertTrue(elapsedMs >= 140, "Synchronous delivery should wait for the slow sink");
            assertTrue(metrics.lastLatencyMs() != null && metrics.lastLatencyMs() >= 140);
            assertTrue(metrics.averageLatencyMs() != null && metrics.averageLatencyMs() >= 140.0);

            outputAdapterComponent.close();
        }
    }
}
