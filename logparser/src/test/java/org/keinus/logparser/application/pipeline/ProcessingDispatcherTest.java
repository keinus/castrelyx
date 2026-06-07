package org.keinus.logparser.application.pipeline;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.application.service.LiveTailService;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.domain.transformation.service.TransformService;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingDispatcherTest {

    @Test
    void parseFailureStopsPipelineProcessingForThatEvent() throws Exception {
        ParseService parseService = mock(ParseService.class);
        TransformService transformService = mock(TransformService.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        LiveTailService liveTailService = mock(LiveTailService.class);
        OutputAdapterComponent outputAdapterComponent = mock(OutputAdapterComponent.class);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger inFlightMessages = new AtomicInteger(0);
        AtomicLong dropped = new AtomicLong(0);
        AtomicLong failed = new AtomicLong(0);
        AtomicLong processed = new AtomicLong(0);
        BlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>();
        LogEvent event = new LogEvent("raw", "localhost", "access");
        queue.put(event);

        when(parseService.parse(any(LogEvent.class))).thenAnswer(invocation -> {
            running.set(false);
            return false;
        });

        ProcessingDispatcher dispatcher = new ProcessingDispatcher(
                queue,
                parseService,
                transformService,
                structuredTransformService,
                liveTailService,
                outputAdapterComponent,
                running,
                inFlightMessages,
                dropped,
                failed,
                processed
        );

        runOnce(dispatcher);

        assertEquals(0, dropped.get());
        assertEquals(1, failed.get());
        assertEquals(0, processed.get());
        assertEquals(0, inFlightMessages.get());
        verify(transformService, never()).transform(any(LogEvent.class));
        verify(outputAdapterComponent, never()).deliver(any(LogEvent.class));
        verify(liveTailService, never()).broadcastLog(any(LogEvent.class));
    }

    @Test
    void noMatchingOutputAdaptersCountsAsDropAndStillCompletesProcessing() throws Exception {
        ParseService parseService = mock(ParseService.class);
        TransformService transformService = mock(TransformService.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        LiveTailService liveTailService = mock(LiveTailService.class);
        OutputAdapterComponent outputAdapterComponent = mock(OutputAdapterComponent.class);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger inFlightMessages = new AtomicInteger(0);
        AtomicLong dropped = new AtomicLong(0);
        AtomicLong failed = new AtomicLong(0);
        AtomicLong processed = new AtomicLong(0);
        BlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>();
        LogEvent event = new LogEvent("raw", "localhost", "access");
        queue.put(event);

        when(parseService.parse(any(LogEvent.class))).thenReturn(true);
        when(transformService.transform(any(LogEvent.class))).thenReturn(true);
        when(structuredTransformService.applyToLogEvent(any(LogEvent.class))).thenReturn(true);
        when(outputAdapterComponent.deliver(any(LogEvent.class))).thenAnswer(invocation -> {
            running.set(false);
            return new OutputAdapterComponent.DeliverySummary(0, 0, 0);
        });

        ProcessingDispatcher dispatcher = new ProcessingDispatcher(
                queue,
                parseService,
                transformService,
                structuredTransformService,
                liveTailService,
                outputAdapterComponent,
                running,
                inFlightMessages,
                dropped,
                failed,
                processed
        );

        runOnce(dispatcher);

        assertEquals(1, dropped.get());
        assertEquals(0, failed.get());
        assertEquals(1, processed.get());
        assertEquals(0, inFlightMessages.get());
        assertTrue(event.isTransformed());
        verify(outputAdapterComponent).deliver(event);
        verify(liveTailService).broadcastLog(event);
    }

    @Test
    void structuredTransformFailureDoesNotBlockDelivery() throws Exception {
        ParseService parseService = mock(ParseService.class);
        TransformService transformService = mock(TransformService.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        LiveTailService liveTailService = mock(LiveTailService.class);
        OutputAdapterComponent outputAdapterComponent = mock(OutputAdapterComponent.class);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger inFlightMessages = new AtomicInteger(0);
        AtomicLong dropped = new AtomicLong(0);
        AtomicLong failed = new AtomicLong(0);
        AtomicLong processed = new AtomicLong(0);
        BlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>();
        LogEvent event = new LogEvent("raw", "localhost", "access");
        queue.put(event);

        when(parseService.parse(any(LogEvent.class))).thenReturn(true);
        when(transformService.transform(any(LogEvent.class))).thenReturn(true);
        when(structuredTransformService.applyToLogEvent(any(LogEvent.class))).thenReturn(false);
        when(outputAdapterComponent.deliver(any(LogEvent.class))).thenAnswer(invocation -> {
            running.set(false);
            return new OutputAdapterComponent.DeliverySummary(1, 1, 0);
        });

        ProcessingDispatcher dispatcher = new ProcessingDispatcher(
                queue,
                parseService,
                transformService,
                structuredTransformService,
                liveTailService,
                outputAdapterComponent,
                running,
                inFlightMessages,
                dropped,
                failed,
                processed
        );

        runOnce(dispatcher);

        assertEquals(0, dropped.get());
        assertEquals(0, failed.get());
        assertEquals(1, processed.get());
        assertEquals(0, inFlightMessages.get());
        assertTrue(event.isTransformed());
        verify(outputAdapterComponent).deliver(event);
        verify(liveTailService).broadcastLog(event);
    }

    private void runOnce(ProcessingDispatcher dispatcher) throws InterruptedException {
        Thread thread = Thread.ofPlatform().start(dispatcher);
        thread.join(2000);
        assertFalse(thread.isAlive(), "dispatcher thread should exit after processing one event");
    }
}
