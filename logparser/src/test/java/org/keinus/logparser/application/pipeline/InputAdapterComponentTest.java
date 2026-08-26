package org.keinus.logparser.application.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.input.model.InputAdapter;
import org.keinus.logparser.domain.input.service.InputFactory;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InputAdapterComponentTest {

    @Mock
    private ApplicationProperties appProp;

    @Mock
    private ThreadManager threadManager;

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private ConfigManagementService configManagementService;

    @Test
    void startPipelineRebuildsRegistryWithoutKeepingStaleAdapters() throws Exception {
        InputAdapterConfig config1 = config(1L, "alpha");
        InputAdapterConfig config2 = config(2L, "beta");

        TestInputAdapter adapter1 = new TestInputAdapter(config1);
        TestInputAdapter adapter2 = new TestInputAdapter(config2);
        InputAdapterComponent component = new InputAdapterComponent(
                appProp, threadManager, messageDispatcher, configManagementService);

        when(appProp.getInput())
                .thenReturn(List.of(config1))
                .thenReturn(List.of(config2));

        try (MockedStatic<InputFactory> mockedFactory = mockStatic(InputFactory.class)) {
            mockedFactory.when(() -> InputFactory.getInputAdapter(any())).thenAnswer(invocation -> {
                InputAdapterConfig config = invocation.getArgument(0);
                return config.getId().equals(1L) ? adapter1 : adapter2;
            });

            component.startPipeline();
            assertEquals(1, component.getRegisteredAdapterCount());
            assertEquals(Set.of(1L), component.getRegisteredAdapterIds());

            component.stopPipeline();
            assertTrue(adapter1.closed.get());
            verify(threadManager).stopThreadsStartingWith(eq("InputAdapter-"), eq(10_000L));

            component.startPipeline();
            assertEquals(1, component.getRegisteredAdapterCount());
            assertEquals(Set.of(2L), component.getRegisteredAdapterIds());
            verify(threadManager).executeWithName(eq("InputAdapter-1-alpha"), any());
            verify(threadManager).executeWithName(eq("InputAdapter-2-beta"), any());

            component.stopPipeline();
            assertTrue(adapter2.closed.get());
        }
    }

    @Test
    void removeAdapterStopsThreadAndClearsRegistryEntry() throws Exception {
        InputAdapterConfig config = config(10L, "alpha");

        TestInputAdapter adapter = new TestInputAdapter(config);
        InputAdapterComponent component = new InputAdapterComponent(
                appProp, threadManager, messageDispatcher, configManagementService);
        when(appProp.getInput()).thenReturn(List.of(config));

        try (MockedStatic<InputFactory> mockedFactory = mockStatic(InputFactory.class)) {
            mockedFactory.when(() -> InputFactory.getInputAdapter(any())).thenReturn(adapter);

            component.startPipeline();
            component.removeAdapter(10L);

            assertEquals(0, component.getRegisteredAdapterCount());
            assertTrue(adapter.closed.get());
            verify(threadManager).stopThread("InputAdapter-10-alpha");
        }
    }

    @Test
    void initializationFailureDisablesOnlyFailedAdapterAndStartsRemainingAdapters() throws Exception {
        InputAdapterConfig failingConfig = config(1L, "failing");
        InputAdapterConfig workingConfig = config(2L, "working");
        TestInputAdapter workingAdapter = new TestInputAdapter(workingConfig);
        InputAdapterComponent component = new InputAdapterComponent(
                appProp, threadManager, messageDispatcher, configManagementService);
        when(appProp.getInput()).thenReturn(List.of(failingConfig, workingConfig));

        try (MockedStatic<InputFactory> mockedFactory = mockStatic(InputFactory.class)) {
            mockedFactory.when(() -> InputFactory.getInputAdapter(any())).thenAnswer(invocation -> {
                InputAdapterConfig config = invocation.getArgument(0);
                if (config.getId().equals(1L)) {
                    throw new IllegalStateException("forced initialization failure");
                }
                return workingAdapter;
            });

            component.startPipeline();

            verify(configManagementService).disableInputAdapter(1L);
            assertEquals(Set.of(2L), component.getRegisteredAdapterIds());
            verify(threadManager).executeWithName(eq("InputAdapter-2-working"), any());
        }
    }

    @Test
    void runtimeFailureDisablesAdapterAndStopsItsLoop() throws Exception {
        InputAdapterConfig failingConfig = config(3L, "runtime-failure");
        TestInputAdapter failingAdapter = new TestInputAdapter(failingConfig) {
            @Override
            public LogEvent run() {
                throw new IllegalStateException("forced runtime failure");
            }
        };
        InputAdapterComponent component = new InputAdapterComponent(
                appProp, threadManager, messageDispatcher, configManagementService);
        when(appProp.getInput()).thenReturn(List.of(failingConfig));
        org.mockito.ArgumentCaptor<Runnable> runnableCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);

        try (MockedStatic<InputFactory> mockedFactory = mockStatic(InputFactory.class)) {
            mockedFactory.when(() -> InputFactory.getInputAdapter(any())).thenReturn(failingAdapter);
            component.startPipeline();
            verify(threadManager).executeWithName(eq("InputAdapter-3-runtime-failure"), runnableCaptor.capture());

            runnableCaptor.getValue().run();

            verify(configManagementService).disableInputAdapter(3L);
            assertEquals(0, component.getRegisteredAdapterCount());
            assertTrue(failingAdapter.closed.get());
        }
    }

    private InputAdapterConfig config(Long id, String messageType) {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(id);
        config.setType("FakeInputAdapter");
        config.setMessagetype(messageType);
        config.setEnabled(true);
        return config;
    }

    private static class TestInputAdapter extends InputAdapter {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private TestInputAdapter(InputAdapterConfig config) throws IOException {
            super(config);
        }

        @Override
        public LogEvent run() {
            return null;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
