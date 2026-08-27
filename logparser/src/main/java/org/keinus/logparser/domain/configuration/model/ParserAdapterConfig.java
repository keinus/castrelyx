package org.keinus.logparser.domain.configuration.model;

import org.keinus.logparser.domain.configuration.model.ConfigSchema.*;
import lombok.Data;

/**
 * 파서 어댑터 설정을 위한 타입 안전한 설정 클래스입니다.
 */
@Data
public class ParserAdapterConfig {

    private Long id;

    @Required
    @Choice(values = {
        "JsonParser",
        "GrokParser",
        "RegexParser",
        "RFC3164SyslogParser",
        "RFC5424SyslogParser",
        "HttpParser"
    })
    @Description("파서의 타입")
    private String type;

    @Required
    @Description("처리할 메시지 타입")
    private String messagetype;

    @Description("파서별 설정 파라미터")
    private String param;

    @Description("파서 입력으로 사용할 event field (비어 있으면 originalText)")
    private String sourceField;

    @Range(min = 0)
    @Default("0")
    @Description("공통 processing step 순서 (낮을수록 먼저 실행)")
    private Integer priority;

    @Default("true")
    @Description("파서 활성화 여부")
    private Boolean enabled;

    @Default("false")
    @Description("파싱 실패 시 다음 processing step 실행 여부")
    private Boolean continueOnFailure;

    /**
     * 파서 타입별 설정 검증
     */
    public void validate() {
        if (sourceField != null) {
            sourceField = sourceField.trim();
            if (sourceField.isEmpty()) {
                sourceField = null;
            } else if (sourceField.length() > 255 || !sourceField.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("sourceField must be a top-level field name");
            }
        }
        switch (type) {
            case "GrokParser":
            case "RegexParser":
                if (param == null || param.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'param' field with pattern");
                }
                break;
            case "JsonParser":
            case "RFC3164SyslogParser":
            case "RFC5424SyslogParser":
                // 파라미터 불필요
                break;
            case "HttpParser":
                // HTTP 파서는 특별한 파라미터 검증 로직이 필요할 수 있음
                break;
        }
    }
}
