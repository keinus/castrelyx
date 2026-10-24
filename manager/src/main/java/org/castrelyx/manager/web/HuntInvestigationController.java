package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security/hunt/v2/investigations")
public class HuntInvestigationController {
  private final HuntInvestigations cases;
  private final HuntCaseAccess access;

  public HuntInvestigationController(HuntInvestigations cases, HuntCaseAccess access) {
    this.cases = cases;
    this.access = access;
  }

  @GetMapping("/analysts")
  public HuntCaseAccess.AnalystPage analysts(
      @RequestParam(required = false) String q, HttpServletRequest request) {
    return access.analysts(q, user(request));
  }

  @GetMapping("/{id}/workflow")
  public HuntInvestigations.Workflow workflow(@PathVariable String id, HttpServletRequest request) {
    return cases.workflow(id, user(request));
  }

  @PostMapping("/{id}/assignment")
  public HuntWorkspace.Resource assign(
      @PathVariable String id,
      @RequestBody HuntInvestigations.Assign input,
      HttpServletRequest request) {
    return cases.assign(id, input, user(request));
  }

  @PostMapping("/{id}/review")
  public HuntWorkspace.Resource submit(
      @PathVariable String id,
      @RequestBody HuntInvestigations.Submit input,
      HttpServletRequest request) {
    return cases.submit(id, input, user(request));
  }

  @PostMapping("/{id}/decision")
  public HuntWorkspace.Resource decide(
      @PathVariable String id,
      @RequestBody HuntInvestigations.Decide input,
      HttpServletRequest request) {
    return cases.decide(id, input, user(request));
  }

  @PostMapping("/{id}/reopen")
  public HuntWorkspace.Resource reopen(
      @PathVariable String id,
      @RequestBody HuntInvestigations.Submit input,
      HttpServletRequest request) {
    return cases.reopen(id, input, user(request));
  }

  @GetMapping("/{id}/reviews")
  public HuntInvestigations.ReviewPage reviews(
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      HttpServletRequest request) {
    return cases.reviews(id, before, user(request));
  }

  @GetMapping("/{id}/reviews/{reviewId}")
  public Object detail(
      @PathVariable String id, @PathVariable long reviewId, HttpServletRequest request) {
    return HuntRedaction.apply(cases.reviewDetail(id, reviewId, user(request)), user(request));
  }

  private static AuthUser user(HttpServletRequest request) {
    return (AuthUser) request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
  }
}
