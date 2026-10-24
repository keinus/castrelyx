package org.castrelyx.manager.hunt.v2;

import java.time.Instant;
import java.util.*;

/** Configuration is validated on write, before it can affect a scheduled query. */
final class HuntContent {
  private HuntContent() {}

  static void validate(String kind, HuntWorkspace.Save input) {
    if (kind.equals("lookup")) {
      String name = input.title().trim();
      if (!Set.of(
              "trusted_scanners",
              "internal_networks",
              "approved_dns",
              "approved_services",
              "business_hours",
              "trusted_processes",
              "protected_paths")
          .contains(name)) throw new IllegalArgumentException("지원하는 운영 룩업 이름을 선택하세요.");
      if (!(input.body().get("values") instanceof List<?> values)
          || values.isEmpty()
          || values.size() > 1000) throw new IllegalArgumentException("룩업 값은 1~1000개입니다.");
      for (Object item : values) {
        if (!(item instanceof String value) || value.isBlank() || value.length() > 1024)
          throw new IllegalArgumentException("룩업 값을 확인하세요.");
        if (name.equals("internal_networks"))
          new HuntSql("validation")
              .predicate(new HuntQuery.Filter("cidr", "dst_ip", List.of(value), null));
        if (Set.of("trusted_scanners", "approved_dns").contains(name)) validateIp(value);
        if (name.equals("approved_services"))
          try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("허용 서비스는 1~65535 포트 번호입니다.");
          }
        if (name.equals("business_hours")) {
          if (values.size() != 1 || !value.matches("\\d{1,2}-\\d{1,2}"))
            throw new IllegalArgumentException("업무시간은 9-18 형식으로 하나를 지정하세요. 시간대는 Asia/Seoul입니다.");
          var hours = value.split("-");
          int start = Integer.parseInt(hours[0]), end = Integer.parseInt(hours[1]);
          if (start < 0 || end > 24 || start >= end)
            throw new IllegalArgumentException("업무 시작·종료 시간을 확인하세요.");
        }
      }
      if (!input.shared()) throw new IllegalArgumentException("운영 룩업은 조직 공유로 저장하세요.");
    }
    if (kind.equals("indicator")) {
      String type = String.valueOf(input.body().get("type")),
          value = String.valueOf(input.body().getOrDefault("value", ""));
      if (value.isBlank() || value.length() > 2048)
        throw new IllegalArgumentException("IOC 값을 확인하세요.");
      switch (type) {
        case "ip" -> validateIp(value);
        case "domain" -> {
          if (value.length() > 253
              || !value.matches("(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}"))
            throw new IllegalArgumentException("도메인 IOC를 확인하세요.");
        }
        case "sha256" -> {
          if (!value.matches("(?i)[a-f0-9]{64}"))
            throw new IllegalArgumentException("SHA-256 IOC는 64자리 16진수입니다.");
        }
        case "tls_fingerprint" -> {
          if (!value.matches("[A-Za-z0-9_:.-]{16,200}"))
            throw new IllegalArgumentException("TLS 지문을 확인하세요.");
        }
        default -> throw new IllegalArgumentException("지원하는 IOC 유형을 선택하세요.");
      }
      if (!(input.body().get("source") instanceof String source)
          || source.isBlank()
          || source.length() > 240) throw new IllegalArgumentException("IOC 출처를 기록하세요.");
      if (!(input.body().get("confidence") instanceof Number confidence)
          || confidence.doubleValue() < 0
          || confidence.doubleValue() > 100) throw new IllegalArgumentException("신뢰도는 0~100입니다.");
      try {
        Instant expiration = Instant.parse(String.valueOf(input.body().get("expiresAt")));
        if (!expiration.isAfter(Instant.now())
            || expiration.isAfter(Instant.now().plusSeconds(365 * 86400L)))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new IllegalArgumentException("IOC 만료 시각은 현재 이후 365일 이내로 지정하세요.");
      }
      if (!input.shared()) throw new IllegalArgumentException("IOC는 조직 공유로 저장하세요.");
    }
  }

  private static void validateIp(String value) {
    // Reuse the literal-only CIDR validator; no DNS resolution is permitted for arbitrary
    // hostnames.
    new HuntSql("validation")
        .predicate(
            new HuntQuery.Filter(
                "cidr", "dst_ip", List.of(value + (value.contains(":") ? "/128" : "/32")), null));
  }
}
