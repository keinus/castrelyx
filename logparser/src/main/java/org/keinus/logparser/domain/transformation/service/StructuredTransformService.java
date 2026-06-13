package org.keinus.logparser.domain.transformation.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.SubTableRule;
import org.keinus.logparser.domain.model.structured.CommonFields;
import org.keinus.logparser.domain.model.structured.StructuredEvent;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.infrastructure.util.converter.IpValidator;
import org.keinus.logparser.infrastructure.util.converter.NumberParser;
import org.keinus.logparser.infrastructure.util.converter.TimestampParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StructuredTransformService {
    private static final Logger log = LoggerFactory.getLogger(StructuredTransformService.class);

    private final MappingRepository mappingRepository;
    private final ConditionEvaluator conditionEvaluator;
    private final StructuredEventSerializer serializer;
    private final Map<String, Optional<MappingConfiguration>> configCache = new java.util.concurrent.ConcurrentHashMap<>();

    public StructuredTransformService(MappingRepository mappingRepository, 
                                      ConditionEvaluator conditionEvaluator,
                                      StructuredEventSerializer serializer) {
        this.mappingRepository = mappingRepository;
        this.conditionEvaluator = conditionEvaluator;
        this.serializer = serializer;
    }

    /**
     * Clears the internal configuration cache to force reloading from the repository.
     */
    public void reload() {
        log.info("Reloading StructuredTransformService configuration cache");
        configCache.clear();
        conditionEvaluator.clearCache();
    }

    public void invalidateCache(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            reload();
            return;
        }

        log.info("Invalidating structured transform cache for messageType={}", messageType);
        configCache.remove(messageType);
        conditionEvaluator.clearCache();
    }

    /**
     * Transforms the LogEvent and updates its internal state with the structured data.
     * This bridges the new Transformation Engine with the existing Pipeline.
     */
    public boolean applyToLogEvent(LogEvent logEvent) {
        try {
            StructuredEvent result = transform(logEvent);
            Map<String, Object> structuredMap = serializer.toMap(result);
            
            // Replace flat fields with structured fields
            // Note: This wipes original flat fields from the "fields" map, 
            // but they are preserved in "additionalAttributes" or "raw_log" inside the structure.
            logEvent.getFields().clear();
            logEvent.setFields(structuredMap);
            
            logEvent.markAsTransformed();
            return true;
        } catch (Exception e) {
            log.error("Failed to apply structured transformation", e);
            logEvent.markAsError(e.getMessage());
            return false;
        }
    }

    public StructuredEvent transform(LogEvent logEvent) {
        String messageType = logEvent.getMessageType();
        // 1. Load Configuration (Cached)
        MappingConfiguration config = configCache.computeIfAbsent(messageType, key -> 
            mappingRepository.findByMessageType(key)
        ).orElse(null);
        return transform(logEvent, config);
    }

    public StructuredEvent transform(LogEvent logEvent, MappingConfiguration config) {
        Map<String, Object> sourceData = logEvent.getFields(); // Parsed fields

        // If no config, we might want a default generic event or return null?
        // For now, create a basic event with unmapped data.
        if (config == null) {
            return createDefaultEvent(logEvent);
        }

        // Track mapped source fields to handle unmapped later
        Set<String> mappedSourceKeys = new HashSet<>();

        // 3. Process Common Fields
        CommonFields.CommonFieldsBuilder commonBuilder = CommonFields.builder();
        commonBuilder.ingestTime(Instant.now());
        
        // Raw Log and Source are always present
        commonBuilder.rawLog(logEvent.getOriginalText());
        commonBuilder.logSource(logEvent.getMessageType()); // or derive from somewhere else

        for (FieldMapping mapping : config.getCommonMappings()) {
            String srcKey = mapping.getSourceField();
            Object value = sourceData.get(srcKey);
            
            if (value == null && mapping.getDefaultValue() != null) {
                value = mapping.getDefaultValue();
            }

            if (value != null) {
                applyCommonField(commonBuilder, mapping.getTargetField(), value);
                mappedSourceKeys.add(srcKey);
            }
        }
        CommonFields commonFields = commonBuilder.build();

        // 4. Process Sub-Table Rules
        String subDomainType = null;
        Map<String, Object> subFields = new HashMap<>();

        for (SubTableRule rule : config.getSubTableRules()) {
            boolean matched = conditionEvaluator.evaluate(rule.getConditionExpression(), sourceData);
            // System.out.println("Checking Rule: " + rule.getConditionExpression() + " Matched: " + matched);
            if (matched) {
                subDomainType = rule.getTargetSubTable();
                
                for (FieldMapping mapping : rule.getMappings()) {
                    String srcKey = mapping.getSourceField();
                    Object value = sourceData.get(srcKey);
                    // System.out.println("Mapping " + srcKey + " (" + value + ") to " + mapping.getTargetField());
                    
                    if (value == null && mapping.getDefaultValue() != null) {
                        value = mapping.getDefaultValue();
                    }

                    if (value != null) {
                        // Apply Type Conversion if necessary (Logic needed here or simple storage?)
                        // For sub-fields (Map), we store as-is or try simple conversion?
                        // "Type Conversion & Validation" requirement says "Strong type casting".
                        // We should infer type from target schema if we had it.
                        // For now, we store as is, but maybe standard types (Long, IP) should be converted.
                        subFields.put(mapping.getTargetField(), value);
                        mappedSourceKeys.add(srcKey);
                    }
                }
                break; // First match wins
            }
        }

        // 5. Unmapped Attributes
        Map<String, Object> additionalAttributes = new HashMap<>();
        for (Map.Entry<String, Object> entry : sourceData.entrySet()) {
            if (!mappedSourceKeys.contains(entry.getKey())) {
                additionalAttributes.put(entry.getKey(), entry.getValue());
            }
        }

        return StructuredEvent.builder()
                .common(commonFields)
                .subDomainType(subDomainType)
                .subFields(subFields)
                .additionalAttributes(additionalAttributes)
                .build();
    }

    private StructuredEvent createDefaultEvent(LogEvent logEvent) {
         CommonFields common = CommonFields.builder()
                 .ingestTime(Instant.now())
                 .rawLog(logEvent.getOriginalText())
                 .logSource(logEvent.getMessageType())
                 .build();
         
         return StructuredEvent.builder()
                 .common(common)
                 .additionalAttributes(new HashMap<>(logEvent.getFields()))
                 .build();
    }

    private void applyCommonField(CommonFields.CommonFieldsBuilder builder, String targetField, Object value) {
        try {
            switch (targetField) {
                case "event_time":
                    builder.eventTime(TimestampParser.parse(value));
                    break;
                case "event_category":
                    builder.eventCategory(String.valueOf(value));
                    break;
                case "event_type":
                    builder.eventType(String.valueOf(value));
                    break;
                case "event_action":
                    builder.eventAction(String.valueOf(value));
                    break;
                case "event_result":
                    builder.eventResult(String.valueOf(value));
                    break;
                case "severity":
                    builder.severity(NumberParser.parseInt(value));
                    break;
                case "src_ip":
                    builder.srcIp(IpValidator.validate(value));
                    break;
                case "src_port":
                    builder.srcPort(NumberParser.parseInt(value));
                    break;
                case "dst_ip":
                    builder.dstIp(IpValidator.validate(value));
                    break;
                case "dst_port":
                    builder.dstPort(NumberParser.parseInt(value));
                    break;
                case "protocol":
                    builder.protocol(String.valueOf(value));
                    break;
                case "src_host":
                    builder.srcHost(String.valueOf(value));
                    break;
                case "dst_host":
                    builder.dstHost(String.valueOf(value));
                    break;
                case "user_name":
                    builder.userName(String.valueOf(value));
                    break;
                case "user_id":
                    builder.userId(String.valueOf(value));
                    break;
                case "log_source":
                    builder.logSource(String.valueOf(value));
                    break;
                default:
                    log.debug("Unknown common target field: {}", targetField);
            }
        } catch (Exception e) {
            log.warn("Error mapping common field {}: {}", targetField, e.getMessage());
        }
    }
}
