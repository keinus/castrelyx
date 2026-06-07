package org.keinus.logparser.domain.configuration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.configuration.model.*;
import org.keinus.logparser.domain.event.*;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.infrastructure.persistence.entity.*;
import org.keinus.logparser.infrastructure.persistence.repository.*;
import org.keinus.logparser.interfaces.exception.ConfigNotFoundException;
import org.keinus.logparser.interfaces.dto.response.PipelineTopologyDto;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ConfigManagementService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InputAdapterRepository inputAdapterRepository;
    private final ParserRepository parserRepository;
    private final TransformRepository transformRepository;
    private final OutputAdapterRepository outputAdapterRepository;
    private final ConfigSettingsRepository configSettingsRepository;
    private final MappingRepository mappingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigValidationService validationService;

    // ==================== InputAdapter Management ====================

    public InputAdapterEntity createInputAdapter(InputAdapterEntity entity) {
        log.info("Creating input adapter: type={}, messagetype={}", entity.getType(), entity.getMessagetype());
        assertValid("InputAdapter", validationService.validateInputAdapter(entity));
        InputAdapterEntity saved = inputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new InputAdapterChangedEvent(this, InputAdapterChangedEvent.ChangeType.CREATED, convertToConfig(saved)));
        return saved;
    }

    public InputAdapterEntity updateInputAdapter(Long id, InputAdapterEntity entity) {
        log.info("Updating input adapter: id={}", id);
        InputAdapterEntity existing = getInputAdapter(id);
        entity.setId(existing.getId());
        entity.setVersion(existing.getVersion());
        assertValid("InputAdapter", validationService.validateInputAdapter(entity));
        InputAdapterEntity saved = inputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new InputAdapterChangedEvent(this, InputAdapterChangedEvent.ChangeType.UPDATED, convertToConfig(saved)));
        return saved;
    }

    public void deleteInputAdapter(Long id) {
        log.info("Deleting input adapter: id={}", id);
        if (!inputAdapterRepository.existsById(id)) {
            throw new ConfigNotFoundException("InputAdapter", id);
        }
        inputAdapterRepository.deleteById(id);
        eventPublisher.publishEvent(new InputAdapterChangedEvent(this, InputAdapterChangedEvent.ChangeType.DELETED, id));
    }

    @Transactional(readOnly = true)
    public InputAdapterEntity getInputAdapter(Long id) {
        return inputAdapterRepository.findById(id)
                .orElseThrow(() -> new ConfigNotFoundException("InputAdapter", id));
    }

    @Transactional(readOnly = true)
    public Page<InputAdapterEntity> getAllInputAdapters(Pageable pageable) {
        return inputAdapterRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<InputAdapterEntity> getInputAdaptersByType(String type) {
        return inputAdapterRepository.findByType(type);
    }

    @Transactional(readOnly = true)
    public InputAdapterEntity getInputAdapterByMessageType(String messageType) {
        return inputAdapterRepository.findByMessagetype(messageType)
                .orElseThrow(() -> new ConfigNotFoundException(
                    "InputAdapter with messageType '" + messageType + "' not found"));
    }

    public InputAdapterEntity enableInputAdapter(Long id) {
        log.info("Enabling input adapter: id={}", id);
        InputAdapterEntity entity = getInputAdapter(id);
        entity.setEnabled(true);
        assertValid("InputAdapter", validationService.validateInputAdapter(entity));
        InputAdapterEntity saved = inputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new InputAdapterChangedEvent(this, InputAdapterChangedEvent.ChangeType.ENABLED, convertToConfig(saved)));
        return saved;
    }

    public InputAdapterEntity disableInputAdapter(Long id) {
        log.info("Disabling input adapter: id={}", id);
        InputAdapterEntity entity = getInputAdapter(id);
        entity.setEnabled(false);
        InputAdapterEntity saved = inputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new InputAdapterChangedEvent(this, InputAdapterChangedEvent.ChangeType.DISABLED, convertToConfig(saved)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<InputAdapterEntity> getEnabledInputAdapters() {
        return inputAdapterRepository.findByEnabledTrue();
    }

    // ==================== Parser Management ====================

    public ParserEntity createParser(ParserEntity entity) {
        log.info("Creating parser: type={}, messagetype={}", entity.getType(), entity.getMessagetype());
        assertValid("Parser", validationService.validateParser(entity));
        ParserEntity saved = parserRepository.save(entity);
        eventPublisher.publishEvent(new ParserChangedEvent(this, ParserChangedEvent.ChangeType.CREATED, convertToConfig(saved)));
        return saved;
    }

    public ParserEntity updateParser(Long id, ParserEntity entity) {
        log.info("Updating parser: id={}", id);
        ParserEntity existing = getParser(id);
        entity.setId(existing.getId());
        entity.setVersion(existing.getVersion());
        assertValid("Parser", validationService.validateParser(entity));
        ParserEntity saved = parserRepository.save(entity);
        eventPublisher.publishEvent(new ParserChangedEvent(this, ParserChangedEvent.ChangeType.UPDATED, convertToConfig(saved)));
        return saved;
    }

    public void deleteParser(Long id) {
        log.info("Deleting parser: id={}", id);
        if (!parserRepository.existsById(id)) {
            throw new ConfigNotFoundException("Parser", id);
        }
        parserRepository.deleteById(id);
        eventPublisher.publishEvent(new ParserChangedEvent(this, ParserChangedEvent.ChangeType.DELETED, id));
    }

    @Transactional(readOnly = true)
    public ParserEntity getParser(Long id) {
        return parserRepository.findById(id)
                .orElseThrow(() -> new ConfigNotFoundException("Parser", id));
    }

    @Transactional(readOnly = true)
    public Page<ParserEntity> getAllParsers(Pageable pageable) {
        return parserRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<ParserEntity> getParsersByType(String type) {
        return parserRepository.findByType(type);
    }

    @Transactional(readOnly = true)
    public List<ParserEntity> getParsersByMessageType(String messageType) {
        return parserRepository.findByMessagetypeOrderByPriorityAsc(messageType);
    }

    public ParserEntity updateParserPriority(Long id, Integer newPriority) {
        log.info("Updating parser priority: id={}, newPriority={}", id, newPriority);
        ParserEntity entity = getParser(id);
        entity.setPriority(newPriority);
        ParserEntity saved = parserRepository.save(entity);
        eventPublisher.publishEvent(new ParserChangedEvent(this, ParserChangedEvent.ChangeType.PRIORITY_CHANGED, convertToConfig(saved)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ParserEntity> getEnabledParsers() {
        return parserRepository.findByEnabledTrue();
    }

    // ==================== Transform Management ====================

    public TransformEntity createTransform(TransformEntity entity) {
        log.info("Creating transform: type={}, messagetype={}", entity.getType(), entity.getMessagetype());
        assertValid("Transform", validationService.validateTransform(entity));
        TransformEntity saved = transformRepository.save(entity);
        eventPublisher.publishEvent(new TransformChangedEvent(this, TransformChangedEvent.ChangeType.CREATED, convertToConfig(saved)));
        return saved;
    }

    public TransformEntity updateTransform(Long id, TransformEntity entity) {
        log.info("Updating transform: id={}", id);
        TransformEntity existing = getTransform(id);
        entity.setId(existing.getId());
        entity.setVersion(existing.getVersion());
        assertValid("Transform", validationService.validateTransform(entity));
        TransformEntity saved = transformRepository.save(entity);
        eventPublisher.publishEvent(new TransformChangedEvent(this, TransformChangedEvent.ChangeType.UPDATED, convertToConfig(saved)));
        return saved;
    }

    public void deleteTransform(Long id) {
        log.info("Deleting transform: id={}", id);
        if (!transformRepository.existsById(id)) {
            throw new ConfigNotFoundException("Transform", id);
        }
        transformRepository.deleteById(id);
        eventPublisher.publishEvent(new TransformChangedEvent(this, TransformChangedEvent.ChangeType.DELETED, id));
    }

    @Transactional(readOnly = true)
    public TransformEntity getTransform(Long id) {
        return transformRepository.findById(id)
                .orElseThrow(() -> new ConfigNotFoundException("Transform", id));
    }

    @Transactional(readOnly = true)
    public Page<TransformEntity> getAllTransforms(Pageable pageable) {
        return transformRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<TransformEntity> getTransformsByType(String type) {
        return transformRepository.findByType(type);
    }

    @Transactional(readOnly = true)
    public List<TransformEntity> getTransformsByMessageType(String messageType) {
        return transformRepository.findByMessagetypeOrderByPriorityAsc(messageType);
    }

    public TransformEntity updateTransformPriority(Long id, Integer newPriority) {
        log.info("Updating transform priority: id={}, newPriority={}", id, newPriority);
        TransformEntity entity = getTransform(id);
        entity.setPriority(newPriority);
        TransformEntity saved = transformRepository.save(entity);
        eventPublisher.publishEvent(new TransformChangedEvent(this, TransformChangedEvent.ChangeType.PRIORITY_CHANGED, convertToConfig(saved)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TransformEntity> getEnabledTransforms() {
        return transformRepository.findByEnabledTrue();
    }

    // ==================== OutputAdapter Management ====================

    public OutputAdapterEntity createOutputAdapter(OutputAdapterEntity entity) {
        log.info("Creating output adapter: type={}, messagetype={}", entity.getType(), entity.getMessagetype());
        assertValid("OutputAdapter", validationService.validateOutputAdapter(entity));
        OutputAdapterEntity saved = outputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new OutputAdapterChangedEvent(this, OutputAdapterChangedEvent.ChangeType.CREATED, convertToConfig(saved)));
        return saved;
    }

    public OutputAdapterEntity updateOutputAdapter(Long id, OutputAdapterEntity entity) {
        log.info("Updating output adapter: id={}", id);
        OutputAdapterEntity existing = getOutputAdapter(id);
        entity.setId(existing.getId());
        entity.setVersion(existing.getVersion());
        assertValid("OutputAdapter", validationService.validateOutputAdapter(entity));
        OutputAdapterEntity saved = outputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new OutputAdapterChangedEvent(this, OutputAdapterChangedEvent.ChangeType.UPDATED, convertToConfig(saved)));
        return saved;
    }

    public void deleteOutputAdapter(Long id) {
        log.info("Deleting output adapter: id={}", id);
        if (!outputAdapterRepository.existsById(id)) {
            throw new ConfigNotFoundException("OutputAdapter", id);
        }
        outputAdapterRepository.deleteById(id);
        eventPublisher.publishEvent(new OutputAdapterChangedEvent(this, OutputAdapterChangedEvent.ChangeType.DELETED, id));
    }

    @Transactional(readOnly = true)
    public OutputAdapterEntity getOutputAdapter(Long id) {
        return outputAdapterRepository.findById(id)
                .orElseThrow(() -> new ConfigNotFoundException("OutputAdapter", id));
    }

    @Transactional(readOnly = true)
    public Page<OutputAdapterEntity> getAllOutputAdapters(Pageable pageable) {
        return outputAdapterRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<OutputAdapterEntity> getOutputAdaptersByType(String type) {
        return outputAdapterRepository.findByType(type);
    }

    @Transactional(readOnly = true)
    public List<OutputAdapterEntity> getOutputAdaptersByMessageType(String messageType) {
        return outputAdapterRepository.findByMessagetype(messageType);
    }

    public OutputAdapterEntity enableOutputAdapter(Long id) {
        log.info("Enabling output adapter: id={}", id);
        OutputAdapterEntity entity = getOutputAdapter(id);
        entity.setEnabled(true);
        assertValid("OutputAdapter", validationService.validateOutputAdapter(entity));
        OutputAdapterEntity saved = outputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new OutputAdapterChangedEvent(this, OutputAdapterChangedEvent.ChangeType.ENABLED, convertToConfig(saved)));
        return saved;
    }

    public OutputAdapterEntity disableOutputAdapter(Long id) {
        log.info("Disabling output adapter: id={}", id);
        OutputAdapterEntity entity = getOutputAdapter(id);
        entity.setEnabled(false);
        OutputAdapterEntity saved = outputAdapterRepository.save(entity);
        eventPublisher.publishEvent(new OutputAdapterChangedEvent(this, OutputAdapterChangedEvent.ChangeType.DISABLED, convertToConfig(saved)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<OutputAdapterEntity> getEnabledOutputAdapters() {
        return outputAdapterRepository.findByEnabledTrue();
    }

    // ==================== Common Settings Management ====================

    public void updateCommonSettings(Map<String, Object> settings) {
        log.info("Updating common settings: count={}", settings.size());
        settings.forEach((key, value) -> {
            setConfigValue(key, value, determineDataType(value));
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAllCommonSettings() {
        return configSettingsRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConfigSettingsEntity::getConfigKey,
                        entity -> parseValue(entity.getConfigValue(), entity.getDataType())
                ));
    }

    @Transactional(readOnly = true)
    public String getConfigValue(String key) {
        return configSettingsRepository.findByConfigKey(key)
                .map(ConfigSettingsEntity::getConfigValue)
                .orElse(null);
    }

    public void setConfigValue(String key, Object value, String dataType) {
        log.info("Setting config value: key={}, dataType={}", key, dataType);
        ConfigSettingsEntity entity = configSettingsRepository.findByConfigKey(key)
                .orElse(ConfigSettingsEntity.builder()
                        .configKey(key)
                        .build());

        entity.setConfigValue(value != null ? value.toString() : null);
        entity.setDataType(dataType);
        entity = configSettingsRepository.save(entity);

        if(key.equals("parser_threads")) {
            eventPublisher.publishEvent(new ParserTransformThreadsChangedEvent(this, ParserTransformThreadsChangedEvent.ChangeType.UPDATED, Integer.valueOf(entity.getConfigValue())));
        }
    }

    // ==================== Topology ====================

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PipelineTopologyDto> getPipelineTopology() {
        Map<String, PipelineTopologyDto> topologyMap = new HashMap<>();

        // 1. Structured Schema Mappings
        mappingRepository.findAll().forEach(mapping -> {
            String msgType = mapping.getMessageType();
            topologyMap.computeIfAbsent(msgType, k -> PipelineTopologyDto.builder()
                    .messageType(k)
                    .description("Flow for " + k)
                    .build())
                .getProcessing().add(mapStructuredMappingToStage(mapping));
        });

        // 2. Inputs
        inputAdapterRepository.findAll().forEach(input -> {
            String msgType = input.getMessagetype();
            topologyMap.computeIfAbsent(msgType, k -> PipelineTopologyDto.builder()
                    .messageType(k)
                    .description("Flow for " + k)
                    .build())
                .getInputs().add(mapInputToStage(input));
        });

        // 3. Parsers
        parserRepository.findAll().forEach(parser -> {
             String msgType = parser.getMessagetype();
             topologyMap.computeIfAbsent(msgType, k -> PipelineTopologyDto.builder()
                    .messageType(k)
                    .description("Flow for " + k)
                    .build())
                .getProcessing().add(mapParserToStage(parser));
        });

        // 4. Transforms
        transformRepository.findAll().forEach(transform -> {
             String msgType = transform.getMessagetype();
             topologyMap.computeIfAbsent(msgType, k -> PipelineTopologyDto.builder()
                    .messageType(k)
                    .description("Flow for " + k)
                    .build())
                .getProcessing().add(mapTransformToStage(transform));
        });

        // Sort Processing by priority
        topologyMap.values().forEach(dto -> 
            dto.getProcessing().sort(Comparator.comparingInt(s -> s.getPriority() != null ? s.getPriority() : 999))
        );

        // 5. Outputs
        outputAdapterRepository.findAll().forEach(output -> {
             String msgType = output.getMessagetype();
             topologyMap.computeIfAbsent(msgType, k -> PipelineTopologyDto.builder()
                    .messageType(k)
                    .description("Flow for " + k)
                    .build())
                .getOutputs().add(mapOutputToStage(output));
        });

        return new ArrayList<>(topologyMap.values());
    }

    private PipelineTopologyDto.PipelineStageDto mapInputToStage(InputAdapterEntity entity) {
        String detail = "";
        if ("HttpInput".equals(entity.getType()) || "TcpInput".equals(entity.getType()) || "UdpInput".equals(entity.getType())) {
            detail = "Port: " + entity.getPort();
        } else if ("KafkaInput".equals(entity.getType())) {
            detail = "Topic: " + entity.getTopicid();
        } else if ("FileInput".equals(entity.getType())) {
            detail = "Path: " + entity.getPath();
        }

        return PipelineTopologyDto.PipelineStageDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .name(entity.getType())
                .detail(detail)
                .badge("IN")
                .enabled(entity.getEnabled())
                .build();
    }

    private PipelineTopologyDto.PipelineStageDto mapParserToStage(ParserEntity entity) {
        String badge = "PARSER";
        if (entity.getType().toUpperCase().contains("GROK")) badge = "GROK";
        else if (entity.getType().toUpperCase().contains("JSON")) badge = "JSON";
        else if (entity.getType().toUpperCase().contains("REGEX")) badge = "REGEX";

        return PipelineTopologyDto.PipelineStageDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .name(entity.getType())
                .detail("Prio: " + entity.getPriority())
                .badge(badge)
                .enabled(entity.getEnabled())
                .priority(entity.getPriority())
                .build();
    }

    private PipelineTopologyDto.PipelineStageDto mapTransformToStage(TransformEntity entity) {
        String badge = "TRANSFORM";
        if (entity.getType().toUpperCase().contains("MASK")) badge = "MASK";
        else if (entity.getType().toUpperCase().contains("FILTER")) badge = "FILTER";
        else if (entity.getType().toUpperCase().contains("GEO")) badge = "GEO";

        return PipelineTopologyDto.PipelineStageDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .name(entity.getType())
                .detail("Prio: " + entity.getPriority())
                .badge(badge)
                .enabled(entity.getEnabled())
                .priority(entity.getPriority())
                .build();
    }

    private PipelineTopologyDto.PipelineStageDto mapStructuredMappingToStage(MappingConfiguration config) {
        int ruleCount = config.getSubTableRules() != null ? config.getSubTableRules().size() : 0;
        return PipelineTopologyDto.PipelineStageDto.builder()
                .type("Structured Schema Mapping")
                .name("Schema Map")
                .detail("Rules: " + ruleCount)
                .badge("SCHEMA")
                .enabled(true)
                .priority(10_000)
                .build();
    }

    private PipelineTopologyDto.PipelineStageDto mapOutputToStage(OutputAdapterEntity entity) {
        String detail = "";
        if (entity.getType().contains("Http")) detail = "URL: " + entity.getUrl();
        else if (entity.getType().contains("Kafka")) detail = "Topic: " + entity.getTopicid();
        else if (entity.getType().contains("Elastic")) detail = "Index: " + entity.getIndexTemplate();
        else if (entity.getHost() != null) detail = "Host: " + entity.getHost();

        return PipelineTopologyDto.PipelineStageDto.builder()
                .id(entity.getId())
                .type(entity.getType())
                .name(entity.getType())
                .detail(detail)
                .badge("OUT")
                .enabled(entity.getEnabled())
                .build();
    }

    // ==================== Helper Methods ====================

    private String determineDataType(Object value) {
        if (value == null) return "STRING";
        if (value instanceof Integer) return "INTEGER";
        if (value instanceof Long) return "LONG";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Double || value instanceof Float) return "DOUBLE";
        return "STRING";
    }

    private Object parseValue(String value, String dataType) {
        // Null value check
        if (value == null) {
            log.warn("Attempting to parse null value for dataType: {}", dataType);
            return null;
        }

        // Empty/blank value check
        if (value.trim().isEmpty()) {
            log.warn("Attempting to parse empty value for dataType: {}", dataType);
            return null;
        }

        // DataType null check
        if (dataType == null) {
            log.warn("DataType is null for value: {}, returning as STRING", value);
            return value;
        }

        try {
            return switch (dataType.toUpperCase()) {
                case "INTEGER" -> (int) Double.parseDouble(value.trim());
                case "LONG" -> (long) Double.parseDouble(value.trim());
                case "BOOLEAN" -> Boolean.parseBoolean(value.trim());
                case "DOUBLE" -> Double.parseDouble(value.trim());
                default -> value;
            };
        } catch (NumberFormatException e) {
            log.error("Invalid configuration value: cannot parse '{}' as {}", value, dataType, e);
            throw new IllegalArgumentException(
                String.format("Invalid configuration: '%s' is not a valid %s", value, dataType), e);
        }
    }

    private void assertValid(String entityName, ConfigValidationService.ValidationResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException(entityName + " validation failed: " + result.errors());
        }
    }

    private InputAdapterConfig convertToConfig(InputAdapterEntity entity) {
        InputAdapterConfig config = new InputAdapterConfig();
        config.setId(entity.getId());
        config.setType(entity.getType()); // Assuming normalized or handled by factory
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
        return config;
    }

    private OutputAdapterConfig convertToConfig(OutputAdapterEntity entity) {
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setId(entity.getId());
        config.setType(entity.getType());
        config.setMessagetype(normalizeOutputMessageType(entity.getMessagetype()));
        config.setHost(entity.getHost());
        config.setPort(entity.getPort());
        config.setUrl(entity.getUrl());
        config.setMethod(entity.getMethod());
        config.setHeaders(readJson(entity.getHeaders(), new TypeReference<Map<String, String>>() {}));
        config.setTopicid(entity.getTopicid());
        config.setBootstrapservers(entity.getBootstrapservers());
        config.setKey(entity.getKey());
        config.setIndex(entity.getIndexTemplate());
        config.setOsUsername(entity.getOsUsername());
        config.setOsPassword(entity.getOsPassword());
        config.setAction(entity.getAction());
        config.setRoutingkey(entity.getRoutingkey());
        config.setExchange(entity.getExchange());
        config.setRmqUsername(entity.getRmqUsername());
        config.setRmqPassword(entity.getRmqPassword());
        config.setRmqPort(entity.getRmqPort());
        config.setTagpass(readJson(entity.getTagpass(), new TypeReference<Map<String, List<String>>>() {}));
        config.setBatchSize(entity.getBatchSize());
        config.setFlushIntervalMs(entity.getFlushIntervalMs());
        config.setRetryCount(entity.getRetryCount());
        config.setRetryDelayMs(entity.getRetryDelayMs());
        config.setAddOriginText(entity.getAddOriginText());
        config.setEnabled(entity.getEnabled());
        config.setTimeoutMs(entity.getTimeoutMs());
        config.setConfigParams(entity.getConfigParams());
        return config;
    }
    
    private ParserAdapterConfig convertToConfig(ParserEntity entity) {
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setId(entity.getId());
        config.setType(entity.getType());
        config.setMessagetype(entity.getMessagetype());
        config.setParam(entity.getParam());
        config.setPriority(entity.getPriority());
        config.setEnabled(entity.getEnabled());
        config.setContinueOnFailure(entity.getContinueOnFailure());
        return config;
    }

    private TransformConfig convertToConfig(TransformEntity entity) {
        TransformConfig config = new TransformConfig();
        config.setId(entity.getId());
        config.setType(entity.getType());
        config.setMessagetype(entity.getMessagetype());
        config.setPriority(entity.getPriority());
        // Param conversion is complex, skipped for now as event might just need basic info or trigger reload
        return config;
    }

    private <T> T readJson(String rawValue, TypeReference<T> typeReference) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(rawValue, typeReference);
        } catch (Exception e) {
            log.warn("Failed to parse JSON configuration value: {}", e.getMessage());
            return null;
        }
    }

    private String normalizeOutputMessageType(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            return "all";
        }
        return messageType;
    }
}
