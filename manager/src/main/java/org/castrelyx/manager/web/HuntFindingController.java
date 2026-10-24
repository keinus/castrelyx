package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security/hunt/v2")
public class HuntFindingController {
  private final HuntFindingEvidence evidence;

  public HuntFindingController(HuntFindingEvidence evidence) {
    this.evidence = evidence;
  }

  @GetMapping("/findings/{id}")
  public Object detail(@PathVariable String id, HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(evidence.detail(id, user), user);
  }

  @GetMapping("/findings/{id}/occurrences")
  public Object occurrences(
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(evidence.occurrences(id, before, user), user);
  }

  @GetMapping("/findings/{id}/merges")
  public Object merges(
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(evidence.merges(id, before, user), user);
  }

  private static AuthUser user(HttpServletRequest request) {
    return (AuthUser) request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
  }
}
