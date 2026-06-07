package org.castrelyx.castrelsign.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.castrelyx.castrelsign.persistence.EnrollmentTokenRepository;
import org.castrelyx.castrelsign.persistence.EnrollmentTokenRepository.TokenRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrollmentTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String TOKEN_PREFIX = "cse_";

  private final EnrollmentTokenRepository repository;

  public EnrollmentTokenService(EnrollmentTokenRepository repository) {
    this.repository = repository;
  }

  public CreatedEnrollmentToken create(String name, String agentId, int ttlSeconds, int maxUses) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    if (ttlSeconds < 60) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ttl_seconds must be at least 60");
    }
    if (maxUses < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max_uses must be at least 1");
    }
    String token = newToken();
    TokenRecord record = repository.create(
        name.trim(),
        hash(token),
        blankToNull(agentId),
        maxUses,
        Instant.now().plusSeconds(ttlSeconds));
    return new CreatedEnrollmentToken(record, token);
  }

  public List<TokenRecord> list() {
    return repository.list();
  }

  public void revoke(long id) {
    repository.revoke(id);
  }

  public void consumeValid(String authorizationHeader, String agentId) {
    String presented = bearerToken(authorizationHeader);
    String tokenHash = hash(presented);
    TokenRecord record = repository.findByHash(tokenHash)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid enrollment token"));
    if (!MessageDigest.isEqual(tokenHash.getBytes(StandardCharsets.UTF_8), record.tokenHash().getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid enrollment token");
    }
    if (record.revokedAt() != null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "enrollment token has been revoked");
    }
    Instant now = Instant.now();
    if (!record.expiresAt().isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "enrollment token has expired");
    }
    if (record.usedCount() >= record.maxUses()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "enrollment token usage limit reached");
    }
    if (record.agentId() != null && !record.agentId().equals(agentId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "enrollment token is not valid for this agent_id");
    }
    if (!repository.markUsed(record.id(), agentId, now)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "enrollment token is no longer usable");
    }
  }

  private static String bearerToken(String authorizationHeader) {
    String prefix = "Bearer ";
    if (authorizationHeader == null || !authorizationHeader.startsWith(prefix)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing enrollment token");
    }
    String token = authorizationHeader.substring(prefix.length());
    if (token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing enrollment token");
    }
    return token;
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("failed to hash enrollment token", e);
    }
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public record CreatedEnrollmentToken(TokenRecord record, String token) {
  }
}

