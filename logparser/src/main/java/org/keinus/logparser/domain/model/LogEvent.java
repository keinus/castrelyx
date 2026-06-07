package org.keinus.logparser.domain.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.Getter;
import lombok.Setter;

/**
 * ETL 파이프라인에서 사용되는 통합 로그 이벤트 클래스입니다.
 *
 * 이 클래스는 원본 메시지부터 최종 처리된 결과까지 전 과정을 지원하며,
 * 메모리 효율성과 확장성을 고려하여 설계되었습니다.
 *
 * 주요 특징:
 * - 단일 클래스로 전체 파이프라인 단계 지원
 * - 메타데이터와 실제 데이터의 명확한 분리
 * - JSON 직렬화 최적화
 * - 지연 초기화를 통한 메모리 절약
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper OUTPUT_MAPPER = createOutputMapper();

    // === Core Metadata (ETL 과정에서 항상 필요) ===
    @Getter
    private String messageType;

    @Getter
    private Instant timestamp;

    @Getter
    private String sourceHost;

    // === Raw Data ===
    @Getter
    @JsonIgnore  // 출력 시 제외 (origin_text로 별도 처리)
    private String originalText;

    // === Parsed/Transformed Data ===
    @SuppressWarnings("serial")
    private Map<String, Object> fields;

    // === Processing State ===
    @Getter @Setter
    @JsonIgnore
    private ProcessingStage stage = ProcessingStage.RAW;

    @Getter @Setter
    @JsonIgnore
    private String processingError;

    @JsonIgnore
    private transient Map<String, Object> preparedOutputMap;

    @JsonIgnore
    private transient Map<String, Object> preparedOutputMapWithOrigin;

    @JsonIgnore
    private transient String preparedOutputJson;

    @JsonIgnore
    private transient String preparedOutputJsonWithOrigin;

    // === 생성자 ===

    /**
     * 원본 메시지로부터 새 LogEvent를 생성합니다.
     */
    public LogEvent(String originalText, String sourceHost, String messageType) {
        this.originalText = Objects.requireNonNull(originalText, "Original text cannot be null");
        this.sourceHost = sourceHost;
        this.messageType = messageType;
        this.timestamp = Instant.now();
        this.stage = ProcessingStage.RAW;
    }

    public LogEvent(String originalText) {
        this.originalText = originalText;
        this.sourceHost = "localhost";
        this.messageType = "test";
        this.timestamp = Instant.now();
        this.stage = ProcessingStage.RAW;
    }

    /**
     * 빈 LogEvent를 생성합니다 (Jackson 역직렬화용).
     */
    public LogEvent() {
        this.timestamp = Instant.now();
    }

    // === Fields 관리 (지연 초기화) ===

    /**
     * 파싱된 필드 맵을 반환합니다. 필요시 지연 초기화됩니다.
     */
    public Map<String, Object> getFields() {
        if (fields == null) {
            fields = new HashMap<>();
        }
        return fields;
    }

    /**
     * 특정 필드 값을 설정합니다.
     */
    public LogEvent setField(String key, Object value) {
        invalidateOutputPayload();
        getFields().put(key, value);
        return this;
    }

    /**
     * 특정 필드 값을 조회합니다.
     */
    public Object getField(String key) {
        return fields != null ? fields.get(key) : null;
    }

    /**
     * 특정 필드를 제거합니다.
     */
    public LogEvent removeField(String key) {
        if (fields != null) {
            invalidateOutputPayload();
            fields.remove(key);
        }
        return this;
    }

    /**
     * 여러 필드를 한번에 설정합니다.
     */
    public LogEvent setFields(Map<String, Object> newFields) {
        if (newFields != null && !newFields.isEmpty()) {
            invalidateOutputPayload();
            getFields().putAll(newFields);
        }
        return this;
    }

    /**
     * 필드가 비어있는지 확인합니다.
     */
    public boolean hasFields() {
        return fields != null && !fields.isEmpty();
    }

    // === Processing State 관리 ===

    /**
     * 파싱 단계로 전환합니다.
     */
    public LogEvent markAsParsed() {
        this.stage = ProcessingStage.PARSED;
        return this;
    }

    /**
     * 변환 단계로 전환합니다.
     */
    public LogEvent markAsTransformed() {
        this.stage = ProcessingStage.TRANSFORMED;
        return this;
    }

    /**
     * 에러 상태로 설정합니다.
     */
    public LogEvent markAsError(String error) {
        this.stage = ProcessingStage.ERROR;
        this.processingError = error;
        return this;
    }

    public void setMessageType(String messageType) {
        invalidateOutputPayload();
        this.messageType = messageType;
    }

    public void setTimestamp(Instant timestamp) {
        invalidateOutputPayload();
        this.timestamp = timestamp;
    }

    public void setSourceHost(String sourceHost) {
        invalidateOutputPayload();
        this.sourceHost = sourceHost;
    }

    public void setOriginalText(String originalText) {
        invalidateOutputPayload();
        this.originalText = originalText;
    }

    // === JSON 출력용 메서드 ===

    public void prepareOutputPayload() {
        prepareOutputPayload(false);
    }

    public void prepareOutputPayload(boolean includeOriginText) {
        Map<String, Object> snapshot = buildOutputMap(includeOriginText);
        String serialized = serializeOutputMap(snapshot);

        if (includeOriginText) {
            this.preparedOutputMapWithOrigin = snapshot;
            this.preparedOutputJsonWithOrigin = serialized;
        } else {
            this.preparedOutputMap = snapshot;
            this.preparedOutputJson = serialized;
        }
    }

    public String toOutputJson() {
        return toOutputJson(false);
    }

    public String toOutputJson(boolean includeOriginText) {
        String cached = includeOriginText ? preparedOutputJsonWithOrigin : preparedOutputJson;
        if (cached != null) {
            return cached;
        }
        return serializeOutputMap(toOutputMap(includeOriginText));
    }

    /**
     * JSON 직렬화를 위한 출력 맵을 생성합니다.
     * 출력 설정에 따라 원본 텍스트 포함 여부를 결정할 수 있습니다.
     */
    public Map<String, Object> toOutputMap() {
        return toOutputMap(false);
    }

    public Map<String, Object> toOutputMap(boolean includeOriginText) {
        Map<String, Object> cached = includeOriginText ? preparedOutputMapWithOrigin : preparedOutputMap;
        if (cached != null) {
            return cached;
        }
        return buildOutputMap(includeOriginText);
    }

    private Map<String, Object> buildOutputMap(boolean includeOriginText) {
        Map<String, Object> output = new HashMap<>();

        // 메타데이터 추가
        if (messageType != null) output.put("message_type", messageType);
        if (timestamp != null) output.put("@timestamp", timestamp);
        if (sourceHost != null) output.put("source_host", sourceHost);

        // 파싱된 필드 추가
        if (fields != null && !fields.isEmpty()) {
            output.putAll(fields);
        }

        if (includeOriginText && originalText != null) {
            output.put("origin_text", originalText);
        }

        return output;
    }

    private String serializeOutputMap(Map<String, Object> outputMap) {
        try {
            return OUTPUT_MAPPER.writeValueAsString(outputMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize output payload", e);
        }
    }

    private void invalidateOutputPayload() {
        preparedOutputMap = null;
        preparedOutputMapWithOrigin = null;
        preparedOutputJson = null;
        preparedOutputJsonWithOrigin = null;
    }

    private static ObjectMapper createOutputMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    // === 편의 메서드 ===

    /**
     * 이벤트가 처리 가능한 상태인지 확인합니다.
     */
    public boolean isProcessable() {
        return stage != ProcessingStage.ERROR && originalText != null;
    }

    /**
     * 처리 단계를 확인합니다.
     */
    public boolean isParsed() {
        return stage.ordinal() >= ProcessingStage.PARSED.ordinal();
    }

    public boolean isTransformed() {
        return stage.ordinal() >= ProcessingStage.TRANSFORMED.ordinal();
    }

    public boolean hasError() {
        return stage == ProcessingStage.ERROR;
    }

    @Override
    public String toString() {
        return String.format("LogEvent{type='%s', stage=%s, host='%s', fieldsCount=%d}",
                messageType, stage, sourceHost, hasFields() ? fields.size() : 0);
    }

    // === 처리 단계 열거형 ===

    public enum ProcessingStage {
        RAW,         // 원본 상태
        PARSED,      // 파싱 완료
        TRANSFORMED, // 변환 완료
        ERROR        // 처리 에러
    }
}
