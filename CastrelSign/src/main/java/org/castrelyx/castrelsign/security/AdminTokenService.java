package org.castrelyx.castrelsign.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminTokenService {
  private final CastrelSignProperties properties;

  public AdminTokenService(CastrelSignProperties properties) {
    this.properties = properties;
  }

  public void requireValid(String authorizationHeader) {
    String prefix = "Bearer ";
    if (authorizationHeader == null || !authorizationHeader.startsWith(prefix)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing admin token");
    }
    byte[] presented = authorizationHeader.substring(prefix.length()).getBytes(StandardCharsets.UTF_8);
    byte[] expected = properties.getAdminToken().getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(presented, expected)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid admin token");
    }
  }
}

