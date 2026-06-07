package org.keinus.logparser.application.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineReloadService {
    private static final long PIPELINE_DRAIN_TIMEOUT_MS = 30_000;

    private final ConfigValidationService validationService;
    private final ConfigManagementService configManagementService;
    private final DatabaseConfigLoader databaseConfigLoader;
    private final ApplicationProperties applicationProperties;
    private final MessageDispatcher messageDispatcher;
    private final InputAdapterComponent inputAdapterComponent;
    private final OutputAdapterComponent outputAdapterComponent;
    private final ParseService parseService;
    private final TransformService transformService;
    private final StructuredTransformService structuredTransformService;

    private final AtomicBoolean reloadInProgress = new AtomicBoolean(false);
    private final AtomicInteger reloadProgress = new AtomicInteger(0);
    private volatile PipelineStatus currentStatus = PipelineStatus.RUNNING;

    public void reloadConfiguration() {
        DatabaseConfigLoader.PipelineConfiguration targetConfiguration = loadValidatedConfiguration();
        reconfigurePipeline(targetConfiguration, PipelineStatus.RELOADING);
    }

    public void validateAndReload() {
        log.info("Validating pipeline before reload");
        validateConfiguration();
        reloadConfiguration();
    }

    public void restartPipeline() {
        DatabaseConfigLoader.PipelineConfiguration targetConfiguration = loadValidatedConfiguration();
        reconfigurePipeline(targetConfiguration, PipelineStatus.STOPPING);
    }

    private DatabaseConfigLoader.PipelineConfiguration loadValidatedConfiguration() {
        log.info("Loading validated configuration from database");
        validateConfiguration();
        return databaseConfigLoader.loadConfiguration();
    }

    private void reconfigurePipeline(
            DatabaseConfigLoader.PipelineConfiguration targetConfiguration,
            PipelineStatus transientStatus
    ) {
        if (!reloadInProgress.compareAndSet(false, true)) {
            throw new RuntimeException("Reload already in progress");
        }

        DatabaseConfigLoader.PipelineConfiguration previousConfiguration = applicationProperties.snapshot();
        boolean pipelineModified = false;

        try {
            reloadProgress.set(5);
            currentStatus = transientStatus;

            stopAcceptingNewInput();
            pipelineModified = true;
            reloadProgress.set(25);

            drainProcessingQueue();
            reloadProgress.set(45);

            stopProcessingAndOutputs();
            reloadProgress.set(65);

            applyConfiguration(targetConfiguration);
            reloadProgress.set(80);

            startPipelineComponents();
            reloadProgress.set(100);
            currentStatus = PipelineStatus.RUNNING;
            log.info("Pipeline reconfiguration completed successfully");
        } catch (Exception e) {
            log.error("Failed to reconfigure pipeline", e);
            if (pipelineModified) {
                rollback(previousConfiguration, e);
            } else {
                currentStatus = PipelineStatus.ERROR;
            }
            throw new RuntimeException("Failed to reconfigure pipeline", e);
        } finally {
            reloadInProgress.set(false);
        }
    }

    private void stopAcceptingNewInput() {
        log.info("Stopping input adapters");
        inputAdapterComponent.stopPipeline();
    }

    private void drainProcessingQueue() {
        log.info("Waiting for input queue and in-flight events to drain");
        boolean drained = messageDispatcher.awaitIdle(PIPELINE_DRAIN_TIMEOUT_MS);
        if (!drained) {
            throw new IllegalStateException("Timed out while draining message queue");
        }
    }

    private void stopProcessingAndOutputs() {
        log.info("Stopping processing workers");
        messageDispatcher.stopProcessingWorkers();

        log.info("Stopping output adapters");
        outputAdapterComponent.stopPipeline();
    }

    private void applyConfiguration(DatabaseConfigLoader.PipelineConfiguration configuration) {
        log.info("Applying new runtime configuration");
        applicationProperties.applyConfiguration(configuration);
        parseService.reload(configuration.getParser());
        transformService.reload(configuration.getTransform());
        structuredTransformService.reload();
    }

    private void startPipelineComponents() {
        log.info("Starting output adapters");
        outputAdapterComponent.startPipeline();

        log.info("Starting processing workers");
        messageDispatcher.restartWorkersFromCurrentConfiguration();

        log.info("Starting input adapters");
        inputAdapterComponent.startPipeline();
    }

    private void rollback(DatabaseConfigLoader.PipelineConfiguration previousConfiguration, Exception cause) {
        log.warn("Attempting pipeline rollback after failure: {}", cause.getMessage());
        try {
            safelyStopAllPipelineComponents();
            applyConfiguration(previousConfiguration);
            startPipelineComponents();
            currentStatus = PipelineStatus.RUNNING;
            reloadProgress.set(0);
            log.warn("Pipeline rollback completed successfully");
        } catch (Exception rollbackException) {
            currentStatus = PipelineStatus.ERROR;
            log.error("Pipeline rollback failed", rollbackException);
            throw new RuntimeException("Pipeline rollback failed", rollbackException);
        }
    }

    private void safelyStopAllPipelineComponents() {
        try {
            inputAdapterComponent.stopPipeline();
        } catch (Exception e) {
            log.warn("Failed to stop input adapters during rollback preparation: {}", e.getMessage());
        }

        try {
            messageDispatcher.stopProcessingWorkers();
        } catch (Exception e) {
            log.warn("Failed to stop processing workers during rollback preparation: {}", e.getMessage());
        }

        try {
            outputAdapterComponent.stopPipeline();
        } catch (Exception e) {
            log.warn("Failed to stop output adapters during rollback preparation: {}", e.getMessage());
        }
    }

    private void validateConfiguration() {
        var validationResult = validationService.validatePipelineIntegrity();

        if (!validationResult.isValid()) {
            log.error("Pipeline validation failed: {}", validationResult.errors());
            throw new RuntimeException("Pipeline validation failed: " + validationResult.errors());
        }

        if (!validationResult.warnings().isEmpty()) {
            log.warn("Pipeline validation warnings: {}", validationResult.warnings());
        }
    }

    public boolean isReloadInProgress() {
        return reloadInProgress.get();
    }

    public ReloadProgress getReloadProgress() {
        return new ReloadProgress(
                reloadProgress.get(),
                currentStatus,
                reloadInProgress.get()
        );
    }

    public PipelineStatusInfo getPipelineStatus() {
        try {
            int inputAdapterCount = configManagementService.getEnabledInputAdapters().size();
            int parserCount = configManagementService.getEnabledParsers().size();
            int transformCount = configManagementService.getEnabledTransforms().size();
            int outputAdapterCount = configManagementService.getEnabledOutputAdapters().size();

            int queueSize = 0;
            int queueCapacity = 0;
            double throughput = 0.0;

            try {
                var metrics = messageDispatcher.getDispatcherMetrics();
                queueSize = metrics.globalQueueSize;
                queueCapacity = metrics.maxQueueSize;
                throughput = metrics.outputThroughput;
            } catch (Exception e) {
                log.debug("Could not retrieve queue metrics: {}", e.getMessage());
            }

            return new PipelineStatusInfo(
                    currentStatus,
                    inputAdapterCount,
                    parserCount,
                    transformCount,
                    outputAdapterCount,
                    queueSize,
                    queueCapacity,
                    throughput
            );
        } catch (Exception e) {
            log.error("Error getting pipeline status", e);
            return new PipelineStatusInfo(
                    PipelineStatus.ERROR,
                    0, 0, 0, 0, 0, 0, 0.0
            );
        }
    }

    public void cancelReload() {
        log.info("Cancelling reload");

        if (reloadInProgress.compareAndSet(true, false)) {
            currentStatus = PipelineStatus.RUNNING;
            reloadProgress.set(0);
            log.info("Reload cancelled");
        } else {
            log.warn("No reload in progress to cancel");
        }
    }

    public enum PipelineStatus {
        RUNNING,
        STOPPED,
        RELOADING,
        STOPPING,
        ERROR
    }

    public record ReloadProgress(
            int progress,
            PipelineStatus status,
            boolean inProgress
    ) {}

    public record PipelineStatusInfo(
            PipelineStatus status,
            int inputAdapterCount,
            int parserCount,
            int transformCount,
            int outputAdapterCount,
            int queueSize,
            int queueCapacity,
            double throughput
    ) {}
}
