package org.keinus.logparser.interfaces.exception;

import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.interfaces.dto.response.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ==================== Custom Exceptions ====================

    @ExceptionHandler(ConfigNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConfigNotFoundException(
            ConfigNotFoundException ex, WebRequest request) {
        log.error("Configuration not found: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                ErrorCode.CONFIG_NOT_FOUND.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(ConfigValidationException.class)
    public ResponseEntity<ErrorResponse> handleConfigValidationException(
            ConfigValidationException ex, WebRequest request) {
        log.error("Configuration validation failed: {}", ex.getMessage());

        String message = ex.getMessage() + ". Errors: " + String.join(", ", ex.getValidationErrors());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.CONFIG_VALIDATION_FAILED.getFullCode(),
                message,
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(DuplicateConfigException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateConfigException(
            DuplicateConfigException ex, WebRequest request) {
        log.error("Duplicate configuration: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCode.DUPLICATE_CONFIG.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(ConfigConflictException.class)
    public ResponseEntity<ErrorResponse> handleConfigConflictException(
            ConfigConflictException ex, WebRequest request) {
        log.error("Configuration conflict: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCode.CONFIG_CONFLICT.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(PipelineIntegrityException.class)
    public ResponseEntity<ErrorResponse> handlePipelineIntegrityException(
            PipelineIntegrityException ex, WebRequest request) {
        log.error("Pipeline integrity violation: {}", ex.getMessage());

        String message = ex.getMessage() + ". Errors: " + String.join(", ", ex.getIntegrityErrors());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.PIPELINE_INTEGRITY_VIOLATION.getFullCode(),
                message,
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(PipelineOperationException.class)
    public ResponseEntity<ErrorResponse> handlePipelineOperationException(
            PipelineOperationException ex, WebRequest request) {
        log.error("Pipeline operation failed: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.PIPELINE_OPERATION_FAILED.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleVersionNotFoundException(
            VersionNotFoundException ex, WebRequest request) {
        log.error("Version not found: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                ErrorCode.VERSION_NOT_FOUND.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(InvalidAdapterTypeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAdapterTypeException(
            InvalidAdapterTypeException ex, WebRequest request) {
        log.error("Invalid adapter type: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.INVALID_ADAPTER_TYPE.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ConfigImportException.class)
    public ResponseEntity<ErrorResponse> handleConfigImportException(
            ConfigImportException ex, WebRequest request) {
        log.error("Configuration import failed: {}", ex.getMessage(), ex);

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.IMPORT_FAILED.getFullCode(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ==================== Spring Framework Exceptions ====================

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockException(
            OptimisticLockingFailureException ex, WebRequest request) {
        log.error("Optimistic locking failure: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCode.OPTIMISTIC_LOCK_FAILED.getFullCode(),
                "The resource was modified by another user. Please refresh and try again.",
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, WebRequest request) {
        log.error("Data integrity violation: {}", ex.getMessage());

        String message = "Data integrity violation. ";
        if (ex.getMessage().contains("constraint")) {
            message += "A database constraint was violated.";
        } else {
            message += "Invalid data operation.";
        }

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.DATA_INTEGRITY_VIOLATION.getFullCode(),
                message,
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, WebRequest request) {
        log.error("Validation error: {}", ex.getMessage());

        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    error.getField(),
                    error.getRejectedValue() != null ? error.getRejectedValue().toString() : "null",
                    error.getDefaultMessage()
            ));
        }

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.INVALID_INPUT.getFullCode(),
                "Validation failed for one or more fields",
                request.getDescription(false).replace("uri=", ""),
                fieldErrors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        log.error("HTTP message not readable: {}", ex.getMessage());

        String message = "Invalid request body. Please check the JSON format.";
        if (ex.getMessage().contains("JSON parse error")) {
            message = "JSON parsing error: " + ex.getMessage();
        }

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.INVALID_FORMAT.getFullCode(),
                message,
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ==================== Generic Exception ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, WebRequest request) {
        log.error("Unexpected error occurred", ex);

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_SERVER_ERROR.getFullCode(),
                "An unexpected error occurred. Please contact support.",
                request.getDescription(false).replace("uri=", "")
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
