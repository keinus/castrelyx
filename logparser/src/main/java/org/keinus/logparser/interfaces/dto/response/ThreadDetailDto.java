package org.keinus.logparser.interfaces.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 스레드 상세 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreadDetailDto {
    /**
     * 스레드 이름
     */
    private String name;

    /**
     * 스레드 ID
     */
    private Long threadId;

    /**
     * 스레드 상태
     */
    private String state;

    /**
     * 실행 중 여부
     */
    private Boolean alive;

    /**
     * 인터럽트 여부
     */
    private Boolean interrupted;

    /**
     * 컴포넌트 타입 (INPUT, OUTPUT, PARSER, BATCH, MONITOR)
     */
    private String componentType;

    /**
     * 컴포넌트 ID (어댑터 ID, 파서 ID 등)
     */
    private Long componentId;

    /**
     * 컴포넌트 이름
     */
    private String componentName;

    /**
     * 컴포넌트 설정 정보 (타입, 포트, 주소 등)
     */
    private Map<String, Object> componentConfig;

    /**
     * 추가 메타데이터
     */
    private Map<String, Object> metadata;
}
