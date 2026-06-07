package org.keinus.logparser.interfaces.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.application.pipeline.PipelineReloadService;
import org.keinus.logparser.application.pipeline.OutputAdapterComponent;
import org.keinus.logparser.application.service.ThreadMonitoringService;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.interfaces.dto.response.OutputAdapterMetricsDto;
import org.keinus.logparser.interfaces.dto.response.PipelineTopologyDto;
import org.keinus.logparser.interfaces.dto.response.ThreadDetailDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipeline")
@RequiredArgsConstructor
@Slf4j
public class PipelineController {

    private final PipelineReloadService pipelineReloadService;
    private final OutputAdapterComponent outputAdapterComponent;
    private final ThreadMonitoringService threadMonitoringService;
    private final ConfigManagementService configManagementService;
    private final org.keinus.logparser.application.service.LiveTailService liveTailService;

    @GetMapping("/livetail/status")
    public ResponseEntity<Map<String, Boolean>> getLiveTailStatus() {
        return ResponseEntity.ok(Map.of("enabled", liveTailService.isEnabled()));
    }

    @PostMapping("/livetail/enable")
    public ResponseEntity<Void> enableLiveTail() {
        liveTailService.setEnabled(true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/livetail/disable")
    public ResponseEntity<Void> disableLiveTail() {
        liveTailService.setEnabled(false);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/topology")
    public ResponseEntity<List<PipelineTopologyDto>> getPipelineTopology() {
        return ResponseEntity.ok(configManagementService.getPipelineTopology());
    }

    @GetMapping("/status")
    public ResponseEntity<PipelineReloadService.PipelineStatusInfo> getPipelineStatus() {
        PipelineReloadService.PipelineStatusInfo status = pipelineReloadService.getPipelineStatus();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/output-metrics")
    public ResponseEntity<List<OutputAdapterMetricsDto>> getOutputMetrics() {
        List<OutputAdapterMetricsDto> metrics = outputAdapterComponent.getAdapterMetrics().stream()
                .map(OutputAdapterMetricsDto::from)
                .toList();
        return ResponseEntity.ok(metrics);
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadConfiguration() {
        log.info("POST /api/v1/pipeline/reload");
        try {
            pipelineReloadService.reloadConfiguration();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Configuration reloaded successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to reload configuration", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/validate-and-reload")
    public ResponseEntity<Map<String, String>> validateAndReload() {

        try {
            pipelineReloadService.validateAndReload();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Configuration validated and reloaded successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to validate and reload configuration", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restartPipeline() {

        try {
            pipelineReloadService.restartPipeline();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Pipeline restarted successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to restart pipeline", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/reload-progress")
    public ResponseEntity<PipelineReloadService.ReloadProgress> getReloadProgress() {

        PipelineReloadService.ReloadProgress progress = pipelineReloadService.getReloadProgress();
        return ResponseEntity.ok(progress);
    }

    @PostMapping("/cancel-reload")
    public ResponseEntity<Map<String, String>> cancelReload() {

        try {
            pipelineReloadService.cancelReload();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Reload cancelled successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to cancel reload", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/threads")
    public ResponseEntity<List<ThreadDetailDto>> getAllThreads() {

        List<ThreadDetailDto> threads = threadMonitoringService.getAllThreadDetails();
        return ResponseEntity.ok(threads);
    }
}
