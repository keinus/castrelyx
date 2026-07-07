package org.castrelyx.castrelvault.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SecurityAttemptService {
  private static final int MAX_FAILURES = 5;
  private static final Duration WINDOW = Duration.ofMinutes(5);

  private final Map<String, AttemptBucket> buckets = new ConcurrentHashMap<>();

  public void requireAllowed(String key) {
    AttemptBucket bucket = buckets.get(key);
    if (bucket == null) {
      return;
    }
    Instant now = Instant.now();
    if (bucket.expiresAt().isBefore(now)) {
      buckets.remove(key);
      return;
    }
    if (bucket.failures() >= MAX_FAILURES) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many failed attempts");
    }
  }

  public void recordFailure(String key) {
    Instant now = Instant.now();
    buckets.compute(key, (ignored, current) -> {
      if (current == null || current.expiresAt().isBefore(now)) {
        return new AttemptBucket(1, now.plus(WINDOW));
      }
      return new AttemptBucket(current.failures() + 1, current.expiresAt());
    });
  }

  public void recordSuccess(String key) {
    buckets.remove(key);
  }

  private record AttemptBucket(int failures, Instant expiresAt) {
  }
}
