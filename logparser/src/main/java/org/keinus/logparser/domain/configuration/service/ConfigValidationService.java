package org.keinus.logparser.domain.configuration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.infrastructure.persistence.entity.*;
import org.keinus.logparser.infrastructure.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConfigValidationService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InputAdapterRepository inputAdapterRepository;
    private final ParserRepository parserRepository;
    private final TransformRepository transformRepository;
    private final OutputAdapterRepository outputAdapterRepository;

    private final List<ValidationError> validationErrors = new ArrayList<>();

    // ==================== Individual Validation ====================

    public ValidationResult validateInputAdapter(InputAdapterEntity entity) {
        List<String> errors = new ArrayList<>();

        if (entity.getType() == null || entity.getType().trim().isEmpty()) {
            errors.add("Type is required");
        }

        if (entity.getMessagetype() == null || entity.getMessagetype().trim().isEmpty()) {
            errors.add("Message type is required");
        }

        String type = normalizeType(entity.getType());
        switch (type) {
            case "tcp", "tcpinputadapter", "udp", "udpinputadapter", "http", "httpinputadapter" -> {
                if (entity.getPort() == null) {
                    errors.add("Port is required for " + entity.getType());
                }
            }
            case "tcpmtlsgzipinputadapter", "tcp_mtls_gzip" -> validateTcpMtlsGzipConfigParams(entity, errors);
            case "kafka", "kafkainputadapter" -> {
                if (entity.getBootstrapservers() == null || entity.getTopicid() == null) {
                    errors.add("Bootstrap servers and topic are required for Kafka");
                }
            }
            case "file", "fileinputadapter" -> {
                if (entity.getPath() == null) {
                    errors.add("Path is required for File");
                }
            }
            case "snmpinputadapter" -> validateSnmpConfigParams(entity, errors);
            case "rabbitmq", "rabbitmqinputadapter" -> validateRabbitMqConfigParams(entity, errors);
            case "fake", "fakeinputadapter" -> {
                break;
            }
            default -> {
                if (entity.getType() != null && !entity.getType().trim().isEmpty()) {
                    errors.add("Unsupported input adapter type: " + entity.getType());
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private void validateSnmpConfigParams(InputAdapterEntity entity, List<String> errors) {
        String configParams = entity.getConfigParams();
        if (configParams == null || configParams.trim().isEmpty()) {
            errors.add("configParams is required for SnmpInputAdapter");
            return;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            if (!root.hasNonNull("targets") || !root.get("targets").isArray() || root.get("targets").isEmpty()) {
                errors.add("configParams.targets must contain at least one target");
            }
            if (!root.hasNonNull("oids") || !root.get("oids").isArray() || root.get("oids").isEmpty()) {
                errors.add("configParams.oids must contain at least one OID");
            }
        } catch (Exception e) {
            errors.add("configParams must be valid JSON for SnmpInputAdapter");
        }
    }

    private void validateRabbitMqConfigParams(InputAdapterEntity entity, List<String> errors) {
        String configParams = entity.getConfigParams();
        if (configParams == null || configParams.trim().isEmpty()) {
            errors.add("configParams is required for RabbitMqInputAdapter");
            return;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            if (!root.hasNonNull("queue") || root.get("queue").asText().isBlank()) {
                errors.add("configParams.queue is required for RabbitMqInputAdapter");
            }
        } catch (Exception e) {
            errors.add("configParams must be valid JSON for RabbitMqInputAdapter");
        }
    }

    private void validateTcpMtlsGzipConfigParams(InputAdapterEntity entity, List<String> errors) {
        if (entity.getPort() == null) {
            errors.add("Port is required for " + entity.getType());
        }

        String configParams = entity.getConfigParams();
        if (configParams == null || configParams.trim().isEmpty()) {
            errors.add("configParams is required for TcpMtlsGzipInputAdapter");
            return;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            requireText(root, "keyStorePath", errors);
            requireText(root, "keyStorePasswordEnv", errors);
            requireText(root, "trustStorePath", errors);
            requireText(root, "trustStorePasswordEnv", errors);
            if (root.has("maxFrameBytes") && root.get("maxFrameBytes").asLong() <= 0) {
                errors.add("configParams.maxFrameBytes must be greater than zero");
            }
            if (root.hasNonNull("ackMode") && !"queueAccepted".equals(root.get("ackMode").asText())) {
                errors.add("configParams.ackMode must be queueAccepted");
            }
        } catch (Exception e) {
            errors.add("configParams must be valid JSON for TcpMtlsGzipInputAdapter");
        }
    }

    public ValidationResult validateParser(ParserEntity entity) {
        List<String> errors = new ArrayList<>();

        if (entity.getType() == null || entity.getType().trim().isEmpty()) {
            errors.add("Type is required");
        }

        if (entity.getMessagetype() == null || entity.getMessagetype().trim().isEmpty()) {
            errors.add("Message type is required");
        }

        String type = normalizeType(entity.getType());
        if ("grok".equals(type) || "grokparser".equals(type) || "regex".equals(type) || "regexparser".equals(type)) {
            if (entity.getParam() == null || entity.getParam().trim().isEmpty()) {
                errors.add("Pattern parameter is required for " + entity.getType());
            }
        }
        if (!Set.of(
                "json", "jsonparser",
                "grok", "grokparser",
                "regex", "regexparser",
                "rfc3164", "rfc3164syslogparser",
                "rfc5424", "rfc5424syslogparser",
                "http", "httpparser"
        ).contains(type) && entity.getType() != null && !entity.getType().trim().isEmpty()) {
            errors.add("Unsupported parser type: " + entity.getType());
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    public ValidationResult validateTransform(TransformEntity entity) {
        List<String> errors = new ArrayList<>();

        if (entity.getType() == null || entity.getType().trim().isEmpty()) {
            errors.add("Type is required");
        }

        if (entity.getMessagetype() == null || entity.getMessagetype().trim().isEmpty()) {
            errors.add("Message type is required");
        }

        String type = normalizeType(entity.getType());
        switch (type) {
            case "filter" -> {
                if ((entity.getFilterPass() == null || entity.getFilterPass().trim().isEmpty()) &&
                    (entity.getFilterDrop() == null || entity.getFilterDrop().trim().isEmpty())) {
                    errors.add("Either filterPass or filterDrop is required for Filter");
                }
            }
            case "add_property", "addproperty" -> {
                if (entity.getAddProperties() == null || entity.getAddProperties().trim().isEmpty()) {
                    errors.add("Add properties is required for AddProperty");
                }
            }
            case "remove_property", "removeproperty" -> {
                if (entity.getRemoveProperties() == null || entity.getRemoveProperties().trim().isEmpty()) {
                    errors.add("Remove properties is required for RemoveProperty");
                }
            }
            default -> {
                if (entity.getType() != null && !entity.getType().trim().isEmpty()) {
                    errors.add("Unsupported transform type: " + entity.getType());
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    public ValidationResult validateOutputAdapter(OutputAdapterEntity entity) {
        List<String> errors = new ArrayList<>();

        if (entity.getType() == null || entity.getType().trim().isEmpty()) {
            errors.add("Type is required");
        }

        if (entity.getMessagetype() == null || entity.getMessagetype().trim().isEmpty()) {
            errors.add("Message type is required");
        }

        String type = normalizeType(entity.getType());
        switch (type) {
            case "tcp", "tcpoutputadapter" -> {
                if (entity.getHost() == null || entity.getPort() == null) {
                    errors.add("Host and port are required for TcpOutputAdapter");
                }
            }
            case "http", "httpoutputadapter" -> {
                if (entity.getUrl() == null || entity.getUrl().trim().isEmpty()) {
                    errors.add("URL is required for HttpOutputAdapter");
                }
            }
            case "kafka", "kafkaoutputadapter" -> {
                if (entity.getBootstrapservers() == null || entity.getTopicid() == null) {
                    errors.add("Bootstrap servers and topic are required for KafkaOutputAdapter");
                }
            }
            case "opensearch", "opensearchoutputadapter" -> {
                if (entity.getUrl() == null || entity.getUrl().trim().isEmpty()) {
                    errors.add("URL is required for OpenSearchOutputAdapter");
                }
                if (entity.getIndexTemplate() == null || entity.getIndexTemplate().trim().isEmpty()) {
                    errors.add("Index is required for OpenSearchOutputAdapter");
                }
            }
            case "rabbitmq", "rabbitmqadapter" -> {
                if (entity.getHost() == null || entity.getHost().trim().isEmpty()) {
                    errors.add("Host is required for RabbitMQAdapter");
                }
                if (entity.getExchange() == null || entity.getExchange().trim().isEmpty()) {
                    errors.add("Exchange is required for RabbitMQAdapter");
                }
                if (entity.getRoutingkey() == null || entity.getRoutingkey().trim().isEmpty()) {
                    errors.add("Routing key is required for RabbitMQAdapter");
                }
            }
            case "mariadb", "mariadboutputadapter" -> validateMariaDbConfigParams(entity, errors);
            case "console", "consoleoutputadapter", "benchmark", "benchmarkadapter" -> {
                break;
            }
            default -> {
                if (entity.getType() != null && !entity.getType().trim().isEmpty()) {
                    errors.add("Unsupported output adapter type: " + entity.getType());
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private void validateMariaDbConfigParams(OutputAdapterEntity entity, List<String> errors) {
        String configParams = entity.getConfigParams();
        if (configParams == null || configParams.trim().isEmpty()) {
            errors.add("configParams is required for MariaDbOutputAdapter");
            return;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(configParams);
            requireText(root, "jdbcUrl", errors);
            requireText(root, "usernameEnv", errors);
            requireText(root, "passwordEnv", errors);
            requireText(root, "tableName", errors);
            if (root.has("batchSize") && root.get("batchSize").asInt() <= 0) {
                errors.add("configParams.batchSize must be greater than zero");
            }
            if (root.has("flushIntervalMs") && root.get("flushIntervalMs").asLong() <= 0) {
                errors.add("configParams.flushIntervalMs must be greater than zero");
            }
        } catch (Exception e) {
            errors.add("configParams must be valid JSON for MariaDbOutputAdapter");
        }
    }

    private void requireText(JsonNode root, String fieldName, List<String> errors) {
        if (!root.hasNonNull(fieldName) || root.get(fieldName).asText().isBlank()) {
            errors.add("configParams." + fieldName + " is required");
        }
    }

    // ==================== Pipeline Integrity Validation ====================

    public PipelineIntegrityResult validatePipelineIntegrity() {
        log.info("Validating pipeline integrity");
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Get all entities
        List<InputAdapterEntity> inputAdapters = inputAdapterRepository.findAll();
        List<ParserEntity> parsers = parserRepository.findAll();
        List<OutputAdapterEntity> outputAdapters = outputAdapterRepository.findAll();

        // If DB is completely empty, return valid with warnings (allow runtime configuration)
        if (inputAdapters.isEmpty() && parsers.isEmpty() && outputAdapters.isEmpty()) {
            log.info("Database is empty - pipeline will not start until configuration is created");
            warnings.add("Database is empty - pipeline will not start");
            warnings.add("Please configure input adapters, parsers, and output adapters");
            return new PipelineIntegrityResult(true, errors, warnings);
        }

        // Collect all message types
        Set<String> inputMessageTypes = new HashSet<>();
        Set<String> parserMessageTypes = new HashSet<>();
        Set<String> outputMessageTypes = new HashSet<>();
        boolean hasGlobalOutput = false;

        inputAdapters.forEach(ia -> inputMessageTypes.add(ia.getMessagetype()));
        parsers.forEach(p -> parserMessageTypes.add(p.getMessagetype()));
        for (OutputAdapterEntity outputAdapter : outputAdapters) {
            String messageType = outputAdapter.getMessagetype();
            if (isGlobalOutputType(messageType)) {
                hasGlobalOutput = true;
                continue;
            }
            outputMessageTypes.add(messageType);
        }

        // Validate: Each input message type should have at least one parser (warning only)
        for (String inputMsgType : inputMessageTypes) {
            if (!parserMessageTypes.contains(inputMsgType)) {
                warnings.add(String.format("Input message type '%s' has no corresponding parser - will pass through", inputMsgType));
            }
        }

        // Validate: Each parser message type should have at least one output
        for (String parserMsgType : parserMessageTypes) {
            if (!hasGlobalOutput && !outputMessageTypes.contains(parserMsgType)) {
                warnings.add(String.format("Parser message type '%s' has no corresponding output adapter", parserMsgType));
            }
        }

        // Check for orphaned output adapters
        for (String outputMsgType : outputMessageTypes) {
            if (!parserMessageTypes.contains(outputMsgType)) {
                warnings.add(String.format("Output message type '%s' has no corresponding parser", outputMsgType));
            }
        }

        // Check for enabled status consistency
        long enabledInputs = inputAdapters.stream().filter(InputAdapterEntity::getEnabled).count();
        long enabledOutputs = outputAdapters.stream().filter(OutputAdapterEntity::getEnabled).count();

        if (enabledInputs == 0) {
            warnings.add("No input adapters are enabled");
        }
        if (enabledOutputs == 0) {
            warnings.add("No output adapters are enabled");
        }

        boolean isValid = errors.isEmpty();
        log.info("Pipeline integrity validation completed: valid={}, errors={}, warnings={}",
                isValid, errors.size(), warnings.size());

        return new PipelineIntegrityResult(isValid, errors, warnings);
    }

    private boolean isGlobalOutputType(String messageType) {
        return messageType != null && "all".equalsIgnoreCase(messageType.trim());
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== Error Management ====================

    public List<ValidationError> getAllValidationErrors() {
        return new ArrayList<>(validationErrors);
    }

    public Map<String, List<ValidationError>> getErrorsByEntity() {
        Map<String, List<ValidationError>> errorsByEntity = new HashMap<>();
        for (ValidationError error : validationErrors) {
            errorsByEntity.computeIfAbsent(error.entityType(), k -> new ArrayList<>()).add(error);
        }
        return errorsByEntity;
    }

    public void clearValidationErrors() {
        validationErrors.clear();
    }

    // ==================== Inner Classes ====================

    public record ValidationResult(boolean isValid, List<String> errors) {}

    public record PipelineIntegrityResult(boolean isValid, List<String> errors, List<String> warnings) {}

    public record ValidationError(String entityType, Long entityId, String field, String message) {}
}
