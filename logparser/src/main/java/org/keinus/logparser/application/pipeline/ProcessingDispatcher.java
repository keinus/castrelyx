package org.keinus.logparser.application.pipeline;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.keinus.logparser.application.service.LiveTailService;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 입력 큐에서 이벤트를 가져와 파싱, 변환, 동기 출력 전송까지 수행하는 워커입니다.
 */
public class ProcessingDispatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ProcessingDispatcher.class);

    private final BlockingQueue<LogEvent> inputQueue;
    private final ParseService parseService;
    private final TransformService transformService;
    private final StructuredTransformService structuredTransformService;
    private final LiveTailService liveTailService;
    private final OutputAdapterComponent outputAdapterComponent;
    private final AtomicBoolean running;
    private final AtomicInteger inFlightMessages;
    private final AtomicLong totalMessagesDropped;
    private final AtomicLong totalMessagesFailed;
    private final AtomicLong processedMessageCount;

    public ProcessingDispatcher(
            BlockingQueue<LogEvent> inputQueue,
            ParseService parseService,
            TransformService transformService,
            StructuredTransformService structuredTransformService,
            LiveTailService liveTailService,
            OutputAdapterComponent outputAdapterComponent,
            AtomicBoolean running,
            AtomicInteger inFlightMessages,
            AtomicLong totalMessagesDropped,
            AtomicLong totalMessagesFailed,
            AtomicLong processedMessageCount) {
        this.inputQueue = inputQueue;
        this.parseService = parseService;
        this.transformService = transformService;
        this.structuredTransformService = structuredTransformService;
        this.liveTailService = liveTailService;
        this.outputAdapterComponent = outputAdapterComponent;
        this.running = running;
        this.inFlightMessages = inFlightMessages;
        this.totalMessagesDropped = totalMessagesDropped;
        this.totalMessagesFailed = totalMessagesFailed;
        this.processedMessageCount = processedMessageCount;
    }

    @Override
    public void run() {
        log.info("Processing thread started: {}", Thread.currentThread().getName());
        while (running.get()) {
            try {
                LogEvent logEvent = inputQueue.take();
                inFlightMessages.incrementAndGet();
                try {
                    processEvent(logEvent);
                } finally {
                    inFlightMessages.decrementAndGet();
                }
            } catch (InterruptedException e) {
                log.info("Processing thread interrupted: {}", Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                totalMessagesFailed.incrementAndGet();
                log.error("Unexpected error in processing thread", e);
            }
        }
        log.info("Processing thread finished: {}", Thread.currentThread().getName());
    }

    private void processEvent(LogEvent logEvent) {
        try {
            boolean parseResult = parseService.parse(logEvent);
            if (!parseResult) {
                logEvent.markAsError("Parsing failed");
                totalMessagesFailed.incrementAndGet();
                log.error("Failed to parse log event: {}", logEvent);
                return;
            }
            logEvent.markAsParsed();

            boolean transformResult = transformService.transform(logEvent);
            if (!transformResult) {
                log.debug("Log event filtered out by transform service");
                return;
            }

            if (structuredTransformService != null) {
                boolean structuredResult = structuredTransformService.applyToLogEvent(logEvent);
                if (!structuredResult) {
                    log.warn("Structured transformation failed for event: {}", logEvent);
                }
            }
            logEvent.markAsTransformed();
            logEvent.prepareOutputPayload();

            OutputAdapterComponent.DeliverySummary deliverySummary = outputAdapterComponent.deliver(logEvent);
            if (deliverySummary.attempted() == 0) {
                totalMessagesDropped.incrementAndGet();
                log.debug("No output adapters matched event type: {}", logEvent.getMessageType());
            }
            if (deliverySummary.hasFailures()) {
                totalMessagesFailed.incrementAndGet();
            }

            processedMessageCount.incrementAndGet();

            if (liveTailService != null) {
                try {
                    liveTailService.broadcastLog(logEvent);
                } catch (Exception e) {
                    log.warn("Failed to broadcast live tail message: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error processing log event: {}", logEvent, e);
            logEvent.markAsError("Processing error: " + e.getMessage());
            totalMessagesFailed.incrementAndGet();
        }
    }
}
