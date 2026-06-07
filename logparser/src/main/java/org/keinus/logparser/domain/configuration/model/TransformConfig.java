package org.keinus.logparser.domain.configuration.model;

import lombok.Data;

/**
 * 단일 변환(transform) 규칙에 대한 설정을 담는 데이터 클래스입니다.
 * <p>
 * 이 클래스는 YAML 설정 파일의 'transform' 섹션에 있는 각 항목에 매핑됩니다.
 * 특정 변환 로직을 어떤 메시지 타입에 적용할지를 정의합니다.
 *
 * @see org.keinus.logparser.config.ApplicationProperties
 * @see lombok.Data
 */
@Data
public class TransformConfig {
    private Long id;
    String type;
    String messagetype;
    Integer priority;
    TransformParamConfig param;
}
