package org.keinus.logparser.application.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Function<OutputAdapterConfig, OutputAdapter> factory;
    private final Supplier<ScheduledExecutorService> retryExecutors;

    private final Map<String, CopyOnWriteArrayList<OutputDeliveryWorker>> specificAdapterMap = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<OutputDeliveryWorker> globalAdapterList = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OutputDeliveryWorker> allAdapters = new CopyOnWriteArrayList<>();
    private final Map<Long, OutputDeliveryWorker> adapterIdMap = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public record DeliverySummary(int attempted, int succeeded, int failed, int queued) {
        public DeliverySummary(int attempted, int succeeded, int failed) {
            this(attempted, succeeded, failed, 0);
        }
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
            Double averageLatencyMs,
            long consecutiveFailures,
            Long nextRetryAt,
            int pendingCount,
            long pendingBytes,
            long rejectedCount
    ) {}

    @Autowired
    public OutputAdapterComponent(ApplicationProperties appProp) {
        this(appProp, OutputFactory::getOutputAdapter, () -> {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                    Thread.ofVirtual().name("output-retry-", 0).factory());
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        });
    }

    OutputAdapterComponent(ApplicationProperties appProp, Function<OutputAdapterConfig, OutputAdapter> factory,
                           Supplier<ScheduledExecutorService> retryExecutors) {
        this.appProp = appProp;
        this.factory = factory;
        this.retryExecutors = retryExecutors;
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
        int queued = 0;
        List<OutputDeliveryWorker> entries = new java.util.ArrayList<>();
        if (logEvent.getMessageType() != null) {
            List<OutputDeliveryWorker> specific = specificAdapterMap.get(logEvent.getMessageType());
            if (specific != null) { entries.addAll(specific); }
        }
        entries.addAll(globalAdapterList);
        for (OutputDeliveryWorker entry : entries) {
            attempted++;
            switch (entry.deliver(logEvent)) {
                case SENT -> succeeded++;
                case QUEUED -> queued++;
                case REJECTED -> failed++;
            }
        }
        return new DeliverySummary(attempted, succeeded, failed, queued);
    }

    public List<AdapterMetricsSnapshot> getAdapterMetrics() {
        return allAdapters.stream()
                .map(OutputDeliveryWorker::snapshot)
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

                addAdapterInternal(config);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addAdapterInternal(OutputAdapterConfig config) {
        OutputDeliveryWorker entry = new OutputDeliveryWorker(config, factory, retryExecutors.get());
        String messageType = entry.messageType();
        if (isGlobalType(messageType)) {
            globalAdapterList.add(entry);
        } else {
            specificAdapterMap.computeIfAbsent(messageType, key -> new CopyOnWriteArrayList<>()).add(entry);
        }
        allAdapters.add(entry);
        if (entry.id() != null) { adapterIdMap.put(entry.id(), entry); }
        // Register before initialization, so unavailable sinks remain visible and receive a retry queue.
        entry.start();
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
        for (OutputDeliveryWorker entry : allAdapters) {
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
            if (config.getId() != null && adapterIdMap.containsKey(config.getId())) {
                log.debug("Output adapter id={} is already registered", config.getId());
                return;
            }
            addAdapterInternal(config);
            log.info("Added output adapter: id={}, type={}", config.getId(), config.getType());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeAdapter(Long id) {
        lock.writeLock().lock();
        try {
            OutputDeliveryWorker entry = adapterIdMap.get(id);
            if (entry == null) {
                return;
            }
            removeAdapterEntryLocked(entry);
            log.info("Removed output adapter: id={}", id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restartAdapter(OutputAdapterConfig config) {
        removeAdapter(config.getId());
        addAdapter(config);
    }

    private void removeAdapterEntryLocked(OutputDeliveryWorker entry) {
        Long adapterId = entry.id();
        if (adapterId != null) {
            adapterIdMap.remove(adapterId, entry);
        }

        String messageType = entry.messageType();
        if (isGlobalType(messageType)) {
            globalAdapterList.remove(entry);
        } else {
            List<OutputDeliveryWorker> entries = specificAdapterMap.get(messageType);
            if (entries != null) {
                entries.remove(entry);
                if (entries.isEmpty()) {
                    specificAdapterMap.remove(messageType);
                }
            }
        }

        if (allAdapters.remove(entry)) {
            entry.close();
        }
    }
}
