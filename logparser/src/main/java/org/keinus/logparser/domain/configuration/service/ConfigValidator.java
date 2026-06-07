package org.keinus.logparser.domain.configuration.service;

import org.keinus.logparser.domain.configuration.model.ConfigSchema.*;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 설정 검증을 위한 유틸리티 클래스입니다.
 * 어노테이션 기반으로 설정 필드를 검증합니다.
 */
@Component
public class ConfigValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValidator.class);

    /**
     * 설정 객체를 검증합니다.
     */
    public <T> ValidationResult validate(T config) {
        ValidationResult result = new ValidationResult();
        Class<?> clazz = config.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(config);
                validateField(field, value, result);
            } catch (IllegalAccessException e) {
                result.addError("Failed to access field: " + field.getName());
            }
        }

        return result;
    }

    private void validateField(Field field, Object value, ValidationResult result) {
        String fieldName = field.getName();

        // Required 검증
        if (field.isAnnotationPresent(Required.class) && (value == null || (value instanceof String str && str.trim().isEmpty()))) {
                Required req = field.getAnnotation(Required.class);
                result.addError(fieldName + ": " + req.message());
                return;
            }
        

        // null 값이면 다른 검증 스킵
        if (value == null) {
            return;
        }

        // Range 검증
        if (field.isAnnotationPresent(Range.class) && value instanceof Number number) {
            Range range = field.getAnnotation(Range.class);
            int intValue = number.intValue();
            if (intValue < range.min() || intValue > range.max()) {
                result.addError(fieldName + ": value must be between " + range.min() + " and " + range.max());
            }
        }

        // Choice 검증
        if (field.isAnnotationPresent(Choice.class) && value instanceof String) {
            Choice choice = field.getAnnotation(Choice.class);
            List<String> validValues = Arrays.asList(choice.values());
            if (!validValues.contains(value)) {
                result.addError(fieldName + ": invalid choice. Valid values: " + validValues);
            }
        }

        // URL 검증
        if (field.isAnnotationPresent(Url.class) && value instanceof String str) {
            try {
                URI uri = new URI(str);
                if(uri.getScheme() == null || uri.getHost() == null)
                    throw new IllegalArgumentException("Invalid URI: missing scheme or host");
            } catch (Exception e) {
                result.addError(fieldName + ": invalid URL format");
            }
        }

        // FilePath 검증
        if (field.isAnnotationPresent(FilePath.class) && value instanceof String str) {
            FilePath filePath = field.getAnnotation(FilePath.class);
            if (filePath.mustExist() && !Files.exists(Paths.get(str))) {
                result.addError(fieldName + ": file does not exist: " + value);
            }
        }
    }

    /**
     * 어댑터별 설정 검증
     */
    public ValidationResult validateInputAdapter(InputAdapterConfig config) {
        ValidationResult result = validate(config);
        config.validate(); // 커스텀 검증 로직
        return result;
    }

    public ValidationResult validateOutputAdapter(OutputAdapterConfig config) {
        ValidationResult result = validate(config);
        config.validate(); // 커스텀 검증 로직
        return result;
    }

    public ValidationResult validateParserAdapter(ParserAdapterConfig config) {
        ValidationResult result = validate(config);
        config.validate(); // 커스텀 검증 로직
        return result;
    }

    /**
     * 검증 결과를 담는 클래스
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }

        public List<String> getWarnings() {
            return new ArrayList<>(warnings);
        }

        public void logResults() {
            if (hasErrors()) {
                LOGGER.error("Configuration validation failed:");
                errors.forEach(error -> LOGGER.error("  - {}", error));
            }

            if (hasWarnings()) {
                LOGGER.warn("Configuration warnings:");
                warnings.forEach(warning -> LOGGER.warn("  - {}", warning));
            }

            if (!hasErrors() && !hasWarnings()) {
                LOGGER.info("Configuration validation passed");
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{");
            sb.append("errors=").append(errors.size());
            sb.append(", warnings=").append(warnings.size());
            sb.append("}");
            return sb.toString();
        }
    }
}