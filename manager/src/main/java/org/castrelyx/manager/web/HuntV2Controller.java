package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security/hunt/v2")
public class HuntV2Controller {
  private final HuntJobs jobs;
  private final HuntWorkspace workspace;
  private final HuntScenarios scenarios;
  private final HuntAccess access;
  private final HuntStorage storage;
  private final HuntRules rules;
  private final HuntExports exports;

  public HuntV2Controller(
      HuntJobs jobs,
      HuntWorkspace workspace,
      HuntScenarios scenarios,
      HuntAccess access,
      HuntStorage storage,
      HuntRules rules,
      HuntExports exports) {
    this.jobs = jobs;
    this.workspace = workspace;
    this.scenarios = scenarios;
    this.access = access;
    this.storage = storage;
    this.rules = rules;
    this.exports = exports;
  }

  public record RuleTransition(String state, long version) {}

  @PostMapping("/rules/{id}/transition")
  public HuntWorkspace.Resource transition(
      @PathVariable String id, @RequestBody RuleTransition input, HttpServletRequest request) {
    return rules.transition(id, input.state(), input.version(), user(request));
  }

  @GetMapping("/rules/runtime")
  public Object runtime(HttpServletRequest request) {
    return rules.runtime(user(request));
  }

  @GetMapping("/findings")
  public Object findings(HttpServletRequest request) {
    return HuntRedaction.apply(rules.findings(user(request)), user(request));
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    return Map.of(
        "csrfToken",
        access.token(request),
        "role",
        user(request).role(),
        "version",
        "2.0",
        "scenarioCount",
        48);
  }

  @GetMapping("/fields")
  public List<Map<String, String>> fields() {
    return HuntFields.all().stream()
        .map(f -> Map.of("name", f.name(), "label", f.label(), "type", f.type()))
        .toList();
  }

  @PostMapping("/queries")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object query(@RequestBody HuntQuery query, HttpServletRequest request) {
    return HuntRedaction.apply(jobs.create(query, user(request)), user(request));
  }

  @GetMapping("/queries/{id}")
  public Object job(@PathVariable String id, HttpServletRequest request) {
    return HuntRedaction.apply(jobs.get(id, user(request)), user(request));
  }

  @PostMapping("/queries/{id}/cancel")
  public Object cancel(@PathVariable String id, HttpServletRequest request) {
    return HuntRedaction.apply(jobs.cancel(id, user(request)), user(request));
  }

  @GetMapping("/queries/{id}/events")
  public Object page(
      @PathVariable String id,
      @RequestParam(required = false) Instant at,
      @RequestParam(required = false) String eventId,
      HttpServletRequest request) {
    if ((at == null) != (eventId == null))
      throw new IllegalArgumentException("페이지 커서의 시각과 이벤트 ID를 함께 지정하세요.");
    return HuntRedaction.apply(
        jobs.page(id, at == null ? null : new HuntQuery.Cursor(at, eventId), user(request)),
        user(request));
  }

  @GetMapping("/queries/{id}/events/{eventId}")
  public Object event(
      @PathVariable String id, @PathVariable String eventId, HttpServletRequest request) {
    return HuntRedaction.apply(jobs.event(id, eventId, user(request)), user(request));
  }

  @GetMapping("/scenarios")
  public List<HuntScenarios.Scenario> scenarios() {
    return scenarios.all();
  }

  @PostMapping("/scenarios/{id}/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object scenario(
      @PathVariable String id, @RequestBody HuntQuery query, HttpServletRequest request) {
    scenarios.require(id);
    return HuntRedaction.apply(jobs.create(query, user(request), id), user(request));
  }

  @GetMapping("/resources/{kind}")
  public List<HuntWorkspace.Resource> resources(
      @PathVariable String kind, HttpServletRequest request) {
    return workspace.list(kind, user(request)).stream()
        .map(item -> resourceResponse(item, request))
        .toList();
  }

  @GetMapping("/resources/{kind}/{id}")
  public HuntWorkspace.Resource resource(
      @PathVariable String kind, @PathVariable String id, HttpServletRequest request) {
    var item = workspace.get(id, user(request));
    if (!item.kind().equals(kind)) throw new IllegalArgumentException("잘못된 항목 종류입니다.");
    return resourceResponse(item, request);
  }

  @PostMapping("/resources/{kind}")
  @ResponseStatus(HttpStatus.CREATED)
  public HuntWorkspace.Resource create(
      @PathVariable String kind,
      @RequestBody HuntWorkspace.Save input,
      HttpServletRequest request) {
    checkAdminResource(kind, input, user(request));
    return resourceResponse(workspace.save(kind, null, input, user(request)), request);
  }

  @PutMapping("/resources/{kind}/{id}")
  public HuntWorkspace.Resource update(
      @PathVariable String kind,
      @PathVariable String id,
      @RequestBody HuntWorkspace.Save input,
      HttpServletRequest request) {
    checkAdminResource(kind, input, user(request));
    return resourceResponse(workspace.save(kind, id, input, user(request)), request);
  }

  public record PinRequest(String jobId, String eventId) {}

  @PostMapping("/investigations/{id}/evidence")
  public Map<String, Object> pin(
      @PathVariable String id, @RequestBody PinRequest input, HttpServletRequest request) {
    return workspace.pin(id, input.jobId(), input.eventId(), user(request));
  }

  @GetMapping("/investigations/{id}/evidence")
  public Object evidence(@PathVariable String id, HttpServletRequest request) {
    return HuntRedaction.apply(workspace.evidence(id, user(request)), user(request));
  }

  @GetMapping("/investigations/{id}/audit")
  public List<Map<String, Object>> audit(@PathVariable String id, HttpServletRequest request) {
    return workspace.auditLog(id, user(request));
  }

  @GetMapping("/investigations/{id}/export")
  public ResponseEntity<InputStreamResource> export(
      @PathVariable String id, HttpServletRequest request) {
    return downloadResponse(exports.prepare(id, user(request)));
  }

  public record PrepareExport(long version) {}

  @PostMapping("/investigations/{id}/exports")
  @ResponseStatus(HttpStatus.CREATED)
  public HuntExports.Ticket prepareExport(
      @PathVariable String id, @RequestBody PrepareExport input, HttpServletRequest request) {
    return exports.ticket(id, input.version(), user(request));
  }

  @GetMapping("/exports/{key}")
  public ResponseEntity<InputStreamResource> preparedExport(
      @PathVariable String key, HttpServletRequest request) {
    return downloadResponse(exports.consume(key, user(request)));
  }

  private ResponseEntity<InputStreamResource> downloadResponse(HuntExportFiles.Download download) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + download.filename())
        .header("X-Hunt-Export-SHA256", download.sha256())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore())
        .contentLength(download.length())
        .contentType(MediaType.APPLICATION_JSON)
        .body(download.resource());
  }

  @GetMapping("/coverage")
  public List<Map<String, Object>> coverage() {
    var compiler = new HuntSql(storage.database(), Map.of(), storage.materialized());
    Instant now = Instant.now();
    var query =
        new HuntQuery(now.minusSeconds(86400), now, null, "", "event_type", "count", 200, null);
    return storage.read(
        compiler.statement(
            "SELECT source_type,source_id,event_type,count() AS count,min(observed_at) AS"
                + " oldest,max(observed_at) AS newest,countIf(src_ip!='') AS"
                + " addressFields,countIf(actor!='') AS accountFields,countIf(process_name!='') AS"
                + " processFields FROM "
                + compiler.source(query, now)
                + " GROUP BY source_type,source_id,event_type ORDER BY count DESC LIMIT 200 FORMAT"
                + " JSONEachRow"),
        "hunt-" + UUID.randomUUID() + "-coverage");
  }

  private void checkAdminResource(String kind, HuntWorkspace.Save input, AuthUser user) {
    if (input == null || input.body() == null) throw new IllegalArgumentException("저장 내용을 확인하세요.");
    if (Set.of("lookup", "indicator").contains(kind) && user.role() != Role.ADMIN)
      throw new ForbiddenException("룩업·IOC 관리는 관리자 권한이 필요합니다.");
    if (kind.equals("rule")) {
      Object scenario = input.body().get("scenarioId");
      scenarios.require(String.valueOf(scenario));
      if (!"DRAFT".equals(input.body().getOrDefault("state", "DRAFT")))
        throw new IllegalArgumentException("규칙 운영 상태는 전용 전환 기능에서 변경하세요.");
    }
  }

  private static AuthUser user(HttpServletRequest request) {
    return (AuthUser) request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
  }

  private static HuntWorkspace.Resource resourceResponse(
      HuntWorkspace.Resource item, HttpServletRequest request) {
    return (HuntWorkspace.Resource) HuntRedaction.apply(item, user(request));
  }
}
