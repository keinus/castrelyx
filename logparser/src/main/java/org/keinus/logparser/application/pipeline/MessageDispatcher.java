package org.keinus.logparser.application.pipeline;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.keinus.logparser.application.service.LiveTailService;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.keinus.logparser.infrastructure.util.ThreadUtil;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 입력 메시지를 큐잉하고 처리 워커에 분배하는 중앙 디스패처입니다.
 */
@Slf4j
@Service
public class MessageDispatcher {
    private static final long WORKER_STOP_TIMEOUT_MS = 30_000;
    private static final long QUEUE_MONITORING_INTERVAL_MS = 30_000;

    private final BlockingQueue<LogEvent> inputMessageQueue;
    private final OutputAdapterComponent outputAdapterComponent;
    private final ThreadManager threadManager;
    private final ParseService parseService;
    private final TransformService transformService;
    private final StructuredTransformService structuredTransformService;
    private final LiveTailService liveTailService;
    private final ApplicationProperties applicationProperties;
    private final int queueSize;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean workerActive;

    private final AtomicLong totalMessagesDropped = new AtomicLong(0);
    private final AtomicLong totalMessagesFailed = new AtomicLong(0);
    private final AtomicLong processedMessageCount = new AtomicLong(0);
    private final AtomicInteger inFlightMessages = new AtomicInteger(0);

    private volatile double currentOutputThroughput = 0.0;
    private long lastProcessedMessageCount = 0;
    private long lastMonitorTime = System.currentTimeMillis();

    public MessageDispatcher(
            ThreadManager threadManager,
            ParseService parseService,
            TransformService transformService,
            StructuredTransformService structuredTransformService,
            LiveTailService liveTailService,
            ApplicationProperties applicationProperties,
            OutputAdapterComponent outputAdapterComponent,
            @Value("${log.message.queue-size:10000}") int queueSize
    ) {
        this.threadManager = threadManager;
        this.parseService = parseService;
        this.transformService = transformService;
        this.structuredTransformService = structuredTransformService;
        this.liveTailService = liveTailService;
        this.applicationProperties = applicationProperties;
        this.outputAdapterComponent = outputAdapterComponent;
        this.queueSize = queueSize;
        this.inputMessageQueue = new LinkedBlockingQueue<>(queueSize);

        log.info("MessageDispatcher initialized with input queue size: {}", queueSize);
    }

    @PostConstruct
    public void startPipeline() {
        try {
            running.set(true);
            startWorkers();
            threadManager.executeWithName("QueueMonitor", this::monitorQueues);
            log.info("MessageDispatcher started");
        } catch (Exception e) {
            log.error("Failed to initialize message dispatcher", e);
            throw new RuntimeException("ETL Pipeline startup failed", e);
        }
    }

    private synchronized void startWorkers() {
        if (workerActive != null && workerActive.get()) {
            log.warn("Workers are already running. Stop them first.");
            return;
        }

        int processingThreads = applicationProperties.getParserThreads();
        this.workerActive = new AtomicBoolean(true);

        for (int i = 0; i < processingThreads; i++) {
            String threadName = "ProcessingThread-" + (i + 1);
            ProcessingDispatcher processingDispatcher = new ProcessingDispatcher(
                    inputMessageQueue,
                    parseService,
                    transformService,
                    structuredTransformService,
                    liveTailService,
                    outputAdapterComponent,
                    workerActive,
                    inFlightMessages,
                    totalMessagesDropped,
                    totalMessagesFailed,
                    processedMessageCount
            );
            threadManager.executeWithName(threadName, processingDispatcher);
        }

        log.info("Started {} processing threads", processingThreads);
    }

    private synchronized void stopWorkers() {
        if (workerActive != null) {
            log.info("Stopping worker threads...");
            workerActive.set(false);
            threadManager.stopThreadsStartingWith("ProcessingThread-", WORKER_STOP_TIMEOUT_MS);
            log.info("Stopped all processing threads");
        }
    }

    public void updateWorkerThreadCount() {
        log.info("Updating worker threads");
        stopWorkers();
        applicationProperties.loadConfigurationFromDatabase();
        startWorkers();
    }

    public void restartWorkersFromCurrentConfiguration() {
        log.info("Restarting worker threads from current configuration");
        stopWorkers();
        startWorkers();
    }

    public void stopProcessingWorkers() {
        stopWorkers();
    }

    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
        while (System.currentTimeMillis() <= deadline) {
            if (inputMessageQueue.isEmpty() && inFlightMessages.get() == 0) {
                return true;
            }
            ThreadUtil.sleep(50);
        }
        return inputMessageQueue.isEmpty() && inFlightMessages.get() == 0;
    }

    @PreDestroy
    public void stopPipeline() {
        try {
            close();
            log.info("MessageDispatcher stopped successfully");
        } catch (Exception e) {
            log.error("Error during ETL Pipeline shutdown", e);
        }
    }

    public void close() throws IOException {
        running.set(false);
        if (workerActive != null) {
            workerActive.set(false);
        }

        inputMessageQueue.clear();
        threadManager.shutdownAllThreads();
        log.info("Message Dispatcher closed");
    }

    public boolean putInputMsg(LogEvent logEvent) {
        try {
            inputMessageQueue.put(logEvent);
            return true;
        } catch (InterruptedException e) {
            log.debug("Interrupted while putting message to input queue - expected during shutdown");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void monitorQueues() {
        while (running.get()) {
            try {
                ThreadUtil.sleep(QUEUE_MONITORING_INTERVAL_MS);

                int inputQueueSize = inputMessageQueue.size();
                double inputUtilization = (double) inputQueueSize / queueSize;

                long currentCount = processedMessageCount.get();
                long currentTime = System.currentTimeMillis();
                double elapsedSeconds = (currentTime - lastMonitorTime) / 1000.0;
                if (elapsedSeconds > 0) {
                    currentOutputThroughput = (currentCount - lastProcessedMessageCount) / elapsedSeconds;
                }
                lastProcessedMessageCount = currentCount;
                lastMonitorTime = currentTime;

                log.info(
                        "Queue Status - Input: {}/{} ({}%) | Dropped: {}, Failed: {} | Throughput: {}/s",
                        inputQueueSize,
                        queueSize,
                        String.format("%.1f", inputUtilization * 100),
                        totalMessagesDropped.get(),
                        totalMessagesFailed.get(),
                        currentOutputThroughput
                );
            } catch (Exception e) {
                log.error("Error in queue monitoring thread", e);
            }
        }
    }

    public DispatcherMetrics getDispatcherMetrics() {
        return new DispatcherMetrics(
                inputMessageQueue.size(),
                queueSize,
                totalMessagesDropped.get(),
                totalMessagesFailed.get(),
                currentOutputThroughput
        );
    }

    @AllArgsConstructor
    public static class DispatcherMetrics {
        public final int globalQueueSize;
        public final int maxQueueSize;
        public final long totalDropped;
        public final long totalFailed;
        public final double outputThroughput;

        public double getGlobalUtilization() {
            return (double) globalQueueSize / maxQueueSize;
        }

        @Override
        public String toString() {
            return String.format(
                    "DispatcherMetrics{input=%d/%d (%.1f%%), dropped=%d, failed=%d, throughput=%.1f/s}",
                    globalQueueSize,
                    maxQueueSize,
                    getGlobalUtilization() * 100,
                    totalDropped,
                    totalFailed,
                    outputThroughput
            );
        }
    }
}
