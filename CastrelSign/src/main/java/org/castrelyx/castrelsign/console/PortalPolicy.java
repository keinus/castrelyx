package org.castrelyx.castrelsign.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalPolicy {
  public record User(String id, String tokenSha256, Set<String> services) {}
  private final CastrelSignProperties properties;
  private final ObjectMapper mapper;
  public PortalPolicy(CastrelSignProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }
  private List<User> users() {
    var path = properties.getPortalUsersFile();
    if (path == null) return List.of();
    try {
      if (Files.isSymbolicLink(path) || Files.size(path) > 1048576
          || properties.getKms().production() && path.toAbsolutePath().normalize().startsWith(properties.getDataDir().toAbsolutePath().normalize()))
        throw new IllegalStateException("invalid policy location");
      var users = Arrays.asList(mapper.readValue(Files.readAllBytes(path), User[].class));
      Set<String> ids = new HashSet<>(), hashes = new HashSet<>();
      for (var user : users) {
        if (user.id() == null || !user.id().matches("[A-Za-z0-9._@-]{1,100}") || !ids.add(user.id())
            || user.tokenSha256() == null || !user.tokenSha256().matches("[a-f0-9]{64}") || !hashes.add(user.tokenSha256())
            || user.services() == null || user.services().stream().anyMatch(s -> !s.matches("[A-Za-z0-9._:-]{1,128}")))
          throw new IllegalStateException("invalid portal policy");
      }
      return users;
    } catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "portal policy unavailable"); }
  }
  public User authenticate(String digest) {
    return users().stream().filter(u -> MessageDigest.isEqual(u.tokenSha256().getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII)))
        .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid portal credentials"));
  }
  public void requireOwner(String id, String service) {
    if (users().stream().noneMatch(u -> u.id().equals(id) && u.services().contains(service)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "service access revoked");
  }
}
