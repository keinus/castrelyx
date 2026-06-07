package org.keinus.logparser.application.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.event.*;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineConfigEventListener {

    private final InputAdapterComponent inputAdapterComponent;
    private final OutputAdapterComponent outputAdapterComponent;
    private final ParseService parseService;
    private final TransformService transformService;
    private final MessageDispatcher messageDispatcher;

    @EventListener
    public void handleInputAdapterChanged(InputAdapterChangedEvent event) {
        log.info("Handling InputAdapterChangedEvent: type={}, id={}", event.getChangeType(), event.getAdapterId());
        try {
            switch (event.getChangeType()) {
                case CREATED, ENABLED:
                    inputAdapterComponent.addAdapter(event.getConfig());
                    break;
                case UPDATED:
                    if (Boolean.TRUE.equals(event.getConfig().getEnabled())) {
                        inputAdapterComponent.restartAdapter(event.getConfig());
                    } else {
                        inputAdapterComponent.removeAdapter(event.getAdapterId());
                    }
                    break;
                case DELETED, DISABLED:
                    inputAdapterComponent.removeAdapter(event.getAdapterId());
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to handle InputAdapterChangedEvent", e);
        }
    }

    @EventListener
    public void handleOutputAdapterChanged(OutputAdapterChangedEvent event) {
        log.info("Handling OutputAdapterChangedEvent: type={}, id={}", event.getChangeType(), event.getAdapterId());
        try {
            switch (event.getChangeType()) {
                case CREATED, ENABLED:
                    outputAdapterComponent.addAdapter(event.getConfig());
                    break;
                case UPDATED:
                    if (Boolean.TRUE.equals(event.getConfig().getEnabled())) {
                        outputAdapterComponent.restartAdapter(event.getConfig());
                    } else {
                        outputAdapterComponent.removeAdapter(event.getAdapterId());
                    }
                    break;
                case DELETED, DISABLED:
                    outputAdapterComponent.removeAdapter(event.getAdapterId());
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to handle OutputAdapterChangedEvent", e);
        }
    }

    @EventListener
    public void handleParserChanged(ParserChangedEvent event) {
        log.info("Handling ParserChangedEvent: type={}, id={}", event.getChangeType(), event.getParserId());
        // For simple implementation, reload all parsers
        // Ideally, ParseService should support granular updates
        parseService.reload();
    }

    @EventListener
    public void handleTransformChanged(TransformChangedEvent event) {
        log.info("Handling TransformChangedEvent: type={}, id={}", event.getChangeType(), event.getTransformId());
        // For simple implementation, reload all transforms
        transformService.reload();
    }

    @EventListener
    public void handleParserChanged(ParserTransformThreadsChangedEvent event) {
        log.info("Handling ParserTransformThreadsChangedEvent: type={}, threads={}", event.getChangeType(), event.getThreads());
        // For simple implementation, reload all parsers
        // Ideally, ParseService should support granular updates
        messageDispatcher.updateWorkerThreadCount();
    }
}
