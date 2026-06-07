package org.keinus.logparser.domain.model.structured;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 정형화된 이벤트 모델의 최상위 컨테이너.
 * RDB 스키마와 독립적으로 유연한 구조를 가집니다.
 */
@Data
@Builder
public class StructuredEvent {
    // 공통 필드 (CORE)
    private CommonFields common;

    // 서브 도메인 타입 (예: "event_web", "event_network")
    private String subDomainType;

    // 서브 도메인 데이터 (Key-Value)
    @Builder.Default
    private Map<String, Object> subFields = new HashMap<>();

    // 매핑되지 않은 나머지 데이터 (확장 필드)
    @Builder.Default
    private Map<String, Object> additionalAttributes = new HashMap<>();

    public void addSubField(String key, Object value) {
        this.subFields.put(key, value);
    }

    public void addAttribute(String key, Object value) {
        this.additionalAttributes.put(key, value);
    }
}
