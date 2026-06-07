package org.keinus.logparser.application.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.output.model.OutputAdapter;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.domain.output.service.OutputFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 출력 어댑터를 관리하고 동기 fan-out 전송을 수행하는 컴포넌트입니다.
 */
@Slf4j
@Component
@Order(1)
public class OutputAdapterComponent implements ApplicationListener<ApplicationReadyEvent> {

    private static final String DEFAULT_MESSAGE_TYPE = "all";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ApplicationProperties appProp;

    private final Map<String, CopyOnWriteArrayList<AdapterEntry>> specificAdapterMap = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AdapterEntry> globalAdapterList = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AdapterEntry> allAdapters = new CopyOnWriteArrayList<>();
    private final Map<Long, AdapterEntry> adapterIdMap = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final class AdapterEntry {
        private final OutputAdapter adapter;
        private final ReentrantLock deliveryLock = new ReentrantLock();
        private final AtomicLong sentCount = new AtomicLong(0);
        private final AtomicLong failedCount = new AtomicLong(0);
        private final AtomicLong totalLatencyNanos = new AtomicLong(0);
        private final AtomicLong lastSuccessAt = new AtomicLong(0);
        private final AtomicLong lastFailureAt = new AtomicLong(0);
        private final AtomicLong lastLatencyMs = new AtomicLong(0);
        private final AtomicReference<String> lastError = new AtomicReference<>(null);

        private AdapterEntry(OutputAdapter adapter) {
            this.adapter = adapter;
        }

        private void deliver(LogEvent logEvent) {
            deliveryLock.lock();
            long startedAt = System.nanoTime();
            try {
                adapter.send(logEvent);
                sentCount.incrementAndGet();
                lastSuccessAt.set(System.currentTimeMillis());
                lastError.set(null);
            } catch (Exception e) {
                failedCount.incrementAndGet();
                lastFailureAt.set(System.currentTimeMillis());
                lastError.set(e.getMessage());
                throw e;
            } finally {
                long elapsedNanos = System.nanoTime() - startedAt;
                totalLatencyNanos.addAndGet(elapsedNanos);
                lastLatencyMs.set(elapsedNanos / 1_000_000);
                deliveryLock.unlock();
            }
        }

        private AdapterMetricsSnapshot snapshot() {
            long attempts = sentCount.get() + failedCount.get();
            Double averageLatencyMs = attempts > 0
                    ? totalLatencyNanos.get() / 1_000_000.0 / attempts
                    : null;
            return new AdapterMetricsSnapshot(
                    adapter.getId(),
                    adapter.getClass().getSimpleName(),
                    adapter.getMessageType(),
                    sentCount.get(),
                    failedCount.get(),
                    lastError.get(),
                    toNullableTimestamp(lastSuccessAt.get()),
                    toNullableTimestamp(lastFailureAt.get()),
                    toNullableTimestamp(lastLatencyMs.get()),
                    averageLatencyMs
            );
        }

        private static Long toNullableTimestamp(long value) {
            return value > 0 ? value : null;
        }

        private void close() {
            deliveryLock.lock();
            try {
                adapter.close();
            } catch (Exception e) {
                log.error("Error closing output adapter {}: {}", adapter, e.getMessage(), e);
            } finally {
                deliveryLock.unlock();
            }
        }
    }

    public record DeliverySummary(int attempted, int succeeded, int failed) {
        public boolean hasFailures() {
            return failed > 0;
        }
    }

    public record AdapterMetricsSnapshot(
            Long adapterId,
            String adapterName,
            String messageType,
            long sentCount,
            long failedCount,
            String lastError,
            Long lastSuccessAt,
            Long lastFailureAt,
            Long lastLatencyMs,
            Double averageLatencyMs
    ) {}

    public OutputAdapterComponent(ApplicationProperties appProp) {
        this.appProp = appProp;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("ApplicationReadyEvent received, starting output adapters");
        startPipeline();
    }

    public void startPipeline() {
        if (!running.compareAndSet(false, true)) {
            log.info("Output adapters already running");
            return;
        }

        log.info("Starting output adapters");
        initializeOutputAdapters();
        log.info("Output adapters started successfully with {} adapters", allAdapters.size());
    }

    @PreDestroy
    public void stopPipeline() {
        try {
            close();
            log.info("All output adapters stopped successfully");
        } catch (Exception e) {
            log.error("Error during output pipeline shutdown", e);
        }
    }

    public DeliverySummary deliver(LogEvent logEvent) {
        if (logEvent == null) {
            return new DeliverySummary(0, 0, 0);
        }

        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        String messageType = logEvent.getMessageType();

        List<AdapterEntry> specificEntries = specificAdapterMap.get(messageType);
        if (specificEntries != null) {
            for (AdapterEntry entry : specificEntries) {
                attempted++;
                if (deliverToEntry(entry, logEvent)) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
        }

        for (AdapterEntry entry : globalAdapterList) {
            attempted++;
            if (deliverToEntry(entry, logEvent)) {
                succeeded++;
            } else {
                failed++;
            }
        }

        return new DeliverySummary(attempted, succeeded, failed);
    }

    public List<AdapterMetricsSnapshot> getAdapterMetrics() {
        return allAdapters.stream()
                .map(AdapterEntry::snapshot)
                .toList();
    }

    /**
     * 컴포넌트 종료
     */
    public void close() {
        if (!running.compareAndSet(true, false)) {
            log.debug("OutputAdapterComponent already stopped");
        }

        log.info("Shutting down OutputAdapterComponent...");
        closeAllAdapters();
        log.info("OutputAdapterComponent shutdown completed");
    }

    private boolean deliverToEntry(AdapterEntry entry, LogEvent logEvent) {
        try {
            entry.deliver(logEvent);
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to deliver event to output adapter {} for messageType {}: {}",
                    entry.adapter,
                    getDisplayMessageType(logEvent.getMessageType()),
                    e.getMessage(),
                    e
            );
            return false;
        }
    }

    private void initializeOutputAdapters() {
        List<OutputAdapterConfig> outputConfigs = appProp.getOutput();

        if (outputConfigs == null || outputConfigs.isEmpty()) {
            log.warn("No output adapters configured in ApplicationProperties");
            return;
        }

        log.info("Initializing {} output adapters", outputConfigs.size());

        lock.writeLock().lock();
        try {
            closeAllAdaptersLocked();

            for (OutputAdapterConfig config : outputConfigs) {
                if (!Boolean.TRUE.equals(config.getEnabled())) {
                    continue;
                }

                try {
                    log.info(
                            "Creating OutputAdapter for type: {}, messageType: {}",
                            config.getType(),
                            config.getMessagetype()
                    );
                    OutputAdapter adapter = OutputFactory.getOutputAdapter(config);
                    addAdapterInternal(adapter);
                } catch (Exception e) {
                    log.error("Failed to initialize OutputAdapter {}: {}", config.getType(), e.getMessage(), e);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addAdapterInternal(OutputAdapter adapter) {
        AdapterEntry entry = new AdapterEntry(adapter);
        String messageType = adapter.getMessageType();

        if (isGlobalType(messageType)) {
            globalAdapterList.add(entry);
            log.info("OutputAdapter {} registered as GLOBAL adapter", adapter.getClass().getSimpleName());
        } else {
            specificAdapterMap.computeIfAbsent(messageType, key -> new CopyOnWriteArrayList<>()).add(entry);
            log.info(
                    "OutputAdapter {} registered for message type: {}",
                    adapter.getClass().getSimpleName(),
                    messageType
            );
        }

        allAdapters.add(entry);
        if (adapter.getId() != null) {
            adapterIdMap.put(adapter.getId(), entry);
        }
    }

    private boolean isGlobalType(String type) {
        return type == null || type.isBlank() || DEFAULT_MESSAGE_TYPE.equalsIgnoreCase(type.trim());
    }

    private void closeAllAdapters() {
        lock.writeLock().lock();
        try {
            closeAllAdaptersLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void closeAllAdaptersLocked() {
        for (AdapterEntry entry : allAdapters) {
            entry.close();
        }

        specificAdapterMap.clear();
        globalAdapterList.clear();
        allAdapters.clear();
        adapterIdMap.clear();
    }

    public void addAdapter(OutputAdapterConfig config) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return;
        }

        lock.writeLock().lock();
        try {
            OutputAdapter adapter = OutputFactory.getOutputAdapter(config);
            addAdapterInternal(adapter);
            log.info("Added output adapter: id={}, type={}", adapter.getId(), adapter.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Failed to add output adapter", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeAdapter(Long id) {
        lock.writeLock().lock();
        try {
            AdapterEntry entry = adapterIdMap.remove(id);
            if (entry == null) {
                return;
            }

            String messageType = entry.adapter.getMessageType();
            if (isGlobalType(messageType)) {
                globalAdapterList.remove(entry);
            } else {
                List<AdapterEntry> entries = specificAdapterMap.get(messageType);
                if (entries != null) {
                    entries.remove(entry);
                    if (entries.isEmpty()) {
                        specificAdapterMap.remove(messageType);
                    }
                }
            }

            allAdapters.remove(entry);
            entry.close();
            log.info("Removed output adapter: id={}", id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restartAdapter(OutputAdapterConfig config) {
        removeAdapter(config.getId());
        addAdapter(config);
    }

    private String getDisplayMessageType(String messageType) {
        return messageType != null ? messageType : DEFAULT_MESSAGE_TYPE;
    }
}
