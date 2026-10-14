package org.keinus.logparser.application.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.output.model.OutputAdapter;
import lombok.extern.slf4j.Slf4j;

/** Serial delivery and bounded, independent recovery for one configured output. */
@Slf4j
final class OutputDeliveryWorker {
    static final int MAX_PENDING_EVENTS = 10_000;
    static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final int DRAIN_BATCH_SIZE = 100;

    enum Result { SENT, QUEUED, REJECTED }
    private record Pending(LogEvent event, long bytes) {}

    private final OutputAdapterConfig config;
    private final Function<OutputAdapterConfig, OutputAdapter> factory;
    private final ScheduledExecutorService executor;
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final Deque<Pending> pending = new ArrayDeque<>();
    private OutputAdapter adapter;
    private ScheduledFuture<?> retryTask;
    private boolean flushPending;
    private volatile boolean closed;
    private volatile long sentCount;
    private volatile long failedCount;
    private volatile long rejectedCount;
    private volatile long totalLatencyNanos;
    private volatile long lastSuccessAt;
    private volatile long lastFailureAt;
    private volatile long lastLatencyMs;
    private volatile String lastError;
    private volatile long consecutiveFailures;
    private volatile long nextRetryAt;
    private volatile int pendingCount;
    private volatile long pendingBytes;

    OutputDeliveryWorker(OutputAdapterConfig config, Function<OutputAdapterConfig, OutputAdapter> factory,
                         ScheduledExecutorService executor) {
        this.config = config;
        this.factory = factory;
        this.executor = executor;
    }

    Long id() { return config.getId(); }
    String messageType() {
        String type = config.getMessagetype();
        return type == null || type.isBlank() ? "all" : type;
    }

    void start() {
        deliveryLock.lock();
        try {
            if (!closed) {
                try { initialize(); }
                catch (Exception failure) { fail(failure); }
            }
        } finally { deliveryLock.unlock(); }
    }

    private void initialize() {
        adapter = factory.apply(config);
        adapter.setBackgroundFailureHandler(this::backgroundFailure);
    }

    Result deliver(LogEvent event) {
        deliveryLock.lock();
        try {
            if (closed) { return Result.REJECTED; }
            if (retryTask != null || adapter == null || !pending.isEmpty()) {
                return enqueue(event) ? Result.QUEUED : Result.REJECTED;
            }
            if (send(event)) { return Result.SENT; }
            return enqueue(event) ? Result.QUEUED : Result.REJECTED;
        } finally { deliveryLock.unlock(); }
    }

    private boolean enqueue(LogEvent event) {
        long bytes;
        try {
            // Estimate retained UTF-16 payload + original text, with per-event overhead.
            bytes = 512L + 2L * event.toOutputJson().length()
                    + (event.getOriginalText() == null ? 0 : 2L * event.getOriginalText().length());
        } catch (RuntimeException invalidPayload) {
            return reject("output payload cannot be serialized");
        }
        if (pending.size() >= MAX_PENDING_EVENTS || bytes > MAX_PENDING_BYTES - pendingBytes) {
            return reject("retry queue limit reached");
        }
        pending.addLast(new Pending(event, bytes));
        pendingCount = pending.size();
        pendingBytes += bytes;
        return true;
    }

    private boolean reject(String reason) {
        rejectedCount++;
        // Bound logging during a prolonged outage without hiding the rejection count.
        if ((rejectedCount & (rejectedCount - 1)) == 0) {
            log.warn("Output adapter id={} rejected {} events: {}", id(), rejectedCount, reason);
        }
        return false;
    }

    private boolean send(LogEvent event) {
        long started = System.nanoTime();
        try {
            adapter.send(event);
            sentCount++;
            lastSuccessAt = System.currentTimeMillis();
            recovered();
            return true;
        } catch (Exception failure) {
            fail(failure);
            return false;
        } finally {
            long elapsed = System.nanoTime() - started;
            totalLatencyNanos += elapsed;
            lastLatencyMs = elapsed / 1_000_000;
        }
    }

    private void fail(Exception failure) {
        failedCount++;
        consecutiveFailures = Math.min(Long.MAX_VALUE - 1, consecutiveFailures) + 1;
        lastFailureAt = System.currentTimeMillis();
        lastError = failure.getMessage();
        if (adapter != null) { adapter.setBackgroundFlushPaused(true); }
        if (!closed && retryTask == null) {
            long delay = OutputRetryPolicy.delayMillis(consecutiveFailures);
            schedule(delay);
            log.warn("Output adapter id={} type={} failed (consecutive={}); retry in {} ms, remains enabled: {}",
                    id(), config.getType(), consecutiveFailures, delay, failure.toString());
        }
    }

    private void schedule(long delay) {
        if (closed) { return; }
        nextRetryAt = System.currentTimeMillis() + delay;
        try {
            retryTask = executor.schedule(this::retry, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            nextRetryAt = 0;
            if (!closed) { throw stopping; }
        }
    }

    private void backgroundFailure(RuntimeException failure) {
        deliveryLock.lock();
        try {
            if (closed) { return; }
            flushPending = true;
            // An already scheduled retry owns recovery, including any retained batches.
            if (retryTask == null) { fail(failure); }
        } finally { deliveryLock.unlock(); }
    }

    private void retry() {
        deliveryLock.lock();
        try {
            retryTask = null;
            nextRetryAt = 0;
            if (closed) { return; }
            try {
                if (adapter == null) { initialize(); }
                if (flushPending) {
                    adapter.retryPendingFlush();
                    flushPending = false;
                    lastSuccessAt = System.currentTimeMillis();
                }
            } catch (Exception failure) {
                fail(failure);
                return;
            }
            for (int i = 0; i < DRAIN_BATCH_SIZE && !pending.isEmpty() && !closed; i++) {
                Pending head = pending.peekFirst();
                if (!send(head.event())) { return; }
                pending.removeFirst();
                pendingCount = pending.size();
                pendingBytes -= head.bytes();
            }
            if (!closed && !pending.isEmpty()) {
                schedule(0);
            } else if (!closed) {
                recovered();
            }
        } finally { deliveryLock.unlock(); }
    }

    private void recovered() {
        if (consecutiveFailures > 0) {
            log.info("Output adapter id={} recovered after {} consecutive failures", id(), consecutiveFailures);
        }
        consecutiveFailures = 0;
        lastError = null;
        if (adapter != null) { adapter.setBackgroundFlushPaused(false); }
    }

    OutputAdapterComponent.AdapterMetricsSnapshot snapshot() {
        long attempts = sentCount + failedCount;
        return new OutputAdapterComponent.AdapterMetricsSnapshot(id(), config.getType(), messageType(),
                sentCount, failedCount, lastError, timestamp(lastSuccessAt), timestamp(lastFailureAt),
                attempts == 0 ? null : lastLatencyMs,
                attempts == 0 ? null : totalLatencyNanos / 1_000_000.0 / attempts,
                consecutiveFailures, timestamp(nextRetryAt), pendingCount, pendingBytes, rejectedCount);
    }

    private static Long timestamp(long value) { return value == 0 ? null : value; }

    void close() {
        // Interrupt the scheduled send before acquiring its lock; never wait out a backoff.
        closed = true;
        executor.shutdownNow();
        deliveryLock.lock();
        try {
            if (retryTask != null) { retryTask.cancel(true); retryTask = null; }
            nextRetryAt = 0;
            if (!pending.isEmpty()) {
                log.warn("Closing output adapter id={} with {} in-memory retry events", id(), pending.size());
                rejectedCount += pending.size();
                pending.clear();
                pendingCount = 0;
                pendingBytes = 0;
            }
            if (adapter != null) {
                adapter.setBackgroundFailureHandler(null);
                try { adapter.close(); }
                catch (Exception e) { log.warn("Error closing output adapter id={}: {}", id(), e.toString()); }
                adapter = null;
            }
        } finally { deliveryLock.unlock(); }
    }
}
