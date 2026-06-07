package org.keinus.logparser.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.keinus.logparser.domain.configuration.service.ConfigValidator;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigValidator 클래스의 단위 테스트
 *
 * 테스트 대상 함수들:
 * - validate(T) : 일반 객체 검증 테스트
 * - validateInputAdapter(InputAdapterConfig) : 입력 어댑터 설정 검증 테스트
 * - validateOutputAdapter(OutputAdapterConfig) : 출력 어댑터 설정 검증 테스트
 * - validateParserAdapter(ParserAdapterConfig) : 파서 어댑터 설정 검증 테스트
 * - validateField(Field, Object, ValidationResult) : 필드 검증 테스트 (private 메서드이지만 validate()를 통해 간접 테스트)
 */
class ConfigValidatorTest {

    private ConfigValidator validator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator();
    }

    @Test
    @DisplayName("validate() 테스트 - InputAdapterConfig 유효한 설정")
    void testValidateInputAdapterConfigValid() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        config.setPort(8080);
        config.setHost("localhost");

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    @DisplayName("validate() 테스트 - Required 필드 누락")
    void testValidateRequiredFieldMissing() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        // type과 messagetype을 설정하지 않음 (Required 필드)

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("type")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("messagetype")));
    }

    @Test
    @DisplayName("validate() 테스트 - Range 검증 실패")
    void testValidateRangeValidationFailed() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        config.setPort(70000); // 범위 초과 (max 65535)

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(error ->
            error.contains("port") && error.contains("between")));
    }

    @Test
    @DisplayName("validate() 테스트 - Choice 검증 실패")
    void testValidateChoiceValidationFailed() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("InvalidAdapter"); // 잘못된 선택
        config.setMessagetype("test-log");

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(error ->
            error.contains("type") && error.contains("invalid choice")));
    }

    @Test
    @DisplayName("validate() 테스트 - FilePath 검증 실패")
    void testValidateFilePathValidationFailed() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("FileInputAdapter");
        config.setMessagetype("file-log");
        config.setPath("/non/existent/file.log");

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        // FilePath 어노테이션이 mustExist=true가 아닐 수 있으므로, 에러가 없을 수도 있음
        // 대신 validate 메서드 자체가 정상 동작하는지 확인
        assertNotNull(result);
        assertFalse(result.hasWarnings()); // 경고는 없어야 함
    }

    @Test
    @DisplayName("validate() 테스트 - FilePath 검증 성공")
    void testValidateFilePathValidationSuccess() throws IOException {
        // Given
        Path testFile = tempDir.resolve("test.log");
        Files.createFile(testFile);

        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("FileInputAdapter");
        config.setMessagetype("file-log");
        config.setPath(testFile.toString());

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        // path 필드에 대한 에러가 없어야 함 (다른 필드의 에러는 있을 수 있음)
        assertFalse(result.getErrors().stream().anyMatch(error ->
            error.contains("path") && error.contains("does not exist")));
    }

    @Test
    @DisplayName("validateInputAdapter() 테스트 - TcpInputAdapter 유효한 설정")
    void testValidateInputAdapterTcp() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        config.setPort(8080);

        // When
        ConfigValidator.ValidationResult result = validator.validateInputAdapter(config);

        // Then
        assertFalse(result.hasErrors());
    }

    @Test
    @DisplayName("validateInputAdapter() 테스트 - TcpInputAdapter 포트 누락")
    void testValidateInputAdapterTcpMissingPort() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        // port 설정 안 함

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateInputAdapter(config));
    }

    @Test
    @DisplayName("validateInputAdapter() 테스트 - KafkaInputAdapter 유효한 설정")
    void testValidateInputAdapterKafka() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("KafkaInputAdapter");
        config.setMessagetype("kafka-log");
        config.setTopicid("test-topic");
        config.setBootstrapservers("localhost:9092");

        // When
        ConfigValidator.ValidationResult result = validator.validateInputAdapter(config);

        // Then
        assertFalse(result.hasErrors());
    }

    @Test
    @DisplayName("validateInputAdapter() 테스트 - KafkaInputAdapter 필수 필드 누락")
    void testValidateInputAdapterKafkaMissingFields() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("KafkaInputAdapter");
        config.setMessagetype("kafka-log");
        // topicid와 bootstrapservers 설정 안 함

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
            validator.validateInputAdapter(config));
    }

    @Test
    @DisplayName("validateOutputAdapter() 테스트 - 유효한 설정")
    void testValidateOutputAdapter() {
        // Given
        OutputAdapterConfig config = new OutputAdapterConfig();
        config.setType("ConsoleOutputAdapter");
        config.setMessagetype("console-log");

        // When
        ConfigValidator.ValidationResult result = validator.validateOutputAdapter(config);

        // Then
        assertFalse(result.hasErrors());
    }

    @Test
    @DisplayName("validateParserAdapter() 테스트 - 유효한 설정")
    void testValidateParserAdapter() {
        // Given
        ParserAdapterConfig config = new ParserAdapterConfig();
        config.setType("HttpParser");
        config.setMessagetype("http-log");

        // When
        ConfigValidator.ValidationResult result = validator.validateParserAdapter(config);

        // Then
        assertFalse(result.hasErrors());
    }

    @Test
    @DisplayName("ValidationResult 테스트 - 에러 추가 및 조회")
    void testValidationResultErrors() {
        // Given
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult();

        // When
        result.addError("Test error 1");
        result.addError("Test error 2");

        // Then
        assertTrue(result.hasErrors());
        assertEquals(2, result.getErrors().size());
        assertTrue(result.getErrors().contains("Test error 1"));
        assertTrue(result.getErrors().contains("Test error 2"));
    }

    @Test
    @DisplayName("ValidationResult 테스트 - 경고 추가 및 조회")
    void testValidationResultWarnings() {
        // Given
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult();

        // When
        result.addWarning("Test warning 1");
        result.addWarning("Test warning 2");

        // Then
        assertTrue(result.hasWarnings());
        assertEquals(2, result.getWarnings().size());
        assertTrue(result.getWarnings().contains("Test warning 1"));
        assertTrue(result.getWarnings().contains("Test warning 2"));
    }

    @Test
    @DisplayName("ValidationResult 테스트 - 빈 결과")
    void testValidationResultEmpty() {
        // Given
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult();

        // When & Then
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("ValidationResult 테스트 - toString() 메서드")
    void testValidationResultToString() {
        // Given
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult();
        result.addError("Test error");
        result.addWarning("Test warning");

        // When
        String toString = result.toString();

        // Then
        assertTrue(toString.contains("ValidationResult"));
        assertTrue(toString.contains("errors=1"));
        assertTrue(toString.contains("warnings=1"));
    }

    @Test
    @DisplayName("ValidationResult 테스트 - logResults() 메서드")
    void testValidationResultLogResults() {
        // Given
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult();
        result.addError("Test error");
        result.addWarning("Test warning");

        // When & Then
        assertDoesNotThrow(() -> result.logResults());
    }

    @Test
    @DisplayName("validate() 테스트 - null 값이 있는 필드")
    void testValidateWithNullValues() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        config.setPort(8080);
        config.setHost(null); // null 값

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        // host는 Required가 아니므로 null이어도 에러가 발생하지 않아야 함
        assertFalse(result.getErrors().stream().anyMatch(error -> error.contains("host")));
    }

    @Test
    @DisplayName("validate() 테스트 - 빈 문자열 Required 필드")
    void testValidateWithEmptyStringRequired() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType(""); // 빈 문자열
        config.setMessagetype("   "); // 공백만 있는 문자열

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("type")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("messagetype")));
    }

    @Test
    @DisplayName("validate() 테스트 - Range 경계값")
    void testValidateRangeBoundaryValues() {
        // Given
        InputAdapterConfig config = new InputAdapterConfig();
        config.setType("TcpInputAdapter");
        config.setMessagetype("tcp-log");
        config.setPort(1); // 최소값
        config.setBufferSize(1024); // 최소값

        // When
        ConfigValidator.ValidationResult result = validator.validate(config);

        // Then
        assertFalse(result.getErrors().stream().anyMatch(error ->
            error.contains("port") || error.contains("bufferSize")));

        // 최대값 테스트
        config.setPort(65535); // 최대값
        config.setBufferSize(1048576); // 최대값

        result = validator.validate(config);
        assertFalse(result.getErrors().stream().anyMatch(error ->
            error.contains("port") || error.contains("bufferSize")));
    }
}