package org.keinus.logparser.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.transformation.model.Filter;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Filter 클래스의 단위 테스트
 *
 * 테스트 대상 함수들:
 * - init(TransformParamConfig) : 필터 초기화 테스트
 * - transform(LogEvent) : 필터링 로직 테스트
 * - parseParam(Map<String, List<String>>, Map<String, String>) : 파라미터 파싱 테스트 (private 메서드이지만 init()을 통해 간접 테스트)
 */
class FilterTest {

    private Filter filter;

    @BeforeEach
    void setUp() {
        filter = new Filter();
    }

    @Test
    @DisplayName("init() 테스트 - null 파라미터로 초기화")
    void testInitWithNullParam() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        config.setPass(null);
        config.setDrop(null);

        // When & Then
        assertDoesNotThrow(() -> filter.init(config));
    }

    @Test
    @DisplayName("init() 테스트 - 빈 파라미터로 초기화")
    void testInitWithEmptyParam() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        config.setPass(new HashMap<>());
        config.setDrop(new HashMap<>());

        // When & Then
        assertDoesNotThrow(() -> filter.init(config));
    }

    @Test
    @DisplayName("init() 테스트 - 유효한 pass/drop 파라미터로 초기화")
    void testInitWithValidParams() {
        // Given
        TransformParamConfig config = new TransformParamConfig();

        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        passParams.put("source", "app1,app2");

        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("status", "DEBUG,TRACE");

        config.setPass(passParams);
        config.setDrop(dropParams);

        // When & Then
        assertDoesNotThrow(() -> filter.init(config));
    }

    @Test
    @DisplayName("transform() 테스트 - pass 필터 통과")
    void testTransformPassFilter() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("transform() 테스트 - pass 필터 차단")
    void testTransformPassFilterBlocked() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "DEBUG");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("transform() 테스트 - drop 필터 차단")
    void testTransformDropFilterBlocked() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("level", "DEBUG,TRACE");
        config.setPass(new HashMap<>());
        config.setDrop(dropParams);

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "DEBUG");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("transform() 테스트 - drop 필터 통과")
    void testTransformDropFilterPassed() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("level", "DEBUG,TRACE");
        config.setPass(new HashMap<>());
        config.setDrop(dropParams);

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("transform() 테스트 - pass와 drop 필터 모두 적용")
    void testTransformBothPassAndDropFilters() {
        // Given
        TransformParamConfig config = new TransformParamConfig();

        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");

        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("source", "test,debug");

        config.setPass(passParams);
        config.setDrop(dropParams);

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO");
        logEvent.setField("source", "production");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result); // level은 pass, source는 drop 목록에 없음
    }

    @Test
    @DisplayName("transform() 테스트 - drop 필터가 우선 적용")
    void testTransformDropFilterPriority() {
        // Given
        TransformParamConfig config = new TransformParamConfig();

        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");

        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("source", "test,debug");

        config.setPass(passParams);
        config.setDrop(dropParams);

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO"); // pass 조건 만족
        logEvent.setField("source", "test"); // drop 조건 만족

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result); // drop이 우선 적용되어 차단
    }

    @Test
    @DisplayName("transform() 테스트 - 필드 값이 null인 경우")
    void testTransformWithNullFieldValue() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", null);

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result); // null 값은 pass 조건을 만족하지 않음
    }

    @Test
    @DisplayName("transform() 테스트 - 필드가 존재하지 않는 경우")
    void testTransformWithMissingField() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        // level 필드를 설정하지 않음

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result); // 필드가 없으면 pass 조건을 만족하지 않음
    }

    @Test
    @DisplayName("transform() 테스트 - 다중 필드 필터링")
    void testTransformMultipleFields() {
        // Given
        TransformParamConfig config = new TransformParamConfig();

        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,WARN,ERROR");
        passParams.put("component", "auth,payment,notification");

        Map<String, String> dropParams = new HashMap<>();
        dropParams.put("environment", "test,development");

        config.setPass(passParams);
        config.setDrop(dropParams);

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "ERROR");
        logEvent.setField("component", "payment");
        logEvent.setField("environment", "production");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result); // 모든 조건을 만족
    }

    @Test
    @DisplayName("transform() 테스트 - 공백 포함 값 처리")
    void testTransformWithWhitespaceValues() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", " INFO , WARN , ERROR "); // 공백 포함
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result); // 공백이 제거되어 정상 처리
    }

    @Test
    @DisplayName("transform() 테스트 - 빈 값 처리")
    void testTransformWithEmptyValues() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "INFO,,WARN,ERROR,"); // 빈 값 포함
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "WARN");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result); // 빈 값은 무시되고 정상 처리
    }

    @Test
    @DisplayName("transform() 테스트 - 필터링 조건 없음")
    void testTransformNoFilterConditions() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        config.setPass(new HashMap<>());
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "DEBUG");

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertTrue(result); // 필터링 조건이 없으면 모든 메시지 통과
    }

    @Test
    @DisplayName("transform() 테스트 - 대소문자 구분")
    void testTransformCaseSensitivity() {
        // Given
        TransformParamConfig config = new TransformParamConfig();
        Map<String, String> passParams = new HashMap<>();
        passParams.put("level", "info,warn,error"); // 소문자
        config.setPass(passParams);
        config.setDrop(new HashMap<>());

        filter.init(config);

        LogEvent logEvent = new LogEvent("test message", "localhost", "test");
        logEvent.setField("level", "INFO"); // 대문자

        // When
        boolean result = filter.transform(logEvent);

        // Then
        assertFalse(result); // 대소문자가 다르므로 차단
    }
}