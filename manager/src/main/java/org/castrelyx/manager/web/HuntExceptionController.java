package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.castrelyx.manager.auth.CurrentUser;
import org.castrelyx.manager.hunt.v2.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security/hunt/v2")
public class HuntExceptionController {
  private final HuntExceptions exceptions;
  private final HuntJobs jobs;

  public HuntExceptionController(HuntExceptions exceptions, HuntJobs jobs) {
    this.exceptions = exceptions;
    this.jobs = jobs;
  }

  @GetMapping("/exceptions")
  public Object list(
      @RequestParam(required = false) Instant beforeAt,
      @RequestParam(required = false) String beforeId,
      HttpServletRequest request) {
    if ((beforeAt == null) != (beforeId == null))
      throw new IllegalArgumentException("예외 목록 커서의 시각과 ID를 함께 지정하세요.");
    var user = user(request);
    var page =
        exceptions.list(
            beforeAt == null ? null : new HuntExceptions.Cursor(beforeAt, beforeId), user);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("items", HuntRedaction.apply(page.items(), user));
    result.put("hasMore", page.hasMore());
    result.put("nextCursor", page.nextCursor());
    return result;
  }

  @PostMapping("/exceptions")
  @ResponseStatus(HttpStatus.CREATED)
  public Object create(@RequestBody HuntExceptions.Request input, HttpServletRequest request) {
    var user = user(request);
    var job = jobs.get(input.jobId(), user);
    return HuntRedaction.apply(
        exceptions.request(
            input, job, jobs.spec(job.id(), user), jobs.aliases(job.id(), user), user),
        user);
  }

  @GetMapping("/exceptions/{id}")
  public Object detail(@PathVariable String id, HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(exceptions.detail(id, user), user);
  }

  @GetMapping("/exceptions/{id}/usage")
  public Object usage(
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(exceptions.usage(id, before, user), user);
  }

  @PostMapping("/exceptions/{id}/decision")
  public Object decision(
      @PathVariable String id,
      @RequestBody HuntExceptions.Decision input,
      HttpServletRequest request) {
    var user = user(request);
    return HuntRedaction.apply(exceptions.decide(id, input, user), user);
  }

  private static org.castrelyx.manager.auth.AuthUser user(HttpServletRequest request) {
    return (org.castrelyx.manager.auth.AuthUser)
        request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
  }
}
