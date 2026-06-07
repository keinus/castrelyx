package org.keinus.logparser.application.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.InputAdapter;
import org.keinus.logparser.domain.input.service.InputFactory;
import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.keinus.logparser.domain.model.LogEvent;

import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 입력 어댑터들을 관리하고 실행하는 컴포넌트입니다.
 * <p>
 * 이 클래스는 애플리케이션 설정({@link ApplicationProperties})을 기반으로
 * 다양한 종류의 입력 어댑터({@link InputAdapter})를 생성하고, 각각을 별도의 스레드에서 실행합니다.
 * 각 어댑터는 외부 소스로부터 로그 메시지를 수신하는 역할을 담당합니다.
 * <p>
 * 생성된 어댑터는 {@link InputFactory}를 통해 동적으로 로드되며,
 * {@link ThreadManager}에 의해 관리되는 스레드에서 동작합니다.
 * 수신된 메시지는 {@link MessageDispatcher}로 전달되어 파이프라인의 다음 단계로 넘어갑니다.
 *
 * @see org.keinus.logparser.config.ApplicationProperties
 * @see org.keinus.logparser.core.dispatch.InputFactory
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see org.keinus.logparser.core.util.ThreadManager
 */
@Slf4j
@Component
@Order(2)
public class InputAdapterComponent implements ApplicationListener<ApplicationReadyEvent> {
    private static final String INPUT_THREAD_PREFIX = "InputAdapter-";

    /**
     * input adapter map
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, InputAdapter> adapterMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ThreadManager threadManager;
    private final MessageDispatcher dispatcher;
    private final ApplicationProperties appProp;

    // 타임아웃 및 대기 시간 상수
    private static final long NO_DATA_SLEEP_MS = 100;  // 데이터가 없을 때 대기 시간

    public InputAdapterComponent(ApplicationProperties appProp, ThreadManager threadManager,
            MessageDispatcher dispatcher) {
        this.appProp = appProp;
        this.threadManager = threadManager;
        this.dispatcher = dispatcher;
    }

    private void initializeInputAdapters() {
        if (!adapterMap.isEmpty()) {
            log.info("Resetting {} previously registered input adapters before initialization", adapterMap.size());
            for (InputAdapter adapter : List.copyOf(adapterMap.values())) {
                try {
                    adapter.close();
                } catch (IOException e) {
                    log.warn("Failed to close stale input adapter {}", adapter, e);
                }
            }
            clearAdapterRegistry();
        }

        List<InputAdapterConfig> inputConfigs = appProp.getInput();
        log.info("Initializing input adapters. Config count: {}",
                inputConfigs == null ? "null" : inputConfigs.size());

        if (inputConfigs == null || inputConfigs.isEmpty()) {
            log.warn("No input adapters configured in ApplicationProperties!");
            return;
        }

        for (InputAdapterConfig param : inputConfigs) {
            try {
                if (!Boolean.TRUE.equals(param.getEnabled())) {
                    log.info("Skipping disabled input adapter: {} (id={})", param.getMessagetype(), param.getId());
                    continue;
                }
                log.info("Creating InputAdapter for type: {}, messagetype: {}",
                        param.getType(), param.getMessagetype());
                InputAdapter adapter = InputFactory.getInputAdapter(param);
                this.adapterMap.put(adapter.getId(), adapter);
                log.info("InputAdapter {} registered successfully", adapter.getClass().getSimpleName());

            } catch (Exception e) {
                log.error("InputAdapter {} initialize error: {}", param.getMessagetype(), e.getMessage(), e);
            }
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("=== ApplicationReadyEvent received ===");
        startPipeline();
    }

    public void startPipeline() {
        if (!running.compareAndSet(false, true)) {
            log.info("Input adapters already running");
            return;
        }

        log.info("=== Starting Input Adapters ===");

        // Initialize adapters from ApplicationProperties (after all beans are ready)
        initializeInputAdapters();

        log.info("Starting Input Adaptor Component with {} adapters...", adapterMap.size());

        if (adapterMap.isEmpty()) {
            log.warn("No input adapters to start!");
            return;
        }

        for (InputAdapter adapter : adapterMap.values()) {
            startAdapterThread(adapter);
        }
        log.info("=== Input Adapters started successfully ===");
    }

    private void startAdapterThread(InputAdapter adapter) {
        String threadName = getThreadName(adapter);
        log.info("Starting thread for adapter: {}", threadName);

        Runnable lamda = () -> this.processInputAdapter(adapter);

        try {
            threadManager.executeWithName(threadName, lamda);
        } catch (Exception ex) {
            log.error("Failed to start thread: {}", threadName, ex);
        }
    }

    public void addAdapter(InputAdapterConfig config) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            log.info("Adapter is disabled, skipping start: {}", config.getMessagetype());
            return;
        }
        try {
            if (config.getId() != null && adapterMap.containsKey(config.getId())) {
                removeAdapter(config.getId());
            }
            InputAdapter adapter = InputFactory.getInputAdapter(config);
            adapterMap.put(adapter.getId(), adapter);
            if (running.get()) {
                startAdapterThread(adapter);
            }
            log.info("Added and started input adapter: id={}, type={}", adapter.getId(), adapter.getMessageType());
        } catch (Exception e) {
            log.error("Failed to add input adapter", e);
            throw new RuntimeException("Failed to add input adapter", e);
        }
    }

    public void removeAdapter(Long id) {
        InputAdapter adapter = adapterMap.remove(id);
        if (adapter != null) {
            String threadName = getThreadName(adapter);
            try {
                adapter.close();
                threadManager.stopThread(threadName);
                log.info("Stopped and removed input adapter: id={}", id);
            } catch (Exception e) {
                log.error("Error stopping input adapter: id={}", id, e);
            }
        } else {
            log.warn("Input adapter not found for removal: id={}", id);
        }
    }

    public void restartAdapter(InputAdapterConfig config) {
        removeAdapter(config.getId());
        addAdapter(config);
    }

    @PreDestroy
    public void stopPipeline() {
        try {
            close();
            log.info("All Input Adapters stopped successfully");
        } catch (Exception e) {
            log.error("Error during ETL Pipeline shutdown", e);
        }
    }

    private void processInputAdapter(InputAdapter mInputAdapter) {
        log.info("processInputAdapter started for adapter: {}", mInputAdapter.getClass().getSimpleName());
        LogEvent pendingLogEvent = null;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                LogEvent logEvent;
                // 이전에 실패한 로그 이벤트가 있다면 먼저 처리
                if (pendingLogEvent != null) {
                    logEvent = pendingLogEvent;
                } else {
                    // 새로운 로그 이벤트 가져오기
                    logEvent = mInputAdapter.run();
                }

                if (logEvent != null) {
                    boolean sent = dispatcher.putInputMsg(logEvent);
                    if (!sent) {
                        log.debug("Failed to send message: {}", mInputAdapter.getClass().getSimpleName());
                        // 실패한 이벤트를 저장하고 다음 루프에서 재시도
                        pendingLogEvent = logEvent;
                        Thread.sleep(NO_DATA_SLEEP_MS);
                    } else {
                        // 성공적으로 전송되었으므로 pendingLogEvent 초기화
                        pendingLogEvent = null;
                    }
                } else {
                    Thread.sleep(NO_DATA_SLEEP_MS); // 데이터가 없을 때 대기
                }
            } catch (InterruptedException ie) {
                log.info("Input adapter thread interrupted, stopping: {}", mInputAdapter.getClass().getSimpleName());
                Thread.currentThread().interrupt(); // Restore interrupted status
                break;
            } catch (Exception e) {
                if (e instanceof InterruptedException || (e.getCause() instanceof InterruptedException)) {
                     log.info("Input adapter thread interrupted (caught in Exception), stopping: {}", mInputAdapter.getClass().getSimpleName());
                     Thread.currentThread().interrupt();
                     break;
                }
                // 예외 발생 시에도 스레드를 종료하지 않고 계속 실행
                log.error("Error in input adapter loop for {}, continuing...",
                        mInputAdapter.getClass().getSimpleName(), e);
                try {
                    Thread.sleep(1000); // 짧은 대기 후 재시도
                } catch (InterruptedException ie) {
                    log.info("Input adapter thread interrupted during error sleep, stopping: {}", mInputAdapter.getClass().getSimpleName());
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // running이 false가 되어 정상적으로 종료되는 경우에만 여기 도달
        // 어댑터 close는 stopPipeline() -> close()에서 처리됨 (중복 close 방지)
        log.info("processInputAdapter shutting down for adapter: {}", mInputAdapter.getClass().getSimpleName());
    }

    public void close() {
        if (!running.compareAndSet(true, false)) {
            log.debug("InputAdapterComponent already stopped");
        }

        List<InputAdapter> adapters = List.copyOf(adapterMap.values());
        for (InputAdapter adapter : adapters) {
            try {
                adapter.close();
                log.info("InputAdapter {} closed", adapter.getClass().getSimpleName());
            } catch (IOException e) {
                log.error("Failed to close InputAdapter", e);
            }
        }
        threadManager.stopThreadsStartingWith(INPUT_THREAD_PREFIX, 10_000);
        clearAdapterRegistry();
    }

    int getRegisteredAdapterCount() {
        return adapterMap.size();
    }

    java.util.Set<Long> getRegisteredAdapterIds() {
        return java.util.Set.copyOf(adapterMap.keySet());
    }

    private void clearAdapterRegistry() {
        adapterMap.clear();
    }

    private String getThreadName(InputAdapter adapter) {
        return INPUT_THREAD_PREFIX + adapter.getId() + "-" + adapter.getMessageType();
    }
}
