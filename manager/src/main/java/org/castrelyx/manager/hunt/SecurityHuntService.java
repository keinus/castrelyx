package org.castrelyx.manager.hunt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.castrelyx.manager.telemetry.ClickHouseClient;
import org.springframework.stereotype.Service;

@Service
public class SecurityHuntService {
  private static final int ANALYSIS_LIMIT = 10000;
  private static final Comparator<HuntEvent> TIME = Comparator.comparing((HuntEvent e) -> e.at).thenComparing(e -> e.id);
  private final ClickHouseClient clickHouse;
  private final HuntClassifier classifier;

  public SecurityHuntService(ClickHouseClient clickHouse, HuntClassifier classifier) {
    this.clickHouse = clickHouse; this.classifier = classifier;
  }

  public Map<String, Object> query(String range, String asset, String family, String severity, String search,
      String category, int offset, int limit) {
    List<Map<String, Object>> rows = clickHouse.querySecurityHuntEvents(range, asset, ANALYSIS_LIMIT + 1);
    boolean truncated = rows.size() > ANALYSIS_LIMIT;
    List<HuntEvent> events = rows.stream().limit(ANALYSIS_LIMIT).map(HuntEvent::new).toList();
    Map<String, HuntAssessment> base = new HashMap<>();
    for (HuntEvent event : events) { event.assessment = classifier.classify(event); base.put(event.id, event.assessment); }
    List<Finding> findings = correlate(events);
    // Promote only the implicated records; preserve their individual interpretation for drill-down.
    for (Finding finding : findings) for (HuntEvent event : finding.members) {
      if (rank(finding.assessment) < rank(event.assessment)) event.assessment = finding.assessment;
    }
    Map<String, List<HuntEvent>> connections = group(events, e -> !e.connectionKey().isBlank(), HuntEvent::connectionKey);
    String needle = search.trim().toLowerCase(Locale.ROOT);
    Predicate<HuntEvent> matches = e -> (family.equals("ALL") || e.family.equals(family))
        && (severity.equals("ALL") || severity.equalsIgnoreCase(HuntEvent.text(e.event.get("severity"))))
        && (needle.isBlank() || (e.id + " " + e.event + " " + e.assessment.title() + " " + e.assessment.explanation())
            .toLowerCase(Locale.ROOT).contains(needle));
    List<HuntEvent> matched = events.stream().filter(matches).toList();
    Map<String, Long> summary = new LinkedHashMap<>();
    for (String name : List.of("THREAT", "OPERATIONAL", "REVIEW", "OBSERVATION", "UNKNOWN"))
      summary.put(name, matched.stream().filter(e -> e.assessment.category().equals(name)).count());
    List<HuntEvent> filtered = matched.stream().filter(e -> category.equals("ALL") || e.assessment.category().equals(category))
        .sorted(Comparator.comparingInt((HuntEvent e) -> rank(e.assessment)).thenComparing(TIME.reversed())).toList();
    Set<String> visibleIds = filtered.stream().map(e -> e.id).collect(java.util.stream.Collectors.toSet());
    List<Finding> visibleFindings = findings.stream().filter(f -> f.members.stream().anyMatch(e -> visibleIds.contains(e.id)))
        .sorted(Comparator.comparingInt((Finding f) -> rank(f.assessment)).thenComparing(f -> f.last(), Comparator.reverseOrder())).toList();
    List<Map<String, Object>> items = filtered.stream().skip(offset).limit(limit).map(e -> {
      Map<String, Object> item = new LinkedHashMap<>(ref(e));
      item.put("event", e.event); item.put("assessment", e.assessment); item.put("interpretation", base.get(e.id));
      var related = connections.getOrDefault(e.connectionKey(), List.of()).stream().filter(other -> !other.id.equals(e.id)).sorted(TIME).toList();
      item.put("relatedCount", related.size()); item.put("related", related.stream().limit(20).map(SecurityHuntService::ref).toList());
      return item;
    }).toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items); result.put("total", filtered.size()); result.put("hasMore", offset + items.size() < filtered.size());
    result.put("nextOffset", offset + items.size()); result.put("summary", summary);
    result.put("findings", visibleFindings.stream().limit(50).map(SecurityHuntService::findingRow).toList());
    result.put("findingCount", visibleFindings.size()); result.put("ruleVersion", "hunt-1.0");
    result.put("coverage", Map.of("analyzed", events.size(), "limit", ANALYSIS_LIMIT, "truncated", truncated,
        "oldest", events.stream().map(e -> e.at).min(Instant::compareTo).map(Instant::toString).orElse(""),
        "newest", events.stream().map(e -> e.at).max(Instant::compareTo).map(Instant::toString).orElse(""),
        "generatedAt", Instant.now().toString(), "range", range));
    result.put("rules", rules());
    return result;
  }

  private static List<Finding> correlate(List<HuntEvent> events) {
    List<Finding> out = new ArrayList<>();
    var successes = group(events, e -> e.loginSuccess() && !e.authKey().isBlank(), HuntEvent::authKey);
    group(events, e -> e.loginFailure() && !e.authKey().isBlank(), HuntEvent::authKey).forEach((key, failures) -> {
      List<HuntEvent> burst = burst(failures, 5, HuntEvent::flowId, 5);
      if (burst.isEmpty()) return;
      HuntEvent following = null;
      // Search each success, so a later/larger failure burst cannot hide an earlier failure -> success sequence.
      for (HuntEvent candidate : successes.getOrDefault(key, List.of()).stream().sorted(TIME.reversed()).toList()) {
        var before = failures.stream().filter(e -> e.at.isBefore(candidate.at)
            && !e.at.isBefore(candidate.at.minusSeconds(900)) && sameActorWhenKnown(e, candidate)).toList();
        var preceding = burst(before, 5, HuntEvent::flowId, 5);
        if (!preceding.isEmpty() && !candidate.at.isAfter(preceding.getLast().at.plusSeconds(600))) {
          burst = preceding; following = candidate; break;
        }
      }
      HuntEvent last = burst.getLast();
      List<HuntEvent> members = new ArrayList<>(burst);
      if (following != null) members.add(following);
      boolean success = following != null;
      out.add(finding(success ? "auth-failure-success" : "auth-burst", key, members, "THREAT", success ? "P1" : "P2",
          success ? "반복 로그인 실패 후 성공" : "반복 로그인 실패 · 자격 증명 공격 의심",
          "같은 수집원·접속 원본·대상에서 5분 이내 " + burst.size() + "건의 인증 실패" + (success ? " 후 10분 이내 성공 기록이 관측되었습니다." : "가 관측되었습니다."),
          List.of("실패 기록 " + burst.size() + "건 / 최대 5분", "접속 원본: " + identity(last), "대상: " + target(last)),
          List.of("사용자 본인의 접속·오입력인지 확인하세요.", "성공 세션의 계정·명령·변경 기록을 확인하고 필요하면 대응 절차를 시작하세요."),
          "자동화 인증 오류나 사용자 재시도일 수 있습니다. 계정 필드가 없으면 동일 계정 여부는 미확인이고, Zeek SSH 인증은 추정입니다."));
    });
    group(events, HuntEvent::unsuccessfulTcp, e -> e.sensorKey() + "|" + e.sourceIp + "|" + e.targetIp).forEach((key, candidates) -> {
      var window = burst(candidates, 10, e -> e.targetPort, 10);
      if (!window.isEmpty()) out.add(finding("port-scan", key, window, "THREAT", "P2", "여러 포트 탐색 의심",
          "같은 출발지에서 같은 대상으로 5분 이내 서로 다른 TCP 포트 10개 이상에 실패한 연결 시도가 관측되었습니다.",
          List.of("출발지: " + window.getFirst().sourceIp, "대상: " + window.getFirst().targetIp,
              "서로 다른 목적지 포트: " + window.stream().map(e -> e.targetPort).distinct().count()),
          List.of("허용된 취약점 점검·모니터링 장비인지 확인하세요.", "대상 서비스와 방화벽 기록을 함께 조사하세요."),
          "허용된 스캐너일 수 있습니다. 성공한 연결·UDP·멀티캐스트는 이 규칙의 대상이 아닙니다."));
    });
    group(events, HuntEvent::unsuccessfulTcp, e -> e.sensorKey() + "|" + e.sourceIp + "|" + e.targetPort).forEach((key, candidates) -> {
      var window = burst(candidates, 20, e -> e.targetIp, 20);
      if (!window.isEmpty()) out.add(finding("host-scan", key, window, "THREAT", "P2", "여러 호스트 탐색 의심",
          "같은 출발지에서 같은 TCP 포트로 5분 이내 서로 다른 대상 20개 이상에 실패한 연결을 시도했습니다.",
          List.of("출발지: " + window.getFirst().sourceIp, "목적지 포트: " + window.getFirst().targetPort,
              "서로 다른 대상: " + window.stream().map(e -> e.targetIp).distinct().count()),
          List.of("발신 호스트의 점검 작업 여부를 확인하세요.", "대상 대역의 연결·차단 기록을 조사하세요."),
          "네트워크 조사 징후이며 침해 성공의 증거는 아닙니다."));
    });
    group(events, e -> e.type.equals("zeek.dns") && !e.sourceIp.isBlank() && !e.multicast()
        && Set.of("NXDOMAIN", "3").contains(e.value("rcode_name", "rcode")) && !e.value("query").isBlank(),
        e -> e.sensorKey() + "|" + e.sourceIp).forEach((key, candidates) -> {
      var window = burst(candidates, 20, e -> e.value("query"), 10);
      if (!window.isEmpty()) out.add(finding("dns-failure-burst", key, window, "REVIEW", "P3", "여러 이름에 대한 DNS 실패 반복",
          "5분 이내 NXDOMAIN 20건 이상과 서로 다른 질의 이름 10개 이상이 관측되었습니다.",
          List.of("출발지: " + window.getFirst().sourceIp, "실패 질의: " + window.size(), "서로 다른 이름: " + window.stream().map(e -> e.value("query")).distinct().count()),
          List.of("DNS 설정·오타·로컬 검색 접미사를 먼저 점검하세요.", "요청 프로세스와 도메인 패턴을 조사하세요."),
          "자동 생성 도메인 사용을 확정하지 않습니다. 로컬 서비스 탐색도 실패를 반복할 수 있습니다."));
    });
    group(events, e -> e.assessment.category().equals("OPERATIONAL"), e -> e.sensorKey() + "|"
        + e.assessment.ruleId() + "|" + HuntEvent.text(e.event.get("program")) + "|" + e.targetIp).forEach((key, candidates) -> {
      var window = burst(candidates, 3, e -> e.id, 3);
      if (!window.isEmpty()) out.add(finding("operational-repeat", key, window, "OPERATIONAL", "P2", "운영 문제 반복 · " + window.getFirst().assessment.title(),
          "동일 자산·수집원에서 같은 종류의 운영 문제가 5분 이내 " + window.size() + "건 관측되었습니다.",
          List.of("자산: " + window.getFirst().asset, "문제 종류: " + window.getFirst().assessment.ruleId(), "로그 수: " + window.size()),
          window.getFirst().assessment.nextSteps(), "반복 횟수는 관측 범위 내 로그 수입니다. 같은 근본 원인인지 원본과 변경 이력으로 확인하세요."));
    });
    return out;
  }

  /** Largest qualifying sliding window per source group; no fixed-bucket boundary blind spot. */
  private static List<HuntEvent> burst(List<HuntEvent> input, int minimum, Function<HuntEvent, String> distinctKey, int minimumDistinct) {
    List<HuntEvent> sorted = input.stream().filter(e -> !e.at.equals(Instant.EPOCH)).sorted(TIME).toList();
    Map<String, Integer> counts = new HashMap<>();
    int left = 0, bestLeft = 0, bestRight = 0;
    for (int right = 0; right < sorted.size(); right++) {
      counts.merge(distinctKey.apply(sorted.get(right)), 1, Integer::sum);
      while (sorted.get(left).at.isBefore(sorted.get(right).at.minusSeconds(300))) {
        String key = distinctKey.apply(sorted.get(left++));
        counts.computeIfPresent(key, (k, count) -> count == 1 ? null : count - 1);
      }
      if (right - left + 1 >= minimum && counts.size() >= minimumDistinct && right - left + 1 >= bestRight - bestLeft) {
        bestLeft = left; bestRight = right + 1;
      }
    }
    return List.copyOf(sorted.subList(bestLeft, bestRight));
  }

  private static Map<String, List<HuntEvent>> group(List<HuntEvent> events, Predicate<HuntEvent> include, Function<HuntEvent, String> key) {
    Map<String, List<HuntEvent>> groups = new LinkedHashMap<>();
    for (HuntEvent event : events) if (include.test(event)) groups.computeIfAbsent(key.apply(event), ignored -> new ArrayList<>()).add(event);
    return groups;
  }
  private static boolean sameActorWhenKnown(HuntEvent a, HuntEvent b) {
    String first = HuntEvent.text(a.event.get("actor")), second = HuntEvent.text(b.event.get("actor"));
    return first.isBlank() || second.isBlank() || first.equals(second);
  }
  private static String identity(HuntEvent e) { return e.sourceIp.isBlank() ? HuntEvent.text(e.event.get("actor")) : e.sourceIp; }
  private static String target(HuntEvent e) { return e.targetIp.isBlank() ? e.asset : e.targetIp; }
  private static int rank(HuntAssessment a) { return Integer.parseInt(a.priority().substring(1)) * 10 + (a.category().equals("THREAT") ? 0 : 1); }

  private record Finding(String id, HuntAssessment assessment, List<HuntEvent> members) {
    Instant last() { return members.getLast().at; }
  }
  private static Finding finding(String rule, String key, List<HuntEvent> members, String category, String priority,
      String title, String explanation, List<String> evidence, List<String> steps, String caveat) {
    var sorted = members.stream().sorted(TIME).toList();
    String id = UUID.nameUUIDFromBytes((rule + key + sorted.getFirst().id).getBytes(StandardCharsets.UTF_8)).toString();
    return new Finding(id, new HuntAssessment(category, priority, title, explanation, "MEDIUM", evidence, steps, caveat, rule), sorted);
  }
  private static Map<String, Object> findingRow(Finding f) {
    return Map.of("id", f.id, "assessment", f.assessment, "count", f.members.size(),
        "firstSeen", f.members.getFirst().at.toString(), "lastSeen", f.last().toString(),
        "assetUid", f.members.getFirst().asset,
        "timeline", f.members.stream().limit(20).map(SecurityHuntService::ref).toList());
  }
  private static Map<String, Object> ref(HuntEvent e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", e.id); row.put("observedAt", e.at.toString()); row.put("assetUid", e.asset);
    row.put("eventType", e.type); row.put("sourceIp", e.sourceIp); row.put("targetIp", e.targetIp);
    row.put("targetPort", e.targetPort); row.put("connectionUid", e.uid); row.put("protocol", e.protocol);
    String message = HuntEvent.text(e.event.get("message"));
    row.put("message", message.substring(0, Math.min(message.length(), 800)));
    return row;
  }
  private static List<Map<String, String>> rules() {
    return List.of(
        Map.of("name", "반복 로그인 실패", "condition", "같은 자산·수집원·접속 원본·대상에서 5분 내 서로 다른 실패 기록 5건 이상. Zeek는 UID로 구분."),
        Map.of("name", "실패 후 성공", "condition", "반복 실패 조건을 만족한 마지막 실패 이후 10분 내 같은 원본·대상에서 성공. 양쪽 계정 값이 있으면 일치해야 함."),
        Map.of("name", "포트·호스트 탐색", "condition", "5분 내 실패한 TCP 연결(S0/REJ/RSTOS0): 같은 대상의 포트 10개 이상, 또는 같은 포트의 대상 20개 이상. UDP·멀티캐스트 제외."),
        Map.of("name", "DNS 실패 반복", "condition", "같은 출발지에서 5분 내 NXDOMAIN 20건 이상, 서로 다른 질의 이름 10개 이상."),
        Map.of("name", "운영 문제 반복", "condition", "같은 자산·수집원·해석 규칙·프로그램·목적지에서 5분 내 3건 이상."),
        Map.of("name", "연결 연관", "condition", "같은 자산·수집원·Zeek UID의 DNS/TLS/연결 등 기록을 연결. UID 없는 로그는 임의로 연결하지 않음."));
  }
}
