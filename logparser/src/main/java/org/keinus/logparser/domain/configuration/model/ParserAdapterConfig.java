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

    @Range(min = 0, max = 100)
    @Default("0")
    @Description("파서 처리 우선순위 (낮을수록 먼저 실행)")
    private Integer priority;

    @Default("true")
    @Description("파서 활성화 여부")
    private Boolean enabled;

    @Default("false")
    @Description("파싱 실패 시 다음 파서 시도 여부")
    private Boolean continueOnFailure;

    /**
     * 파서 타입별 설정 검증
     */
    public void validate() {
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