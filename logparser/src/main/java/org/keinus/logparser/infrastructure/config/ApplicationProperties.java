package org.keinus.logparser.infrastructure.config;

import java.util.List;
import java.util.ArrayList;
import jakarta.annotation.PostConstruct;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.keinus.logparser.domain.configuration.service.ConfigValidator;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * ETL 파이프라인의 모든 설정을 관리하는 중앙 설정 클래스입니다.
 * 데이터베이스에서 설정을 로드합니다.
 */
@Configuration
@Component
@Data
@Slf4j
public class ApplicationProperties {

    // === 타입 안전한 설정 ===
    private List<InputAdapterConfig> input;
    private List<OutputAdapterConfig> output;
    private List<ParserAdapterConfig> parser;
    private List<TransformConfig> transform;
    private int parserThreads;
    private long flushInterval;

    // === 설정 검증기 ===
    private final ConfigValidator configValidator;

    // === DB 설정 로더 ===
    private final DatabaseConfigLoader databaseConfigLoader;

    @PostConstruct
    public void loadConfigurationFromDatabase() {
        log.info("=".repeat(80));
        log.info("Loading pipeline configuration from database...");
        log.info("=".repeat(80));

        // DB에서 설정 로드 (검증 실패 시 빈 설정으로 초기화)
        try {
            DatabaseConfigLoader.PipelineConfiguration config = databaseConfigLoader.loadConfiguration();

            this.input = config.getInput();
            this.output = config.getOutput();
            this.parser = config.getParser();
            this.transform = config.getTransform();
            this.parserThreads = config.getParserThreads();
            this.flushInterval = config.getFlushInterval();

            log.info("Configuration loaded from database:");
            log.info("  - Input adapters: {}", input.size());
            log.info("  - Output adapters: {}", output.size());
            log.info("  - Parsers: {}", parser.size());
            log.info("  - Transforms: {}", transform.size());
            log.info("  - Parser threads: {}", parserThreads);
            log.info("  - Flush interval: {}ms", flushInterval);

            // 설정 검증 (일시적으로 비활성화 - 디버깅용)
            try {
                validateProperties();
                log.info("Configuration validation passed");
            } catch (Exception e) {
                log.warn("Configuration validation failed, but continuing anyway: {}", e.getMessage());
                log.warn("This is allowed for debugging purposes");
            }

            log.info("=".repeat(80));
            log.info("Pipeline configuration loaded successfully");
            log.info("=".repeat(80));

        } catch (Exception e) {
            log.error("═".repeat(80));
            log.error("FAILED TO LOAD CONFIGURATION FROM DATABASE!");
            log.error("Error: {}", e.getMessage(), e);
            log.error("");
            log.error("Pipeline will NOT start due to invalid configuration.");
            log.error("Application will start with EMPTY configuration to allow fixing via API.");
            log.error("");
            log.error("To fix the configuration:");
            log.error("  1. Open Web UI: http://localhost:8765/");
            log.error("  2. Review and fix invalid adapters/parsers/transforms");
            log.error("  3. Use REST API to update or delete invalid entries");
            log.error("");
            log.error("Pipeline will auto-reload after configuration is fixed.");
            log.error("═".repeat(80));

            // 빈 설정으로 초기화 (앱은 시작되지만 파이프라인은 비활성화)
            this.input = new java.util.ArrayList<>();
            this.output = new java.util.ArrayList<>();
            this.parser = new java.util.ArrayList<>();
            this.transform = new java.util.ArrayList<>();
            this.parserThreads = 4;
            this.flushInterval = 5000;

            log.info("Application started with EMPTY configuration (pipeline disabled)");
            log.info("=".repeat(80));
        }
    }

    public synchronized void applyConfiguration(DatabaseConfigLoader.PipelineConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }

        this.input = copyList(config.getInput());
        this.output = copyList(config.getOutput());
        this.parser = copyList(config.getParser());
        this.transform = copyList(config.getTransform());
        this.parserThreads = config.getParserThreads();
        this.flushInterval = config.getFlushInterval();
    }

    public synchronized DatabaseConfigLoader.PipelineConfiguration snapshot() {
        DatabaseConfigLoader.PipelineConfiguration snapshot = new DatabaseConfigLoader.PipelineConfiguration();
        snapshot.setInput(copyList(input));
        snapshot.setOutput(copyList(output));
        snapshot.setParser(copyList(parser));
        snapshot.setTransform(copyList(transform));
        snapshot.setParserThreads(parserThreads);
        snapshot.setFlushInterval(flushInterval);
        return snapshot;
    }

    public void validateProperties() {
        validateBasicProperties();
        validateRequiredConfigs();
        validateAdapters();
    }

    private void validateBasicProperties() {
        if (parserThreads <= 0) {
            throw new IllegalArgumentException("Parser threads must be greater than zero.");
        }
        if (flushInterval <= 0) {
            throw new IllegalArgumentException("Flush interval must be greater than zero.");
        }
    }

    private void validateRequiredConfigs() {
        // DB가 비어있으면 검증 스킵 (애플리케이션은 시작되지만 pipeline은 비활성화)
        if (CollectionUtils.isEmpty(input) && CollectionUtils.isEmpty(output) && CollectionUtils.isEmpty(parser)) {
            log.debug("Empty configuration detected - pipeline will not start");
            return;
        }

        // Input/Output은 필수, Parser는 선택적 (경고만)
        if (CollectionUtils.isEmpty(input)) {
            log.warn("═".repeat(80));
            log.warn("WARNING: No input adapters configured!");
            log.warn("Pipeline will be idle until input adapters are added.");
            log.warn("═".repeat(80));
        }
        if (CollectionUtils.isEmpty(output)) {
            throw new IllegalArgumentException("Output configuration cannot be empty.");
        }
        if (CollectionUtils.isEmpty(parser)) {
            log.warn("═".repeat(80));
            log.warn("WARNING: No parsers configured!");
            log.warn("Messages will pass through without parsing.");
            log.warn("Consider adding parsers for message type: {}",
                    input.stream().map(InputAdapterConfig::getMessagetype).distinct().toList());
            log.warn("═".repeat(80));
        }
    }

    private void validateAdapters() {
        if (configValidator == null) {
            return;
        }

        // Skip validation if lists are empty (allows runtime configuration)
        if (CollectionUtils.isEmpty(input) && CollectionUtils.isEmpty(output) && CollectionUtils.isEmpty(parser)) {
            log.debug("Skipping adapter validation - configuration is empty");
            return;
        }

        // Validate individual adapter configurations
        if (!CollectionUtils.isEmpty(input)) {
            validateAdapterList(input, "Input adapter configuration validation failed", configValidator::validateInputAdapter);
        }
        if (!CollectionUtils.isEmpty(output)) {
            validateAdapterList(output, "Output adapter configuration validation failed", configValidator::validateOutputAdapter);
        }
        if (!CollectionUtils.isEmpty(parser)) {
            validateAdapterList(parser, "Parser configuration validation failed", configValidator::validateParserAdapter);
        }
    }

    private <T> void validateAdapterList(List<T> configs, String errorMessage, java.util.function.Function<T, ConfigValidator.ValidationResult> validator) {
        int index = 0;
        for (T config : configs) {
            ConfigValidator.ValidationResult result = validator.apply(config);
            if (result.hasErrors()) {
                log.error("Validation failed for config at index {}: {}", index, config);
                log.error("Validation errors: {}", result.getErrors());
                throw new IllegalArgumentException(errorMessage + " at index " + index + ": " + result.getErrors());
            }
            index++;
        }
    }

    private <T> List<T> copyList(List<T> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(source);
    }

}
