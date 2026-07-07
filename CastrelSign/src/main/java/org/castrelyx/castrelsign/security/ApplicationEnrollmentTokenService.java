package org.castrelyx.castrelsign.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository.ApplicationEnrollmentTokenRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationEnrollmentTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String TOKEN_PREFIX = "csa_";

  private final ApplicationPrincipalRepository repository;

  public ApplicationEnrollmentTokenService(ApplicationPrincipalRepository repository) {
    this.repository = repository;
  }

  public CreatedApplicationEnrollmentToken create(String name, String principalId, int ttlSeconds) {
    repository.principal(principalId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application principal was not found"));
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    if (ttlSeconds < 60) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ttl_seconds must be at least 60");
    }
    String token = newToken();
    ApplicationEnrollmentTokenRecord record = repository.createToken(
        name.trim(),
        hash(token),
        principalId,
        Instant.now().plusSeconds(ttlSeconds));
    return new CreatedApplicationEnrollmentToken(record, token);
  }

  public List<ApplicationEnrollmentTokenRecord> list() {
    return repository.listTokens();
  }

  public void revoke(long id) {
    repository.revokeToken(id);
  }

  public void consumeValid(String authorizationHeader, String principalId) {
    String token = bearerToken(authorizationHeader);
    ApplicationEnrollmentTokenRecord record = repository.tokenByHash(hash(token))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid application enrollment token"));
    if (!record.principalId().equals(principalId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "application enrollment token is not valid for this principal");
    }
    if (record.revokedAt() != null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "application enrollment token has been revoked");
    }
    if (record.usedAt() != null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "application enrollment token has already been used");
    }
    Instant now = Instant.now();
    if (!record.expiresAt().isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "application enrollment token has expired");
    }
    if (!repository.consumeToken(record.id(), now)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "application enrollment token is no longer usable");
    }
  }

  private static String bearerToken(String authorizationHeader) {
    String prefix = "Bearer ";
    if (authorizationHeader == null || !authorizationHeader.startsWith(prefix)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing application enrollment token");
    }
    String token = authorizationHeader.substring(prefix.length());
    if (token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing application enrollment token");
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
      throw new IllegalStateException("failed to hash application enrollment token", e);
    }
  }

  public record CreatedApplicationEnrollmentToken(ApplicationEnrollmentTokenRecord record, String token) {
  }
}
