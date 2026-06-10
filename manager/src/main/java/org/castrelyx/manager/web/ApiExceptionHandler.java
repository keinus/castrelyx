package org.castrelyx.manager.web;

import java.util.Map;
import org.castrelyx.manager.auth.AuthException;
import org.castrelyx.manager.auth.ForbiddenException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(AuthException.class)
  ResponseEntity<Map<String, String>> auth(AuthException exception) {
    return error(HttpStatus.UNAUTHORIZED, exception.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<Map<String, String>> forbidden(ForbiddenException exception) {
    return error(HttpStatus.FORBIDDEN, exception.getMessage());
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException exception) {
    return error(HttpStatus.CONFLICT, "duplicate resource");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
    return error(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
    return error(HttpStatus.CONFLICT, exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
    return error(HttpStatus.BAD_REQUEST, "invalid request");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
  }
}
