package org.keinus.logparser.application.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.TransformService;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/** Executes parser and transform configurations as one ordered chain per message type. */
@Service
public class ProcessingStepService {
    private static final Logger log = LoggerFactory.getLogger(ProcessingStepService.class);

    private final ApplicationProperties applicationProperties;
    private final DatabaseConfigLoader databaseConfigLoader;
    private final ParseService parseService;
    private final TransformService transformService;

    private volatile Map<String, List<ProcessingStep>> stepsByMessageType = Map.of();

    public ProcessingStepService(
            ApplicationProperties applicationProperties,
            DatabaseConfigLoader databaseConfigLoader,
            ParseService parseService,
            TransformService transformService) {
        this.applicationProperties = applicationProperties;
        this.databaseConfigLoader = databaseConfigLoader;
        this.parseService = parseService;
        this.transformService = transformService;
    }

    @PostConstruct
    public void initialize() {
        reload(applicationProperties.getParser(), applicationProperties.getTransform());
    }

    public synchronized void reload() {
        DatabaseConfigLoader.PipelineConfiguration configuration = databaseConfigLoader.loadConfiguration();
        reload(configuration.getParser(), configuration.getTransform());
    }

    public synchronized void reload(
            List<ParserAdapterConfig> parserConfigs,
            List<TransformConfig> transformConfigs) {
        Map<String, List<ProcessingStep>> next = new HashMap<>();

        if (parserConfigs != null) {
            for (ParserAdapterConfig config : parserConfigs) {
                if (config == null || Boolean.FALSE.equals(config.getEnabled())) {
                    continue;
                }
                ParseService.ParserStep parserStep = parseService.createStep(config);
                if (parserStep == null) {
                    continue;
                }
                next.computeIfAbsent(config.getMessagetype(), ignored -> new ArrayList<>())
                        .add(ProcessingStep.parser(config, parserStep));
            }
        }

        if (transformConfigs != null) {
            for (TransformConfig config : transformConfigs) {
                TransformService.TransformStep transformStep = transformService.createStep(config);
                if (transformStep == null) {
                    continue;
                }
                next.computeIfAbsent(config.getMessagetype(), ignored -> new ArrayList<>())
                        .add(ProcessingStep.transform(config, transformStep));
            }
        }

        next.replaceAll((messageType, steps) -> steps.stream()
                .sorted(Comparator
                        .comparing((ProcessingStep step) -> step.priority() == null
                                ? Integer.MAX_VALUE : step.priority())
                        .thenComparing(step -> step.kind() == StepKind.PARSER ? 0 : 1)
                        .thenComparing(step -> step.id() == null ? Long.MAX_VALUE : step.id()))
                .toList());

        Map<String, List<ProcessingStep>> immutable = new HashMap<>();
        next.forEach((messageType, steps) -> immutable.put(messageType, List.copyOf(steps)));
        stepsByMessageType = Map.copyOf(immutable);
        log.info("Processing step chain reloaded: {} message types, {} steps",
                stepsByMessageType.size(),
                stepsByMessageType.values().stream().mapToInt(List::size).sum());
    }

    public ProcessingResult process(LogEvent logEvent) {
        List<ProcessingStep> steps = stepsByMessageType.getOrDefault(logEvent.getMessageType(), List.of());
        for (ProcessingStep step : steps) {
            try {
                boolean succeeded = step.execute(logEvent);
                if (succeeded) {
                    continue;
                }

                if (step.kind() == StepKind.PARSER && step.parserStep().continueOnFailure()) {
                    clearRecoverableParserError(logEvent);
                    continue;
                }

                if (step.kind() == StepKind.TRANSFORM) {
                    return ProcessingResult.FILTERED;
                }

                logEvent.markAsError("Parser step failed: " + step.type());
                return ProcessingResult.FAILED;
            } catch (Exception e) {
                log.warn("Processing step {} failed for message type {}: {}",
                        step.type(), logEvent.getMessageType(), e.getMessage(), e);
                if (step.kind() == StepKind.PARSER && step.parserStep().continueOnFailure()) {
                    clearRecoverableParserError(logEvent);
                    continue;
                }
                logEvent.markAsError("Processing step failed: " + e.getMessage());
                return ProcessingResult.FAILED;
            }
        }
        return ProcessingResult.SUCCESS;
    }

    private void clearRecoverableParserError(LogEvent logEvent) {
        if (logEvent.hasError()) {
            logEvent.setProcessingError(null);
            logEvent.setStage(LogEvent.ProcessingStage.RAW);
        }
    }

    public enum ProcessingResult {
        SUCCESS,
        FILTERED,
        FAILED
    }

    public enum StepKind {
        PARSER,
        TRANSFORM
    }

    private record ProcessingStep(
            StepKind kind,
            Long id,
            Integer priority,
            String type,
            ParseService.ParserStep parserStep,
            TransformService.TransformStep transformStep) {

        static ProcessingStep parser(ParserAdapterConfig config, ParseService.ParserStep step) {
            return new ProcessingStep(StepKind.PARSER, config.getId(), config.getPriority(),
                    config.getType(), step, null);
        }

        static ProcessingStep transform(TransformConfig config, TransformService.TransformStep step) {
            return new ProcessingStep(StepKind.TRANSFORM, config.getId(), config.getPriority(),
                    config.getType(), null, step);
        }

        boolean execute(LogEvent event) {
            return kind == StepKind.PARSER
                    ? parserStep.execute(event)
                    : transformStep.execute(event);
        }
    }
}
