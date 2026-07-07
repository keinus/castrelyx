package org.castrelyx.castrelvault.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception) {
    int status = exception.getStatusCode().value();
    String reason = exception.getReason() == null ? "request failed" : exception.getReason();
    return ResponseEntity.status(status).body(Map.of("error", reason));
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<Map<String, Object>> badRequest(Exception exception) {
    return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safeMessage(exception)));
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "request failed";
    }
    return message;
  }
}
