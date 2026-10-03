package org.castrelyx.castrelsign.console;

import jakarta.servlet.http.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.kms.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {
  private final KmsAuthentication authentication;
  private final PortalPolicy policy;
  private final CastrelSignProperties properties;
  private final Map<String, long[]> loginAttempts = new HashMap<>();
  public ConsoleController(KmsAuthentication authentication, PortalPolicy policy, CastrelSignProperties properties) { this.authentication = authentication; this.policy = policy; this.properties = properties; }
  public record Login(String realm, String token) {}
  @PostMapping(value = "/login", consumes = "application/json")
  public Map<String, Object> login(HttpServletRequest request, HttpServletResponse response, @RequestBody Login login) {
    throttle(request.getRemoteAddr());
    if (login.realm() == null || login.token() == null || login.token().length() < 1 || login.token().length() > 4096 || !Set.of("admin", "user").contains(login.realm())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    String digest = KmsAuthentication.tokenDigest(login.token());
    if (login.realm().equals("admin")) request.setAttribute(KmsAuthentication.IDENTITY, authentication.authenticateDigest(digest, null, false));
    else request.setAttribute(ConsoleSecurityFilter.USER, policy.authenticate(digest));
    var previous = request.getSession(false); if (previous != null) previous.invalidate();
    var http = request.getSession(true); http.setMaxInactiveInterval(900);
    byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
    var session = new ConsoleSecurityFilter.Session(login.realm(), digest, Base64.getUrlEncoder().withoutPadding().encodeToString(random), Instant.now());
    http.setAttribute(ConsoleSecurityFilter.SESSION, session);
    // Explicit flags also cover containers and deployments with custom defaults.
    response.addHeader("Set-Cookie", "CASTRELSIGN_SESSION=" + http.getId() + "; Path=" + (request.getContextPath().isEmpty() ? "/" : request.getContextPath()) + "; HttpOnly; SameSite=Strict" + (request.isSecure() ? "; Secure" : ""));
    return session(request);
  }
  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    var session = (ConsoleSecurityFilter.Session) request.getSession(false).getAttribute(ConsoleSecurityFilter.SESSION);
    Map<String, Object> result = new LinkedHashMap<>(); result.put("realm", session.realm()); result.put("csrf", session.csrf());
    result.put("environment", properties.getKms().getEnvironment()); result.put("tenant", properties.getKms().getTenant());
    if (session.realm().equals("admin")) {
      var identity = (KmsAuthentication.Identity) request.getAttribute(KmsAuthentication.IDENTITY);
      result.put("id", identity.id()); result.put("roles", identity.roles()); result.put("keys", identity.keys());
      result.put("mfaRequired", properties.getKms().getAdministratorsFile() != null);
    } else {
      var user = (PortalPolicy.User) request.getAttribute(ConsoleSecurityFilter.USER);
      result.put("id", user.id()); result.put("roles", Set.of("SERVICE_USER")); result.put("services", user.services()); result.put("mfaRequired", false);
    }
    return result;
  }
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    request.getSession(false).invalidate();
    response.addHeader("Set-Cookie", "CASTRELSIGN_SESSION=; Path=" + (request.getContextPath().isEmpty() ? "/" : request.getContextPath()) + "; Max-Age=0; HttpOnly; SameSite=Strict" + (request.isSecure() ? "; Secure" : ""));
  }
  @GetMapping("/configuration")
  public Map<String, Object> configuration(HttpServletRequest request) {
    authentication.authenticate(request).require("AUDITOR", null);
    var k = properties.getKms();
    return Map.ofEntries(Map.entry("mode", k.getMode()), Map.entry("environment", k.getEnvironment()), Map.entry("tenant", k.getTenant()),
        Map.entry("signingLifetimeDays", k.getSigningLifetimeDays()), Map.entry("certValidDays", properties.getCertValidDays()), Map.entry("deletionGraceDays", k.getDeletionGraceDays()),
        Map.entry("signsPerMinute", k.getSignsPerMinute()), Map.entry("pkcs11Configured", k.getPkcs11Config() != null), Map.entry("externalMasterKey", k.getMasterKeyFile() != null),
        Map.entry("administrators", authentication.publicAdministrators()), Map.entry("portalConfigured", properties.getPortalUsersFile() != null));
  }
  private synchronized void throttle(String remote) {
    long minute = Instant.now().getEpochSecond() / 60;
    loginAttempts.entrySet().removeIf(e -> e.getValue()[0] < minute);
    if (loginAttempts.size() > 10000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    var count = loginAttempts.computeIfAbsent(remote, ignored -> new long[]{minute, 0});
    if (++count[1] > 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "login quota exceeded");
  }
}
