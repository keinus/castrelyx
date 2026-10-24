package org.castrelyx.manager.hunt.v2;

import java.util.*;

/** Explicit field registry: user input is never interpolated as a SQL identifier. */
public final class HuntFields {
  private HuntFields() {}

  public record Field(String name, String label, String type, String expression) {}

  private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

  static {
    direct("asset_uid", "관측 자산", "string");
    direct("source_id", "수집원", "string");
    direct("source_type", "수집 경로", "string");
    direct("event_type", "이벤트 유형", "string");
    field("provider", "이벤트 공급자", "string", "provider", "ProviderName");
    field("channel", "이벤트 채널", "string", "channel", "Channel");
    FIELDS.put("severity", new Field("severity", "원천 등급", "string", "ifNull(severity, '')"));
    field("src_ip", "출발지 IP", "ip", "src_ip", "id.orig_h", "src");
    field("dst_ip", "목적지 IP", "ip", "dest_ip", "dst_ip", "id.resp_h", "dst");
    field("src_port", "출발지 포트", "number", "src_port", "id.orig_p");
    field("dst_port", "목적지 포트", "number", "dest_port", "dst_port", "id.resp_p", "p");
    field("protocol", "프로토콜", "string", "proto", "protocol");
    field("actor", "계정", "string", "actor", "user", "TargetUserName", "User");
    field("realm", "계정 영역", "string", "realm", "TargetDomainName", "domain");
    field("account_id", "대상 계정 고유 ID", "string", "account_id", "TargetSid", "MemberSid");
    field("outcome", "결과", "string", "outcome", "auth_success");
    field("action", "동작", "string", "action");
    field("connection_uid", "연결 UID", "string", "uid", "flow_id");
    field("community_id", "Community ID", "string", "community_id");
    field("domain", "도메인", "string", "query", "server_name", "serverName", "host");
    FIELDS.put(
        "registered_domain",
        new Field(
            "registered_domain",
            "등록 도메인",
            "string",
            "cutToFirstSignificantSubdomainRFC(lower(" + FIELDS.get("domain").expression() + "))"));
    field("dns_type", "DNS 유형", "string", "qtype_name", "qtype");
    field("dns_code", "DNS 응답", "string", "rcode_name", "rcode");
    jsonArrayText("dns_answers", "DNS 응답 주소", "answers");
    field("dns_ttl", "DNS TTL", "number", "ttl", "min_ttl");
    field("bytes_sent", "송신 바이트", "number", "orig_bytes", "bytes_sent");
    field("bytes_received", "수신 바이트", "number", "resp_bytes", "bytes_received");
    field("http_method", "HTTP 메서드", "string", "method", "http_method");
    field("http_host", "HTTP Host", "string", "host", "http_host");
    field("http_body_bytes", "HTTP 요청 크기", "number", "request_body_len", "http_body_bytes");
    field("http_uri", "HTTP 경로", "string", "uri", "url");
    field("tls_fingerprint", "TLS 지문", "string", "ja4", "ja3", "tls_fingerprint");
    field("certificate_status", "인증서 상태", "string", "validation_status", "validationStatus");
    field("connection_state", "연결 상태", "string", "conn_state", "connection_state");
    field("process_id", "프로세스 ID", "string", "process_guid", "ProcessGuid", "process_id", "pid");
    field("parent_id", "부모 프로세스 ID", "string", "parent_guid", "ParentProcessGuid", "parent_pid");
    // Graph identities keep GUIDs, PID generations and observer scope separate.
    field("process_guid", "프로세스 GUID", "string", "process_guid", "ProcessGuid");
    field("parent_guid", "부모 GUID", "string", "parent_guid", "ParentProcessGuid");
    field("process_pid", "프로세스 PID", "string", "process_pid", "pid", "ProcessId", "NewProcessId");
    field("parent_pid", "부모 PID", "string", "parent_pid", "ppid", "ParentProcessId");
    field("process_started_at", "프로세스 시작 시각", "string", "process_started_at", "process_start_time");
    field("parent_started_at", "부모 시작 시각", "string", "parent_started_at", "parent_start_time");
    field("boot_id", "부팅 ID", "string", "boot_id", "host_boot_id");
    field("network_namespace", "네트워크 관측 영역", "string", "network_namespace", "net_namespace");
    field("connection_started_at", "연결 시작 시각", "string", "connection_started_at", "flow_start");
    field("process_name", "실행 파일", "string", "Image", "process_name", "executable_path", "exe");
    field(
        "parent_name",
        "부모 실행 파일",
        "string",
        "ParentImage",
        "ParentProcessName",
        "parent_name",
        "parent_executable");
    field("command_line", "명령 인수", "string", "CommandLine", "command_line", "command");
    field("file_path", "파일 경로", "string", "TargetFilename", "file_path", "path");
    field("file_hash", "파일 해시", "string", "sha256", "file_hash", "Hashes");
    field("registry_path", "레지스트리", "string", "TargetObject", "registry_path");
    field("service", "서비스", "string", "service", "ServiceName", "service_name");
    field(
        "session_id",
        "인증 세션",
        "string",
        "session_id",
        "TargetLogonId",
        "SubjectLogonId",
        "LogonGuid");
    field("event_code", "이벤트 코드", "string", "event_id", "eventId", "EventID");
    field("share", "공유 이름", "string", "share", "ShareName");
    field("access_mask", "접근 권한", "string", "GrantedAccess", "access_mask");
    field("target_process", "대상 프로세스", "string", "TargetImage", "target_process");
    field("message", "메시지", "string", "message");
  }

  private static void direct(String name, String label, String type) {
    FIELDS.put(name, new Field(name, label, type, name));
  }

  private static void field(String name, String label, String type, String... paths) {
    List<String> candidates = new ArrayList<>();
    for (String prefix : List.of("", "data", "fields", "raw.data", "payload"))
      for (String path : paths) {
        // Bracket paths preserve Zeek's literal dotted field names.
        String jsonPath = "$" + (prefix.isEmpty() ? "" : "." + prefix) + ".\"" + path + "\"";
        candidates.add("nullIf(JSON_VALUE(event_json, '" + jsonPath + "'), '')");
      }
    String text = "coalesce(" + String.join(",", candidates) + ", '')";
    FIELDS.put(
        name,
        new Field(
            name, label, type, type.equals("number") ? "toFloat64OrNull(" + text + ")" : text));
  }

  private static void jsonArrayText(String name, String label, String path) {
    var candidates = new ArrayList<String>();
    for (String prefix : List.of("", "data", "fields", "raw.data", "payload")) {
      var keys = new ArrayList<String>();
      if (!prefix.isEmpty()) keys.addAll(List.of(prefix.split("\\.")));
      keys.add(path);
      String arguments =
          keys.stream()
              .map(key -> "'" + key + "'")
              .collect(java.util.stream.Collectors.joining(","));
      String raw = "JSONExtractRaw(event_json," + arguments + ")";
      candidates.add(
          "nullIf(if(startsWith("
              + raw
              + ",'['),"
              + raw
              + ",JSONExtractString(event_json,"
              + arguments
              + ")),'')");
    }
    FIELDS.put(
        name,
        new Field(name, label, "string", "coalesce(" + String.join(",", candidates) + ",'')"));
  }

  public static Field require(String name) {
    Field field = FIELDS.get(name);
    if (field == null) throw new IllegalArgumentException("지원하지 않는 필드: " + name);
    return field;
  }

  public static List<Field> all() {
    return List.copyOf(FIELDS.values());
  }

  public static String projection() {
    return FIELDS.values().stream()
        .map(f -> f.expression() + " AS " + f.name())
        .collect(java.util.stream.Collectors.joining(",\n"));
  }
}
