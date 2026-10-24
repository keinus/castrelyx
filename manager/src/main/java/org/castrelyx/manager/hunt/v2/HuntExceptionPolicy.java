package org.castrelyx.manager.hunt.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Exact candidate identities and a captured source scope; no broad wildcard exclusions. */
public final class HuntExceptionPolicy {
  public static final String VERSION = "1.0.0";

  private HuntExceptionPolicy() {}

  public record Grant(
      String id,
      long version,
      String scenarioId,
      String scenarioVersion,
      String scopeKey,
      Map<String, Object> scope,
      Instant validFrom,
      Instant expiresAt,
      long approvedBy,
      Instant approvedAt,
      String title,
      String reason,
      String evidenceSha256) {
    public Grant {
      scope = Collections.unmodifiableMap(new LinkedHashMap<>(scope));
    }
  }

  public record Suppressed(Map<String, Object> finding, List<Grant> approvals) {}

  public record Result(List<Map<String, Object>> findings, List<Suppressed> suppressed) {}

  public static String scopeKey(
      ObjectMapper json, String id, String version, HuntQuery query, Map<String, String> aliases) {
    try {
      var scope = new LinkedHashMap<String, Object>();
      scope.put("scenarioId", id);
      scope.put("scenarioVersion", version);
      scope.put("exceptionPolicyVersion", VERSION);
      scope.put("filter", query.filter());
      scope.put("aliases", new TreeMap<>(aliases));
      return HuntWorkspace.hash(json.writeValueAsString(scope));
    } catch (Exception e) {
      throw new IllegalArgumentException("예외의 조회 범위를 확인하세요.", e);
    }
  }

  public static Map<String, Object> identity(
      HuntScenarios.Scenario definition, Map<String, Object> finding) {
    var scope = new TreeMap<String, Object>();
    for (String key : definition.keys()) {
      if (!finding.containsKey(key)) throw new IllegalArgumentException("후보의 엔터티 식별자가 부족합니다.");
      Object raw = finding.get(key), value = raw;
      if (!key.equals("realm") && (raw == null || raw instanceof String text && text.isBlank()))
        throw new IllegalArgumentException("대상 식별자가 없는 후보에는 예외를 부여할 수 없습니다.");
      if (raw instanceof Number number)
        value = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
      else if (raw != null) {
        if (!(raw instanceof String)) throw new IllegalArgumentException("지원하지 않는 엔터티 식별자입니다.");
        if (((String) raw).length() > 4096) throw new IllegalArgumentException("엔터티 식별자가 너무 깁니다.");
      }
      scope.put(key, value);
    }
    return Collections.unmodifiableMap(scope);
  }

  public static Result apply(
      HuntScenarios.Scenario definition,
      String scopeKey,
      Instant asOf,
      List<Grant> grants,
      List<Map<String, Object>> candidates) {
    if (grants == null || grants.isEmpty()) return new Result(List.copyOf(candidates), List.of());
    var findings = new ArrayList<Map<String, Object>>();
    var suppressed = new ArrayList<Suppressed>();
    for (var finding : candidates) {
      Map<String, Object> identity;
      try {
        identity = identity(definition, finding);
      } catch (IllegalArgumentException missing) {
        findings.add(finding);
        continue;
      }
      var matching = new ArrayList<Grant>();
      Instant first = time(finding.get("first_seen")), last = time(finding.get("last_seen"));
      for (var grant : grants) {
        if (definition.id().equals(grant.scenarioId())
            && definition.version().equals(grant.scenarioVersion())
            && Objects.equals(scopeKey, grant.scopeKey())
            && grant.scope().equals(identity)
            && grant.approvedAt() != null
            && !grant.approvedAt().isAfter(asOf)
            && grant.validFrom() != null
            && grant.expiresAt() != null
            && !asOf.isBefore(grant.validFrom())
            && asOf.isBefore(grant.expiresAt())
            && first != null
            && last != null
            && !last.isBefore(first)
            && !first.isBefore(grant.validFrom())
            && last.isBefore(grant.expiresAt())) matching.add(grant);
      }
      if (matching.isEmpty()) findings.add(finding);
      else suppressed.add(new Suppressed(finding, List.copyOf(matching)));
    }
    return new Result(List.copyOf(findings), List.copyOf(suppressed));
  }

  public static Instant time(Object raw) {
    if (raw == null) return null;
    try {
      String value = String.valueOf(raw).replace(' ', 'T');
      return Instant.parse(value.endsWith("Z") ? value : value + "Z");
    } catch (RuntimeException invalid) {
      return null;
    }
  }
}
