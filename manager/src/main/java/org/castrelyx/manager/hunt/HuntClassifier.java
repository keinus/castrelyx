package org.castrelyx.manager.hunt;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic, explainable interpretation. Raw severity is never treated as proof of an attack. */
@Component
public class HuntClassifier {
  HuntAssessment classify(HuntEvent e) {
    String type = e.type;
    String state = e.value("conn_state", "connectionState");
    if (type.equals("suricata.alert")) return a("THREAT", "P2", "IDS 탐지 규칙 일치", "Suricata가 탐지 규칙에 일치하는 트래픽을 보고했습니다.",
        "HIGH", facts(e, "signature", "signatureId", "alertCategory"),
        List.of("탐지 규칙과 송신·수신 주소를 확인하세요.", "관련 연결과 대상 호스트 로그에서 실제 실행·영향 여부를 교차 확인하세요."),
        "규칙 일치는 침해 확정이 아닙니다. 허용된 점검이나 오탐 가능성이 있습니다.", "ids-alert");
    if (type.equals("zeek.notice")) {
      String note = e.value("note");
      if (note.equals("CaptureLoss::Too_Little_Traffic")) return a("OPERATIONAL", "P3", "센서의 TCP 관측 트래픽 부족",
          "Zeek가 캡처 손실을 평가하기에 충분한 TCP ACK를 보지 못했습니다. 트래픽이 없는 포트나 캡처 경로를 확인해야 합니다.", "HIGH",
          facts(e, "note", "msg", "peer_descr"), List.of("캡처 인터페이스의 링크와 실제 트래픽 유무를 확인하세요.", "미러링 대상·캡처 필터·센서 인터페이스 연결을 점검하세요."),
          "패킷 손실 또는 사이버 공격이 발생했다는 뜻은 아닙니다. 사용하지 않는 포트에서도 발생할 수 있습니다.", "sensor-low-traffic");
      if (note.equals("CaptureLoss::Too_Much_Loss") || note.equals("PacketFilter::Dropped_Packets"))
        return a("OPERATIONAL", "P2", "센서 캡처 손실 점검", "센서가 패킷 누락 또는 높은 캡처 손실 징후를 보고했습니다. 분석 가시성이 불완전할 수 있습니다.", "HIGH",
            facts(e, "note", "msg", "peer_descr"), List.of("센서 부하·인터페이스 드롭·미러링 경로를 점검하세요.", "capture_loss와 stats 기록으로 누락 정도를 확인하세요."),
            "실제 네트워크 손실과 센서 캡처 문제를 구분해야 하며, 이 알림 자체는 공격 탐지가 아닙니다.", "sensor-capture-loss");
      if (Set.of("SSH::Password_Guessing", "Scan::Port_Scan", "Scan::Address_Scan", "Intel::Notice",
          "Signatures::Sensitive_Signature").contains(note))
        return a("THREAT", "P2", "Zeek 위협 징후 알림", "Zeek의 보안 탐지 규칙이 조사할 활동을 보고했습니다.", "HIGH",
            facts(e, "note", "msg", "sub"), List.of("발신자가 허용된 점검 장비인지 확인하세요.", "관련 UID의 연결 및 인증 기록을 검토하세요."),
            "관측된 징후이며 공격 성공이나 침해를 확정하지 않습니다.", "zeek-threat-notice");
      if (note.equals("SSL::Invalid_Server_Cert")) return certificate(e);
      return a("REVIEW", "P3", "Zeek 조사 대상 알림", "Zeek 스크립트가 추가 확인이 필요한 상황을 보고했습니다.", "MEDIUM",
          facts(e, "note", "msg", "sub"), List.of("notice 종류와 원본 메시지를 확인하세요.", "관련 연결의 TLS·DNS·호스트 기록을 함께 확인하세요."),
          "notice에는 설정 문제나 자체 서명 인증서도 포함됩니다. 모든 notice가 공격은 아닙니다.", "zeek-notice");
    }
    if (type.equals("zeek.weird") || type.equals("zeek.analyzer"))
      return a("REVIEW", "P3", "프로토콜 해석 이상", "센서가 예상한 프로토콜 형태와 다른 트래픽 또는 분석 문제를 관측했습니다.", "MEDIUM",
          facts(e, "name", "weirdName", "addl", "failure_reason"),
          List.of("같은 UID의 연결 로그와 패킷 누락 여부를 확인하세요.", "단말 구현 차이·캡처 시작 시점·센서 상태를 점검하세요."),
          "프로토콜 이상만으로 공격을 판단할 수 없습니다.", "zeek-protocol-anomaly");
    if (e.loginFailure()) return a("REVIEW", "P3", "로그인 실패", "인증 실패가 관측되었습니다. 반복 횟수와 이후 성공 여부를 함께 확인해야 합니다.",
        type.equals("zeek.ssh") ? "MEDIUM" : "HIGH", facts(e, "auth_success", "auth_attempts", "actor", "outcome"),
        List.of("계정 소유자와 접속 원본을 확인하세요.", "연관 분석의 반복 실패·실패 후 성공 항목을 확인하세요."),
        type.equals("zeek.ssh") ? "Zeek SSH 인증 결과는 트래픽 기반 추정입니다. 서버 인증 로그와 대조하세요." : "단일 실패는 비밀번호 오입력이나 자동화 설정 오류일 수도 있습니다.", "auth-failure");
    if (e.loginSuccess()) return a("OBSERVATION", "P4", "로그인 성공 관측", "인증 성공 기록입니다. 정상 사용자 활동인지 여부는 계정과 접속 맥락으로 판단합니다.",
        type.equals("zeek.ssh") ? "MEDIUM" : "HIGH", facts(e, "auth_success", "actor", "outcome"),
        List.of("본인 또는 허용된 자동화 작업의 접속인지 확인하세요."),
        "성공 기록이 안전을 보장하지 않습니다. Zeek SSH의 결과는 서버 로그 확인이 필요합니다.", "auth-success");
    if (type.equals("zeek.conn")) {
      if (e.protocol.equals("udp") || e.multicast()) return a("OBSERVATION", "P4", e.multicast() ? "멀티캐스트 통신 관측" : "UDP 통신 관측",
          "연결 없는 데이터그램 통신 기록입니다. 응답이 없는 상태만으로 연결 실패나 공격을 의미하지 않습니다.", "HIGH",
          facts(e, "proto", "service", "conn_state", "connectionState"), List.of("예상한 장비 검색·DNS·서비스 통신인지 주소와 포트를 확인하세요."),
          "트래픽의 존재를 설명하며 악성 여부를 판정한 결과는 아닙니다.", "udp-observation");
      if (!e.protocol.equals("tcp")) return a(e.protocol.isBlank() ? "UNKNOWN" : "OBSERVATION", "P4", "통신 프로토콜 기록",
          "TCP 이외의 통신이거나 프로토콜 필드가 없는 기록입니다. TCP 연결 상태 규칙을 적용하지 않습니다.", "MEDIUM",
          facts(e, "proto", "conn_state", "connectionState", "service"), List.of("원본 프로토콜과 통신 목적을 확인하세요."),
          "프로토콜에 맞는 추가 해석이 필요하며 악성 여부는 판단하지 않습니다.", "other-protocol");
      if (Set.of("S0", "REJ", "RSTOS0").contains(state)) return a("OPERATIONAL", "P3", state.equals("REJ") ? "TCP 연결 거부" : "TCP 응답 미관측",
          state.equals("REJ") ? "연결 시도가 거부되었습니다." : "연결 시도에 대한 정상 응답을 센서가 관측하지 못했습니다.", "HIGH",
          facts(e, "conn_state", "connectionState", "proto", "service"),
          List.of("대상 서비스·방화벽 정책·경로·센서 가시성을 점검하세요.", "여러 포트나 대상으로 반복되면 탐색 징후 항목을 확인하세요."),
          "운영 원인과 스캔 모두 가능합니다. 단일 연결 상태로 공격을 확정하지 않습니다.", "tcp-unanswered");
      if (Set.of("SF", "S1").contains(state)) return a("OBSERVATION", "P4", state.equals("SF") ? "TCP 연결 수립·종료 관측" : "TCP 연결 수립 관측",
          "TCP 연결 상태 기록입니다. 통신 상대와 사용한 서비스가 기대한 대상인지 확인할 수 있습니다.", "HIGH",
          facts(e, "conn_state", "connectionState", "service", "duration"), List.of("같은 UID의 DNS·TLS·HTTP 기록에서 통신 목적을 확인하세요."),
          "정상적인 연결 절차도 악성 통신에 사용될 수 있습니다.", "tcp-observation");
      return a("REVIEW", "P3", "TCP 종료·부분 관측 확인", "연결 재설정이나 일부 구간만 관측된 상태일 수 있습니다.", "MEDIUM",
          facts(e, "conn_state", "connectionState", "history", "missed_bytes"), List.of("양쪽 단말의 연결 종료 사유와 캡처 누락을 확인하세요."),
          "연결 상태만으로 장애나 공격 원인을 확정할 수 없습니다.", "tcp-partial");
    }
    if (type.equals("zeek.dns")) {
      String code = e.value("rcode_name", "rcode");
      if (Set.of("NXDOMAIN", "SERVFAIL", "REFUSED", "3", "2", "5").contains(code))
        return a("OPERATIONAL", "P3", "DNS 조회 실패 응답", "DNS 서버가 이름을 찾지 못했거나 질의를 처리·허용하지 못했습니다.", "HIGH",
            facts(e, "query", "rcode_name", "rcode"), List.of("질의 도메인과 DNS 서버 설정을 확인하세요.", "실패가 여러 이름으로 반복되는지 연관 분석을 확인하세요."),
            "오타·서비스 검색·DNS 설정 문제도 원인입니다. DGA나 악성 도메인으로 단정하지 않습니다.", "dns-failure");
      return a("OBSERVATION", "P4", code.isBlank() ? "DNS 질의 관측" : "DNS 응답 관측", "도메인 또는 로컬 서비스의 주소를 조회한 기록입니다.", "HIGH",
          facts(e, "query", "qtype_name", "qtype", "rcode_name", "rcode"), List.of("질의 이름과 요청 장비가 예상한 서비스인지 확인하세요."),
          code.isBlank() ? "응답 코드가 없어 조회 성공 여부는 아직 판단할 수 없습니다." : "DNS 응답 성공은 도메인의 안전성을 보장하지 않습니다.", "dns-observation");
    }
    if (type.equals("zeek.ssl")) {
      String validation = e.value("validation_status", "validationStatus");
      if (!validation.isBlank() && !validation.equalsIgnoreCase("ok")) return certificate(e);
      if (Set.of("SSLv2", "SSLv3", "TLSv10", "TLSv11", "TLSv1", "TLSv1.0", "TLSv1.1").contains(e.value("version", "tlsVersion")))
        return a("REVIEW", "P3", "구형 TLS/SSL 사용", "구형 암호화 프로토콜이 관측되었습니다. 조직의 허용 정책과 비교해야 합니다.", "HIGH",
            facts(e, "version", "tlsVersion", "cipher"), List.of("서버와 클라이언트의 TLS 지원 및 정책을 확인하세요."), "구형 버전 사용은 공격 성공의 증거가 아닙니다.", "legacy-tls");
      return a("OBSERVATION", "P4", "TLS 암호화 통신 관측", "서버와의 TLS 협상 정보를 관측했습니다. 버전·암호군·서버 이름을 확인할 수 있습니다.", "HIGH",
          facts(e, "server_name", "serverName", "version", "tlsVersion", "established", "validation_status"),
          List.of("서버 이름과 목적지가 허용된 서비스인지 확인하세요."),
          "암호화는 악성 여부를 판별하지 않습니다. 인증서 검증 결과가 없으면 신뢰 여부도 미확인입니다.", "tls-observation");
    }
    if (type.equals("zeek.http")) {
      int status;
      try { status = Integer.parseInt(e.value("status_code", "statusCode")); } catch (NumberFormatException ex) { status = 0; }
      return a(status >= 500 ? "OPERATIONAL" : status >= 400 ? "REVIEW" : "OBSERVATION", status >= 400 ? "P3" : "P4",
          status >= 500 ? "HTTP 서버 오류 응답" : status >= 400 ? "HTTP 요청 실패 응답" : "HTTP 요청·응답 관측",
          "웹 요청과 응답 코드 기록입니다. 응답 코드와 요청 경로를 함께 해석합니다.", "HIGH", facts(e, "host", "method", "uri", "status_code", "statusCode"),
          List.of("애플리케이션 로그와 요청 경로를 비교하세요."), "HTTP 상태 코드만으로 공격 또는 취약점 악용을 단정할 수 없습니다.", "http-status");
    }
    String message = HuntEvent.text(e.event.get("message")).toLowerCase(Locale.ROOT);
    String program = HuntEvent.text(e.event.get("program"));
    if (type.startsWith("system.") && program.equals("dockerd") && message.contains("healthcheck failed"))
      return a("OPERATIONAL", "P3", "Docker 상태 점검 실패", "Docker가 연결 또는 세션 상태 검사 실패를 기록했습니다. 배포·빌드 종료 시점의 로그와 대조해야 합니다.", "HIGH",
          facts(e, "program", "message"), List.of("같은 시각의 배포·빌드 작업과 Docker 세션 종료 이력을 확인하세요.", "실행 중인 컨테이너의 상태와 실제 서비스 응답을 확인하세요."),
          "빌드 세션 오류와 애플리케이션 장애는 다릅니다. 이 메시지만으로 서비스 중단이나 공격을 확정할 수 없습니다.", "docker-healthcheck");
    if (type.equals("system.service.failure") || type.equals("collector_error") ||
        type.startsWith("system.") && (message.contains("out of memory") || message.contains("no space left on device") || message.contains("segmentation fault")))
      return a("OPERATIONAL", "P2", "서비스·수집기 운영 오류", "서비스 실행 또는 자원·상태 점검 실패가 기록되었습니다.", "HIGH",
          facts(e, "program", "provider", "outcome", "message"), List.of("서비스 상태와 최근 배포·재시작 이력을 확인하세요.", "메모리·디스크·의존 서비스 상태를 점검하세요."),
          "운영 오류를 설명하는 분류이며 보안 원인이 배제된다는 뜻은 아닙니다.", "service-failure");
    if (type.startsWith("auth.") || type.startsWith("authentication.") || type.startsWith("authorization.") ||
        type.startsWith("incident.") || type.startsWith("quarantine.") || type.startsWith("security."))
      return a("REVIEW", "P3", "계정·보안 활동 확인", "계정, 권한 또는 보안 제어 관련 활동입니다. 승인된 작업인지 확인이 필요합니다.", "MEDIUM",
          facts(e, "actor", "action", "outcome", "eventId"), List.of("승인된 변경 및 운영자 작업과 대조하세요."),
          "보안 로그라는 이유만으로 사이버 공격을 의미하지 않습니다.", "security-activity");
    if (Set.of("ERROR", "CRITICAL", "WARNING").contains(HuntEvent.text(e.event.get("severity")).toUpperCase(Locale.ROOT)))
      return a("REVIEW", "P3", "원천 경고·오류 확인", "원천 로그가 경고 또는 오류 등급을 부여했습니다. 원인을 구분할 추가 근거가 필요합니다.", "LOW",
          facts(e, "severity", "program", "provider"), List.of("원본 메시지와 같은 시각의 호스트 상태를 확인하세요."),
          "원천 심각도는 공격 위험도와 다릅니다.", "source-severity");
    if (type.startsWith("system.") || type.startsWith("pam.") || type.startsWith("config.") || type.equals("zeek.known_services"))
      return a("OBSERVATION", "P4", "시스템·서비스 활동 기록", "서비스 상태 변화 또는 시스템 활동이 기록되었습니다.", "MEDIUM", facts(e, "program", "action", "outcome"),
          List.of("예정된 작업 시간과 서비스 변경 이력을 비교하세요."), "이 규칙에서 위협 근거를 찾지 못했으며 안전을 보장하는 판정은 아닙니다.", "system-observation");
    return a("UNKNOWN", "P4", "판단 근거 부족", "지원하는 해석 규칙이나 필요한 구조화 필드가 충분하지 않습니다.", "LOW", facts(e),
        List.of("원본 기록과 발생 서비스의 로그 정의를 확인하세요."), "분석하지 못한 로그를 정상 또는 안전으로 간주하지 않습니다.", "unclassified");
  }

  private HuntAssessment certificate(HuntEvent e) {
    return a("OPERATIONAL", "P2", "TLS 인증서 검증 문제", "센서가 서버 인증서의 신뢰 검증 문제를 보고했습니다.", "HIGH",
        facts(e, "note", "validation_status", "validationStatus", "msg", "server_name", "serverName"),
        List.of("인증서 만료·서버 이름·중간 CA·신뢰 CA 구성을 확인하세요.", "예상하지 못한 발급자나 서버 변경이면 보안 담당자에게 확인하세요."),
        "사설 CA 미등록도 원인입니다. 중간자 공격의 확정 증거는 아닙니다.", "tls-certificate");
  }

  private static List<String> facts(HuntEvent e, String... keys) {
    var facts = new java.util.ArrayList<String>(); facts.add("event_type = " + e.type);
    if (!e.sourceIp.isBlank()) facts.add("출발지 = " + e.sourceIp);
    if (!e.targetIp.isBlank()) facts.add("목적지 = " + e.targetIp + (e.targetPort.isBlank() ? "" : " / " + e.targetPort));
    for (String key : keys) { String value = e.value(key); if (!value.isBlank()) facts.add(key + " = " + value.substring(0, Math.min(value.length(), 400))); }
    return facts;
  }

  private static HuntAssessment a(String category, String priority, String title, String explanation, String confidence,
      List<String> evidence, List<String> nextSteps, String caveat, String ruleId) {
    return new HuntAssessment(category, priority, title, explanation, confidence, evidence, nextSteps, caveat, ruleId);
  }
}
