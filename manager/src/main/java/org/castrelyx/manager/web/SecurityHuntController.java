package org.castrelyx.manager.web;

import java.util.Map;
import java.util.Set;
import org.castrelyx.manager.hunt.SecurityHuntService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SecurityHuntController {
  private final SecurityHuntService hunt;
  public SecurityHuntController(SecurityHuntService hunt) { this.hunt = hunt; }

  @GetMapping("/api/security/hunt")
  public Map<String, Object> query(@RequestParam(defaultValue = "1h") String range,
      @RequestParam(defaultValue = "") String assetUid, @RequestParam(defaultValue = "ALL") String family,
      @RequestParam(defaultValue = "ALL") String severity, @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "ALL") String category, @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    if (!Set.of("15m", "30m", "1h", "6h", "24h").contains(range)
        || !Set.of("ALL", "host", "zeek", "suricata", "security", "audit").contains(family)
        || !Set.of("ALL", "INFO", "WARNING", "ERROR", "CRITICAL").contains(severity)
        || !Set.of("ALL", "THREAT", "OPERATIONAL", "REVIEW", "OBSERVATION", "UNKNOWN").contains(category)
        || query.length() > 256 || assetUid.length() > 256 || offset < 0 || offset > 10000 || limit < 1 || limit > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hunt filter");
    try { return hunt.query(range, assetUid, family, severity, query, category, offset, limit); }
    catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Hunt log storage or analysis unavailable", e); }
  }
}
