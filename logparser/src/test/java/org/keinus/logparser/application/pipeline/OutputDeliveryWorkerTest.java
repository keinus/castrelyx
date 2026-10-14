package org.keinus.logparser.application.pipeline;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.output.model.OutputAdapter;
import org.keinus.logparser.domain.output.model.OutputDeliveryException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutputDeliveryWorkerTest {
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<OutputDeliveryWorker> workers = new ArrayList<>();

    @AfterEach void closeWorkers() { workers.forEach(OutputDeliveryWorker::close); }

    private OutputDeliveryWorker worker(Function<OutputAdapterConfig, OutputAdapter> factory) {
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tasks.addLast(invocation.getArgument(0));
                    delays.add(invocation.getArgument(1));
                    return mock(ScheduledFuture.class);
                });
        OutputDeliveryWorker worker = new OutputDeliveryWorker(config(), factory, scheduler);
        workers.add(worker);
        worker.start();
        return worker;
    }

    private static OutputAdapterConfig config() {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setId(42L);
        config.setType("TestOutput");
        config.setMessagetype("test");
        config.setEnabled(true);
        return config;
    }

    private static LogEvent event(String text) { return new LogEvent(text, "localhost", "test"); }

    private static OutputAdapter adapter(Consumer<LogEvent> send) throws IOException {
        return new OutputAdapter(Map.of("id", "42", "messagetype", "test")) {
            @Override public void send(LogEvent event) { send.accept(event); }
            @Override public void close() {}
        };
    }

    @Test void retriesTheSameEventWithoutNewInputAndBacksOffToOneHourIndefinitely() throws Exception {
        List<LogEvent> attempted = new ArrayList<>();
        OutputAdapter output = adapter(event -> {
            attempted.add(event);
            throw new OutputDeliveryException("offline");
        });
        var worker = worker(ignored -> output);
        LogEvent original = event("keep me");
        assertEquals(OutputDeliveryWorker.Result.QUEUED, worker.deliver(original));
        for (int i = 0; i < 15; i++) { tasks.removeFirst().run(); }
        assertEquals(List.of(5000L, 5000L, 5000L, 10000L, 20000L, 40000L, 80000L,
                160000L, 320000L, 640000L, 1280000L, 2560000L, 3600000L,
                3600000L, 3600000L, 3600000L), delays);
        assertTrue(attempted.stream().allMatch(e -> e == original));
        assertEquals(16, worker.snapshot().failedCount());
        assertEquals(1, worker.snapshot().pendingCount());
        assertNotNull(worker.snapshot().nextRetryAt());
        assertEquals(1, tasks.size());
        assertEquals(3_600_000, OutputRetryPolicy.delayMillis(Long.MAX_VALUE));
    }

    @Test void incomingEventsDoNotBypassBackoffAndDrainInOrderAfterRecovery() throws Exception {
        AtomicInteger failures = new AtomicInteger(4);
        List<String> delivered = new ArrayList<>();
        OutputAdapter output = adapter(event -> {
            if (failures.getAndDecrement() > 0) { throw new OutputDeliveryException("offline"); }
            delivered.add(event.getOriginalText());
        });
        var worker = worker(ignored -> output);
        worker.deliver(event("first"));
        for (int i = 0; i < 3; i++) { tasks.removeFirst().run(); }
        assertEquals(10000, delays.getLast());
        assertEquals(OutputDeliveryWorker.Result.QUEUED, worker.deliver(event("second")));
        assertEquals(4, worker.snapshot().failedCount());
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertEquals(List.of("first", "second"), delivered);
        assertEquals(0, worker.snapshot().pendingCount());
        assertEquals(0, worker.snapshot().pendingBytes());
        assertEquals(0, worker.snapshot().consecutiveFailures());
        assertNull(worker.snapshot().nextRetryAt());
        assertNull(worker.snapshot().lastError());
        failures.set(1);
        worker.deliver(event("third"));
        assertEquals(5000, delays.getLast());
    }

    @Test void initializationFailureRetriesEvenWithoutMessagesAndKeepsConfigEnabled() throws Exception {
        var seenConfig = new java.util.concurrent.atomic.AtomicReference<OutputAdapterConfig>();
        AtomicInteger creations = new AtomicInteger();
        OutputAdapter output = adapter(ignored -> {});
        var worker = worker(configuration -> {
            seenConfig.set(configuration);
            if (creations.incrementAndGet() < 3) { throw new IllegalStateException("unavailable"); }
            return output;
        });
        tasks.removeFirst().run();
        tasks.removeFirst().run();
        assertTrue(seenConfig.get().getEnabled());
        assertEquals(3, creations.get());
        assertEquals(0, worker.snapshot().consecutiveFailures());
        assertEquals(OutputDeliveryWorker.Result.SENT, worker.deliver(event("after recovery")));
    }

    @Test void closingCancelsRecoveryAndDoesNotResurrectTheAdapter() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        OutputAdapter output = adapter(ignored -> {
            sends.incrementAndGet();
            throw new OutputDeliveryException("offline");
        });
        var worker = worker(ignored -> output);
        worker.deliver(event("pending"));
        Runnable staleTask = tasks.removeFirst();
        worker.close();
        staleTask.run();
        assertEquals(1, sends.get());
        assertEquals(OutputDeliveryWorker.Result.REJECTED, worker.deliver(event("late")));
        assertNull(worker.snapshot().nextRetryAt());
        verify(scheduler, atLeastOnce()).shutdownNow();
    }

    @Test void retryQueueIsBoundedAndRejectsNewEventsWithoutLosingTheOldest() throws Exception {
        OutputAdapter output = adapter(ignored -> { throw new OutputDeliveryException("offline"); });
        var worker = worker(ignored -> output);
        for (int i = 0; i < OutputDeliveryWorker.MAX_PENDING_EVENTS; i++) {
            assertEquals(OutputDeliveryWorker.Result.QUEUED, worker.deliver(event("event" + i)));
        }
        assertEquals(OutputDeliveryWorker.Result.REJECTED, worker.deliver(event("overflow")));
        assertEquals(OutputDeliveryWorker.MAX_PENDING_EVENTS, worker.snapshot().pendingCount());
        assertEquals(1, worker.snapshot().rejectedCount());
        assertEquals(1, worker.snapshot().failedCount());
        assertEquals(1, tasks.size());
    }

    @Test void retryQueueAlsoBoundsBytes() throws Exception {
        OutputAdapter output = adapter(ignored -> { throw new OutputDeliveryException("offline"); });
        var worker = worker(ignored -> output);
        LogEvent large = event("x".repeat(1024 * 1024));
        while (worker.deliver(large) == OutputDeliveryWorker.Result.QUEUED) { /* fill */ }
        assertTrue(worker.snapshot().pendingCount() < OutputDeliveryWorker.MAX_PENDING_EVENTS);
        assertTrue(worker.snapshot().pendingBytes() <= OutputDeliveryWorker.MAX_PENDING_BYTES);
        assertEquals(1, worker.snapshot().rejectedCount());
    }

    @Test void backgroundBatchFailuresUseTheSameBackoffAndRecoverWithoutNewInput() throws Exception {
        class BufferedOutput extends OutputAdapter {
            int flushes;
            BufferedOutput() throws IOException { super(Map.of()); }
            @Override public void send(LogEvent event) {}
            @Override public void close() {}
            void failedFlush() { reportBackgroundFailure(new OutputDeliveryException("flush failed")); }
            @Override public void retryPendingFlush() {
                if (++flushes < 3) { throw new OutputDeliveryException("still offline"); }
            }
            boolean paused() { return backgroundFlushPaused; }
        }
        var output = new BufferedOutput();
        var worker = worker(ignored -> output);
        output.failedFlush();
        assertTrue(output.paused());
        for (int i = 0; i < 3; i++) { tasks.removeFirst().run(); }
        assertEquals(3, output.flushes);
        assertFalse(output.paused());
        assertEquals(0, worker.snapshot().consecutiveFailures());
        assertNull(worker.snapshot().nextRetryAt());
    }

    @Test void realSchedulerRecoversWithoutAnotherDeliveryCall() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        OutputAdapter output = adapter(ignored -> {
            if (attempts.incrementAndGet() == 1) { throw new OutputDeliveryException("once"); }
            recovered.countDown();
        });
        ScheduledThreadPoolExecutor fastClock = new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return super.schedule(command, 10, TimeUnit.MILLISECONDS);
            }
        };
        var worker = new OutputDeliveryWorker(config(), ignored -> output, fastClock);
        workers.add(worker);
        worker.start();
        worker.deliver(event("retry automatically"));
        assertTrue(recovered.await(5, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }
}
