package org.keinus.logparser.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.ParserRepository;
import org.keinus.logparser.infrastructure.persistence.repository.TransformRepository;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.keinus.logparser.interfaces.dto.response.ThreadDetailDto;

class ThreadMonitoringServiceTest {

    private ThreadManager threadManager;
    private InputAdapterRepository inputAdapterRepository;
    private ParserRepository parserRepository;
    private TransformRepository transformRepository;
    private ThreadMonitoringService threadMonitoringService;

    @BeforeEach
    void setUp() {
        threadManager = mock(ThreadManager.class);
        inputAdapterRepository = mock(InputAdapterRepository.class);
        parserRepository = mock(ParserRepository.class);
        transformRepository = mock(TransformRepository.class);

        when(inputAdapterRepository.findAll()).thenReturn(List.of());
        when(parserRepository.findAll()).thenReturn(List.of());
        when(transformRepository.findAll()).thenReturn(List.of());

        threadMonitoringService = new ThreadMonitoringService(
                threadManager,
                inputAdapterRepository,
                parserRepository,
                transformRepository
        );
    }

    @Test
    void getAllThreadDetailsReflectsCurrentThreadModel() {
        when(threadManager.getAllThreadInfo()).thenReturn(List.of(
                new ThreadManager.ThreadInfo("ProcessingThread-1", 1L, Thread.State.RUNNABLE, true, false),
                new ThreadManager.ThreadInfo("QueueMonitor", 2L, Thread.State.TIMED_WAITING, true, false),
                new ThreadManager.ThreadInfo("OutputDispatcher", 3L, Thread.State.RUNNABLE, true, false)
        ));

        List<ThreadDetailDto> details = threadMonitoringService.getAllThreadDetails();

        ThreadDetailDto processing = details.stream()
                .filter(detail -> "ProcessingThread-1".equals(detail.getName()))
                .findFirst()
                .orElseThrow();
        ThreadDetailDto monitor = details.stream()
                .filter(detail -> "QueueMonitor".equals(detail.getName()))
                .findFirst()
                .orElseThrow();
        ThreadDetailDto obsolete = details.stream()
                .filter(detail -> "OutputDispatcher".equals(detail.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("PARSER", processing.getComponentType());
        assertEquals("MONITOR", monitor.getComponentType());
        assertEquals("UNKNOWN", obsolete.getComponentType());
    }
}
