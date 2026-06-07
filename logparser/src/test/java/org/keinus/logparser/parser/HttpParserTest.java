package org.keinus.logparser.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.model.HttpParser;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpParser 클래스의 단위 테스트
 *
 * 테스트 대상 함수들:
 * - init(Object) : 파서 초기화 테스트
 * - parse(LogEvent) : HTTP 메시지 파싱 테스트
 */
class HttpParserTest {

    private HttpParser parser;

    @BeforeEach
    void setUp() {
        parser = new HttpParser();
        parser.init(null); // 초기화 파라미터 없음
    }

    @Test
    @DisplayName("init() 테스트 - 초기화")
    void testInit() {
        // When & Then
        assertDoesNotThrow(() -> parser.init(null));
        assertDoesNotThrow(() -> parser.init("any parameter"));
    }

    @Test
    @DisplayName("parse() 테스트 - 기본 GET 요청")
    void testParseGetRequest() {
        // Given
        String httpMessage = "GET /api/test HTTP/1.1\r\n" +
                           "Host: localhost:8080\r\n" +
                           "User-Agent: Test-Agent\r\n" +
                           "Accept: application/json\r\n" +
                           "\r\n";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        Map<String, Object> fields = logEvent.getFields();
        assertNotNull(fields.get("headers"));
        assertNotNull(fields.get("body"));

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) fields.get("headers");
        assertEquals("localhost:8080", headers.get("HOST"));
        assertEquals("Test-Agent", headers.get("USER-AGENT"));
        assertEquals("application/json", headers.get("ACCEPT"));

        String body = (String) fields.get("body");
        assertEquals("", body.trim()); // GET 요청은 본문이 비어있음
    }

    @Test
    @DisplayName("parse() 테스트 - POST 요청 with Body")
    void testParsePostRequestWithBody() {
        // Given
        String requestBody = "{\"name\": \"test\", \"value\": 123}";
        String httpMessage = "POST /api/create HTTP/1.1\r\n" +
                           "Host: example.com\r\n" +
                           "Content-Type: application/json\r\n" +
                           "Content-Length: " + requestBody.length() + "\r\n" +
                           "\r\n" +
                           requestBody;

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        Map<String, Object> fields = logEvent.getFields();
        assertNotNull(fields.get("headers"));
        assertNotNull(fields.get("body"));

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) fields.get("headers");
        assertEquals("example.com", headers.get("HOST"));
        assertEquals("application/json", headers.get("CONTENT-TYPE"));
        assertEquals(String.valueOf(requestBody.length()), headers.get("CONTENT-LENGTH"));

        String body = (String) fields.get("body");
        assertTrue(body.contains(requestBody));
    }

    @Test
    @DisplayName("parse() 테스트 - 헤더에 콜론이 포함된 값")
    void testParseHeaderWithColonInValue() {
        // Given
        String httpMessage = "GET /test HTTP/1.1\r\n" +
                           "Host: localhost:8080\r\n" +
                           "Authorization: Bearer token:with:colons\r\n" +
                           "Custom-Header: value:with:multiple:colons\r\n" +
                           "\r\n";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) logEvent.getFields().get("headers");
        assertEquals("localhost:8080", headers.get("HOST"));
        assertEquals("Bearer token:with:colons", headers.get("AUTHORIZATION"));
        assertEquals("value:with:multiple:colons", headers.get("CUSTOM-HEADER"));
    }

    @Test
    @DisplayName("parse() 테스트 - 잘못된 헤더 형식")
    void testParseInvalidHeaderFormat() {
        // Given
        String httpMessage = "GET /test HTTP/1.1\r\n" +
                           "Host: localhost:8080\r\n" +
                           "InvalidHeaderWithoutColon\r\n" +
                           "Valid-Header: valid-value\r\n" +
                           "\r\n";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result); // 파싱은 성공하지만 잘못된 헤더는 무시됨
        assertTrue(logEvent.hasFields());

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) logEvent.getFields().get("headers");
        assertEquals("localhost:8080", headers.get("HOST"));
        assertEquals("valid-value", headers.get("VALID-HEADER"));
        assertNull(headers.get("InvalidHeaderWithoutColon")); // 잘못된 헤더는 포함되지 않음
    }

    @Test
    @DisplayName("parse() 테스트 - 빈 헤더 값")
    void testParseEmptyHeaderValue() {
        // Given
        String httpMessage = "GET /test HTTP/1.1\r\n" +
                           "Host: localhost:8080\r\n" +
                           "Empty-Header: \r\n" +
                           "Another-Header: value\r\n" +
                           "\r\n";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) logEvent.getFields().get("headers");
        assertEquals("localhost:8080", headers.get("HOST"));
        assertEquals("", headers.get("EMPTY-HEADER"));
        assertEquals("value", headers.get("ANOTHER-HEADER"));
    }

    @Test
    @DisplayName("parse() 테스트 - 멀티라인 본문")
    void testParseMultilineBody() {
        // Given
        String requestBody = "Line 1\nLine 2\nLine 3";
        String httpMessage = "POST /api/multiline HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "Content-Type: text/plain\r\n" +
                           "\r\n" +
                           requestBody;

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        String body = (String) logEvent.getFields().get("body");
        assertTrue(body.contains("Line 1"));
        assertTrue(body.contains("Line 2"));
        assertTrue(body.contains("Line 3"));
    }

    @Test
    @DisplayName("parse() 테스트 - null 원본 텍스트")
    void testParseNullOriginalText() {
        // Given
        LogEvent logEvent = new LogEvent("", "localhost", "http");
        logEvent.setOriginalText(null);

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertFalse(result);
        assertTrue(logEvent.hasError());
        assertNotNull(logEvent.getProcessingError());
    }

    @Test
    @DisplayName("parse() 테스트 - 빈 메시지")
    void testParseEmptyMessage() {
        // Given
        LogEvent logEvent = new LogEvent("", "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result); // 빈 메시지도 파싱 성공으로 처리
        assertTrue(logEvent.hasFields());

        Map<String, Object> fields = logEvent.getFields();
        assertNotNull(fields.get("headers"));
        assertNotNull(fields.get("body"));

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) fields.get("headers");
        assertTrue(headers.isEmpty());

        String body = (String) fields.get("body");
        assertEquals("", body.trim());
    }

    @Test
    @DisplayName("parse() 테스트 - 헤더만 있고 본문이 없는 경우")
    void testParseHeadersOnlyNoBody() {
        // Given
        String httpMessage = "GET /test HTTP/1.1\r\n" +
                           "Host: localhost\r\n" +
                           "User-Agent: TestAgent\r\n" +
                           "\r\n";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) logEvent.getFields().get("headers");
        assertEquals("localhost", headers.get("HOST"));
        assertEquals("TestAgent", headers.get("USER-AGENT"));

        String body = (String) logEvent.getFields().get("body");
        assertEquals("", body.trim());
    }

    @Test
    @DisplayName("parse() 테스트 - 복잡한 실제 HTTP 요청")
    void testParseComplexRealHttpRequest() {
        // Given
        String httpMessage = "POST /api/v1/logs HTTP/1.1\r\n" +
                           "Host: log-server.example.com:443\r\n" +
                           "User-Agent: LogClient/1.0 (Linux; x64)\r\n" +
                           "Accept: application/json, text/plain, */*\r\n" +
                           "Accept-Language: en-US,en;q=0.9\r\n" +
                           "Accept-Encoding: gzip, deflate, br\r\n" +
                           "Content-Type: application/json; charset=utf-8\r\n" +
                           "Content-Length: 85\r\n" +
                           "Origin: https://app.example.com\r\n" +
                           "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\r\n" +
                           "\r\n" +
                           "{\"timestamp\":\"2024-01-01T12:00:00Z\",\"level\":\"ERROR\",\"message\":\"Test error\"}";

        LogEvent logEvent = new LogEvent(httpMessage, "localhost", "http");

        // When
        boolean result = parser.parse(logEvent);

        // Then
        assertTrue(result);
        assertTrue(logEvent.hasFields());

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) logEvent.getFields().get("headers");
        assertEquals("log-server.example.com:443", headers.get("HOST"));
        assertEquals("application/json; charset=utf-8", headers.get("CONTENT-TYPE"));
        assertEquals("85", headers.get("CONTENT-LENGTH"));
        assertEquals("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", headers.get("AUTHORIZATION"));

        String body = (String) logEvent.getFields().get("body");
        assertTrue(body.contains("\"timestamp\":\"2024-01-01T12:00:00Z\""));
        assertTrue(body.contains("\"level\":\"ERROR\""));
        assertTrue(body.contains("\"message\":\"Test error\""));
    }
}
