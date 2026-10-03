package org.castrelyx.castrelsign.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KmsAuthentication {
  public static final String IDENTITY = KmsAuthentication.class.getName();
  private final CastrelSignProperties properties;
  private final KeyManagementService kms;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, long[]> mutationAttempts = new HashMap<>();
  public record Administrator(String id, String tokenSha256, Set<String> roles, Set<String> keys, String totpSecretBase64) {}
  public record Identity(String id, Set<String> roles, Set<String> keys) {
    public void require(String role, String alias) {
      if (!roles.contains(role) || alias != null && !keys.contains(alias)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "administrator permission denied");
    }
  }
  public KmsAuthentication(CastrelSignProperties properties, KeyManagementService kms) {
    this.properties = properties; this.kms = kms;
    if (properties.getKms().getAdministratorsFile() == null && (properties.getAdminToken() == null || properties.getAdminToken().isBlank()))
      throw new IllegalStateException("administrator credentials are required");
    if (properties.getKms().production()) administrators();
  }
  public Identity authenticate(HttpServletRequest request) {
    if (request.getAttribute(IDENTITY) instanceof Identity identity) return identity;
    boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
    Identity identity = authenticate(request.getHeader("Authorization"), request.getHeader("X-KMS-TOTP"), mutation);
    request.setAttribute(IDENTITY, identity); return identity;
  }
  public Identity authenticate(String authorization, String totp, boolean mutation) {
    if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 4096) throw unauthorized();
    return authenticateDigest(tokenDigest(authorization.substring(7)), totp, mutation);
  }
  public static String tokenDigest(String token) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  /** Session credentials store only this digest; policy is reloaded for every request. */
  public Identity authenticateDigest(String hash, String totp, boolean mutation) {
    try {
      if (properties.getKms().getAdministratorsFile() == null) {
        if (properties.getKms().production() || properties.getAdminToken() == null
            || !MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), tokenDigest(properties.getAdminToken()).getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
        return new Identity("legacy-admin", Set.of("OPERATOR", "KEY_ADMIN", "AUDITOR", "VERIFIER"), Set.of(KeyManagementService.CA, KeyManagementService.UPDATE));
      }
      Administrator matched = null;
      for (Administrator administrator : administrators()) if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), administrator.tokenSha256().getBytes(StandardCharsets.US_ASCII))) matched = administrator;
      if (matched == null) throw unauthorized();
      if (mutation) {
        limitMfaAttempts(matched.id());
        if (matched.totpSecretBase64() == null || matched.totpSecretBase64().isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MFA enrollment required");
        long step = verifyTotp(Base64.getDecoder().decode(matched.totpSecretBase64()), totp, Instant.now().getEpochSecond());
        kms.useMfaStep(matched.id(), step);
      }
      return new Identity(matched.id(), matched.roles(), matched.keys());
    } catch (ResponseStatusException error) { throw error; }
    catch (Exception error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "administrator authentication unavailable", error); }
  }
  private synchronized void limitMfaAttempts(String actor) {
    long minute = Instant.now().getEpochSecond() / 60;
    long[] window = mutationAttempts.computeIfAbsent(actor, ignored -> new long[]{minute, 0});
    if (window[0] != minute) { window[0] = minute; window[1] = 0; }
    if (++window[1] > 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "administrator MFA attempt quota exceeded");
  }
  private List<Administrator> administrators() {
    try {
      var path = properties.getKms().getAdministratorsFile();
      if (Files.size(path) > 1024 * 1024 || Files.isSymbolicLink(path)) throw new IllegalStateException("invalid administrator file");
      List<Administrator> admins = Arrays.asList(mapper.readValue(Files.readAllBytes(path), Administrator[].class));
      Set<String> ids = new HashSet<>(), hashes = new HashSet<>();
      for (var a : admins) {
        if (a.id() == null || !a.id().matches("[A-Za-z0-9._@-]{1,100}") || !ids.add(a.id())
            || a.tokenSha256() == null || !a.tokenSha256().matches("[a-f0-9]{64}") || !hashes.add(a.tokenSha256())
            || a.roles() == null || a.keys() == null || !Set.of("KEY_ADMIN", "SECURITY_OFFICER", "AUDITOR", "OPERATOR", "VERIFIER").containsAll(a.roles())
            || !Set.of(KeyManagementService.CA, KeyManagementService.UPDATE).containsAll(a.keys())) throw new IllegalStateException("invalid administrator policy");
        if (properties.getKms().production() && (a.roles().contains("KEY_ADMIN") || a.roles().contains("SECURITY_OFFICER") || a.roles().contains("OPERATOR"))) {
          if (a.totpSecretBase64() == null || Base64.getDecoder().decode(a.totpSecretBase64()).length < 20) throw new IllegalStateException("production administrators require MFA");
        }
      }
      if (properties.getKms().production() && admins.stream().noneMatch(a -> a.roles().contains("KEY_ADMIN")
          && admins.stream().anyMatch(b -> !b.id().equals(a.id()) && b.roles().contains("SECURITY_OFFICER")))) throw new IllegalStateException("two distinct key/security administrators required");
      return admins;
    } catch (Exception error) { throw new IllegalStateException("cannot load administrator policy", error); }
  }
  public List<Map<String, Object>> publicAdministrators() {
    if (properties.getKms().getAdministratorsFile() == null)
      return List.of(Map.of("id", "legacy-admin", "roles", Set.of("OPERATOR", "KEY_ADMIN", "AUDITOR", "VERIFIER"), "keys", Set.of(KeyManagementService.CA, KeyManagementService.UPDATE), "mfa", false));
    return administrators().stream().map(a -> Map.<String,Object>of("id", a.id(), "roles", a.roles(), "keys", a.keys(), "mfa", a.totpSecretBase64() != null && !a.totpSecretBase64().isBlank())).toList();
  }
  public void requireCurrentRequester(String actor, String alias) {
    boolean allowed = properties.getKms().getAdministratorsFile() != null && administrators().stream()
        .anyMatch(a -> a.id().equals(actor) && a.roles().contains("KEY_ADMIN") && a.keys().contains(alias));
    if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "requester no longer has key administration permission");
  }
  static long verifyTotp(byte[] secret, String code, long seconds) throws Exception {
    if (secret.length < 20 || code == null || !code.matches("[0-9]{6}")) throw unauthorized();
    for (long step = seconds / 30 - 1; step <= seconds / 30 + 1; step++) {
      if (MessageDigest.isEqual(totp(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) return step;
    }
    throw unauthorized();
  }
  static String totp(byte[] secret, long step) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret, "HmacSHA1"));
    byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array()); int offset = digest[digest.length - 1] & 15;
    int binary = ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff;
    return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
  }
  private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid administrator credentials or MFA"); }
}
