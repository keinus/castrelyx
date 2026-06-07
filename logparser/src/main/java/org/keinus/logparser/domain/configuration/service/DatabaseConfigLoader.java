package org.keinus.logparser.domain.configuration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.model.*;
import org.keinus.logparser.infrastructure.persistence.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 데이터베이스에서 설정을 로드하는 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseConfigLoader {

    private final ConfigManagementService configManagementService;
    private final ConfigValidationService configValidationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public PipelineConfiguration loadConfiguration() {
        log.info("Loading configuration from database...");

        PipelineConfiguration config = new PipelineConfiguration();

        config.setInput(loadInputAdapters());
        log.info("Loaded {} input adapters", config.getInput().size());

        config.setOutput(loadOutputAdapters());
        log.info("Loaded {} output adapters", config.getOutput().size());

        config.setParser(loadParsers());
        log.info("Loaded {} parsers", config.getParser().size());

        config.setTransform(loadTransforms());
        log.info("Loaded {} transforms", config.getTransform().size());

        loadCommonSettings(config);

        // Validate loaded configuration
        log.info("Validating loaded configuration from database");
        validateLoadedConfiguration(config);

        log.info("Configuration loaded and validated successfully from database");
        return config;
    }

    /**
     * 데이터베이스에서 로드한 설정을 검증합니다.
     */
    private void validateLoadedConfiguration(PipelineConfiguration config) {
        var validationResult = configValidationService.validatePipelineIntegrity();

        if (!validationResult.isValid()) {
            log.error("Database configuration validation failed: {}", validationResult.errors());
            throw new IllegalStateException(
                    "Invalid configuration loaded from database: " + validationResult.errors());
        }

        if (!validationResult.warnings().isEmpty()) {
            log.warn("Database configuration validation warnings: {}", validationResult.warnings());
        }

        log.info("Database configuration validation passed");
    }

    private List<InputAdapterConfig> loadInputAdapters() {
        List<InputAdapterEntity> entities = configManagementService.getEnabledInputAdapters();
        List<InputAdapterConfig> configs = new ArrayList<>();

        for (InputAdapterEntity entity : entities) {
            InputAdapterConfig config = new InputAdapterConfig();
            config.setId(entity.getId());
            config.setType(entity.getType());
            config.setMessagetype(entity.getMessagetype());
            config.setHost(entity.getHost());
            config.setPort(entity.getPort());
            config.setPath(entity.getPath());
            config.setTopicid(entity.getTopicid());
            config.setBootstrapservers(entity.getBootstrapservers());
            config.setGroupId(entity.getGroupId());
            config.setCodec(entity.getCodec());
            config.setPath_pattern(entity.getPathPattern());
            config.setIsFromBeginning(entity.getIsFromBeginning());
            config.setBufferSize(entity.getBufferSize());
            config.setTimeoutMs(entity.getTimeoutMs());
            config.setEnabled(entity.getEnabled());
            config.setWorkerThreads(entity.getWorkerThreads());
            config.setQueueSize(entity.getQueueSize());
            config.setConfigParams(entity.getConfigParams());

            configs.add(config);
        }

        return configs;
    }

    private List<OutputAdapterConfig> loadOutputAdapters() {
        List<OutputAdapterEntity> entities = configManagementService.getEnabledOutputAdapters();
        List<OutputAdapterConfig> configs = new ArrayList<>();

        for (OutputAdapterEntity entity : entities) {
            OutputAdapterConfig config = new OutputAdapterConfig();
            config.setId(entity.getId());
            config.setType(entity.getType());
            config.setMessagetype(entity.getMessagetype());

            // Network settings
            config.setHost(entity.getHost());
            config.setPort(entity.getPort());

            // HTTP settings
            config.setUrl(entity.getUrl());
            config.setMethod(entity.getMethod());
            config.setHeaders(readJsonMap(entity.getHeaders(), new TypeReference<Map<String, String>>() {}, "headers", entity.getId()));

            // Kafka settings
            config.setTopicid(entity.getTopicid());
            config.setBootstrapservers(entity.getBootstrapservers());
            config.setKey(entity.getKey());

            // OpenSearch settings
            config.setIndex(entity.getIndexTemplate());
            config.setOsUsername(entity.getOsUsername());
            config.setOsPassword(entity.getOsPassword());
            config.setAction(entity.getAction());

            // RabbitMQ settings
            config.setRoutingkey(entity.getRoutingkey());
            config.setExchange(entity.getExchange());
            config.setRmqUsername(entity.getRmqUsername());
            config.setRmqPassword(entity.getRmqPassword());
            config.setRmqPort(entity.getRmqPort());
            config.setTagpass(readJsonMap(entity.getTagpass(), new TypeReference<Map<String, List<String>>>() {}, "tagpass", entity.getId()));

            // Performance settings
            config.setBatchSize(entity.getBatchSize());
            config.setFlushIntervalMs(entity.getFlushIntervalMs());
            config.setRetryCount(entity.getRetryCount());
            config.setRetryDelayMs(entity.getRetryDelayMs());

            // Common settings
            config.setAddOriginText(entity.getAddOriginText());
            config.setEnabled(entity.getEnabled());
            config.setTimeoutMs(entity.getTimeoutMs());
            config.setConfigParams(entity.getConfigParams());
            config.setMessagetype(normalizeOutputMessageType(entity.getMessagetype()));

            configs.add(config);
        }

        return configs;
    }

    private List<ParserAdapterConfig> loadParsers() {
        List<ParserEntity> entities = configManagementService.getEnabledParsers().stream()
                .sorted(Comparator
                        .comparing((ParserEntity entity) -> entity.getPriority() == null ? Integer.MAX_VALUE : entity.getPriority())
                        .thenComparing(entity -> entity.getId() == null ? Long.MAX_VALUE : entity.getId()))
                .toList();
        List<ParserAdapterConfig> configs = new ArrayList<>();

        for (ParserEntity entity : entities) {
            ParserAdapterConfig config = new ParserAdapterConfig();
            config.setId(entity.getId());
            config.setType(entity.getType());
            config.setMessagetype(entity.getMessagetype());
            config.setParam(entity.getParam());
            config.setPriority(entity.getPriority());
            config.setContinueOnFailure(entity.getContinueOnFailure());
            configs.add(config);
        }

        return configs;
    }

    private List<TransformConfig> loadTransforms() {
        List<TransformEntity> entities = configManagementService.getEnabledTransforms().stream()
                .sorted(Comparator
                        .comparing((TransformEntity entity) -> entity.getPriority() == null ? Integer.MAX_VALUE : entity.getPriority())
                        .thenComparing(entity -> entity.getId() == null ? Long.MAX_VALUE : entity.getId()))
                .toList();
        List<TransformConfig> configs = new ArrayList<>();

        for (TransformEntity entity : entities) {
            TransformConfig config = new TransformConfig();
            config.setId(entity.getId());
            config.setType(entity.getType());
            config.setMessagetype(entity.getMessagetype());
            config.setPriority(entity.getPriority());

            // TransformParamConfig 생성
            TransformParamConfig paramConfig = new TransformParamConfig();

            // filterPass 파싱
            if (entity.getFilterPass() != null && !entity.getFilterPass().trim().isEmpty()) {
                try {
                    Map<String, String> pass = objectMapper.readValue(
                        entity.getFilterPass(),
                        new TypeReference<Map<String, String>>() {}
                    );
                    paramConfig.setPass(pass);
                } catch (Exception e) {
                    log.warn("Failed to parse filterPass for transform {}: {}", entity.getId(), e.getMessage());
                }
            }

            // filterDrop 파싱
            if (entity.getFilterDrop() != null && !entity.getFilterDrop().trim().isEmpty()) {
                try {
                    Map<String, String> drop = objectMapper.readValue(
                        entity.getFilterDrop(),
                        new TypeReference<Map<String, String>>() {}
                    );
                    paramConfig.setDrop(drop);
                } catch (Exception e) {
                    log.warn("Failed to parse filterDrop for transform {}: {}", entity.getId(), e.getMessage());
                }
            }

            // addProperties 파싱
            if (entity.getAddProperties() != null && !entity.getAddProperties().trim().isEmpty()) {
                try {
                    Map<String, List<String>> add = objectMapper.readValue(
                        entity.getAddProperties(),
                        new TypeReference<Map<String, List<String>>>() {}
                    );
                    paramConfig.setAdd(add);
                } catch (Exception e) {
                    log.warn("Failed to parse addProperties for transform {}: {}", entity.getId(), e.getMessage());
                }
            }

            // removeProperties 파싱
            if (entity.getRemoveProperties() != null && !entity.getRemoveProperties().trim().isEmpty()) {
                try {
                    List<String> remove = objectMapper.readValue(
                        entity.getRemoveProperties(),
                        new TypeReference<List<String>>() {}
                    );
                    paramConfig.setRemove(remove);
                } catch (Exception e) {
                    log.warn("Failed to parse removeProperties for transform {}: {}", entity.getId(), e.getMessage());
                }
            }

            config.setParam(paramConfig);
            configs.add(config);
        }

        return configs;
    }

    private <T> T readJsonMap(String rawValue, TypeReference<T> typeReference, String fieldName, Long entityId) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(rawValue, typeReference);
        } catch (Exception e) {
            log.warn("Failed to parse {} for output adapter {}: {}", fieldName, entityId, e.getMessage());
            return null;
        }
    }

    private String normalizeOutputMessageType(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            return "all";
        }
        return messageType;
    }

    private void loadCommonSettings(PipelineConfiguration config) {
        String parserThreadsStr = configManagementService.getConfigValue("parser_threads");
        String flushIntervalStr = configManagementService.getConfigValue("flush_interval");

        config.setParserThreads(parserThreadsStr != null ? Integer.parseInt(parserThreadsStr) : 4);
        config.setFlushInterval(flushIntervalStr != null ? Long.parseLong(flushIntervalStr) : 5000);
    }

    @Transactional(readOnly = true)
    public boolean isDatabaseEmpty() {
        return configManagementService.getEnabledInputAdapters().isEmpty()
                && configManagementService.getEnabledOutputAdapters().isEmpty()
                && configManagementService.getEnabledParsers().isEmpty();
    }

    public static class PipelineConfiguration {
        private List<InputAdapterConfig> input = new ArrayList<>();
        private List<OutputAdapterConfig> output = new ArrayList<>();
        private List<ParserAdapterConfig> parser = new ArrayList<>();
        private List<TransformConfig> transform = new ArrayList<>();
        private int parserThreads = 4;
        private long flushInterval = 5000;

        public List<InputAdapterConfig> getInput() {
            return input;
        }

        public void setInput(List<InputAdapterConfig> input) {
            this.input = input;
        }

        public List<OutputAdapterConfig> getOutput() {
            return output;
        }

        public void setOutput(List<OutputAdapterConfig> output) {
            this.output = output;
        }

        public List<ParserAdapterConfig> getParser() {
            return parser;
        }

        public void setParser(List<ParserAdapterConfig> parser) {
            this.parser = parser;
        }

        public List<TransformConfig> getTransform() {
            return transform;
        }

        public void setTransform(List<TransformConfig> transform) {
            this.transform = transform;
        }

        public int getParserThreads() {
            return parserThreads;
        }

        public void setParserThreads(int parserThreads) {
            this.parserThreads = parserThreads;
        }

        public long getFlushInterval() {
            return flushInterval;
        }

        public void setFlushInterval(long flushInterval) {
            this.flushInterval = flushInterval;
        }
    }
}
