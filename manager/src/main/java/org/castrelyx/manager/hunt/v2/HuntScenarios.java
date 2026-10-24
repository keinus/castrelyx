package org.castrelyx.manager.hunt.v2;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Executable, versioned packs. Conditions are trusted code, parameters are typed values. */
@Component
public class HuntScenarios {
  public record Scenario(
      String id,
      String group,
      String title,
      String hypothesis,
      List<String> requiredFields,
      int windowMinutes,
      double threshold,
      String mode,
      String condition,
      List<String> keys,
      String value,
      String follow,
      String third,
      boolean baseline,
      List<String> requiredLookups,
      List<String> falsePositives,
      List<String> steps,
      String version) {}

  private final List<Scenario> scenarios = new ArrayList<>();
  private static final String AUTH_FAIL =
      "(event_type IN ('auth.login.failure','pam.authentication.failure') OR (event_type='zeek.ssh' AND outcome='false'))";
  private static final String AUTH_OK =
      "(event_type='auth.login.success' OR (event_type='zeek.ssh' AND outcome='true'))";
  private static final String NET = "event_type IN ('zeek.conn','suricata.flow')";
  private static final String DNS = "event_type IN ('zeek.dns','suricata.dns')";
  private static final String PROC = "event_type='process.start'";
  private static final String OUT =
      "dst_ip!='' AND NOT (startsWith(dst_ip,'10.') OR startsWith(dst_ip,'192.168.') OR match(dst_ip,'^172\\.(1[6-9]|2[0-9]|3[01])\\.') OR startsWith(dst_ip,'127.') OR startsWith(dst_ip,'169.254.') OR startsWith(lower(dst_ip),'fe80:') OR startsWith(lower(dst_ip),'fc') OR startsWith(lower(dst_ip),'fd') OR startsWith(lower(dst_ip),'ff') OR dst_ip='::1')";

  public HuntScenarios() {
    add(
        "A01",
        "인증·계정",
        "단일 계정 무차별 인증",
        List.of("src_ip", "actor"),
        5,
        5,
        "COUNT",
        AUTH_FAIL,
        "src_ip,dst_ip,asset_uid,realm,actor",
        null,
        null,
        null,
        false,
        "");
    add(
        "A02",
        "인증·계정",
        "패스워드 스프레이",
        List.of("src_ip", "actor"),
        10,
        10,
        "DISTINCT",
        AUTH_FAIL,
        "src_ip,realm",
        "actor",
        null,
        null,
        false,
        "");
    add(
        "A03",
        "인증·계정",
        "분산 원본의 계정 공격",
        List.of("src_ip", "actor"),
        15,
        5,
        "DISTINCT",
        AUTH_FAIL,
        "actor,realm,asset_uid",
        "src_ip",
        null,
        null,
        false,
        "");
    add(
        "A04",
        "인증·계정",
        "실패 반복 후 성공",
        List.of("src_ip", "actor", "outcome"),
        5,
        5,
        "BURST_SEQUENCE",
        AUTH_FAIL,
        "src_ip,realm,actor,asset_uid",
        null,
        AUTH_OK,
        null,
        false,
        "");
    add(
        "A05",
        "인증·계정",
        "신규 외부 원본의 인증 성공",
        List.of("src_ip", "actor"),
        15,
        1,
        "NEW",
        AUTH_OK + " AND " + OUT.replace("dst_ip", "src_ip"),
        "realm,actor,src_ip",
        null,
        null,
        null,
        true,
        "");
    add(
        "A06",
        "인증·계정",
        "신규 로그인 후 권한 상승",
        List.of("src_ip", "actor"),
        10,
        1,
        "NEW_SEQUENCE",
        AUTH_OK,
        "realm,actor,asset_uid",
        "src_ip",
        "event_type IN ('auth.privilege.sudo','auth.privilege.assigned')",
        null,
        true,
        "");
    add(
        "B01",
        "탐색·횡적 이동",
        "다중 포트 탐색",
        List.of("src_ip", "dst_ip", "dst_port"),
        5,
        10,
        "DISTINCT",
        NET + " AND lower(protocol)='tcp'",
        "src_ip,dst_ip",
        "dst_port",
        null,
        null,
        false,
        "trusted_scanners");
    add(
        "B02",
        "탐색·횡적 이동",
        "다중 호스트 탐색",
        List.of("src_ip", "dst_ip", "dst_port"),
        5,
        20,
        "DISTINCT",
        NET,
        "src_ip,dst_port",
        "dst_ip",
        null,
        null,
        false,
        "trusted_scanners");
    add(
        "B03",
        "탐색·횡적 이동",
        "저속 탐색",
        List.of("src_ip", "dst_ip", "dst_port"),
        60,
        20,
        "SLOW_SCAN",
        NET,
        "src_ip",
        "dst_ip",
        null,
        null,
        false,
        "trusted_scanners");
    add(
        "B04",
        "탐색·횡적 이동",
        "새로운 내부 원격 접속",
        List.of("src_ip", "dst_ip", "dst_port"),
        15,
        1,
        "NEW",
        NET + " AND dst_port IN (22,3389,5985,5986)",
        "src_ip,dst_ip,dst_port",
        null,
        null,
        null,
        true,
        "internal_networks");
    add(
        "B05",
        "탐색·횡적 이동",
        "관리 공유의 다중 호스트 접근",
        List.of("src_ip", "share"),
        10,
        3,
        "DISTINCT",
        "match(lower(share),'(admin|c)\\$')",
        "src_ip,realm,actor",
        "asset_uid",
        null,
        null,
        false,
        "");
    add(
        "B06",
        "탐색·횡적 이동",
        "원격 로그인 뒤 실행 수단 생성",
        List.of("actor", "session_id"),
        10,
        1,
        "SEQUENCE",
        AUTH_OK,
        "asset_uid,realm,actor,session_id",
        null,
        "event_type IN ('service.create','task.create')",
        null,
        false,
        "");
    add(
        "C01",
        "DNS",
        "다수 이름의 DNS 실패",
        List.of("src_ip", "domain", "dns_code"),
        5,
        20,
        "DNS_FAILURE",
        DNS + " AND dns_code IN ('NXDOMAIN','3')",
        "src_ip",
        "domain",
        null,
        null,
        false,
        "");
    add(
        "C02",
        "DNS",
        "긴 이름과 다수 하위 도메인",
        List.of("src_ip", "domain", "registered_domain"),
        10,
        30,
        "DISTINCT",
        DNS + " AND arrayExists(x -> length(x)>=50,splitByChar('.',domain))",
        "src_ip,registered_domain",
        "lower(domain)",
        null,
        null,
        false,
        "");
    add(
        "C03",
        "DNS",
        "TXT 질의 급증",
        List.of("src_ip", "dns_type"),
        10,
        30,
        "BASELINE_COUNT",
        DNS + " AND dns_type IN ('TXT','16')",
        "src_ip",
        null,
        null,
        null,
        true,
        "");
    add(
        "C04",
        "DNS",
        "조직에서 희귀한 신규 도메인",
        List.of("src_ip", "domain"),
        15,
        2,
        "RARE",
        DNS,
        "domain",
        "src_ip",
        null,
        null,
        true,
        "");
    add(
        "C05",
        "DNS",
        "짧은 TTL과 잦은 주소 변경",
        List.of("src_ip", "dns_answers", "dns_ttl"),
        60,
        10,
        "FAST_FLUX",
        DNS + " AND dns_ttl<=60",
        "domain",
        "dns_answers",
        null,
        null,
        false,
        "");
    add(
        "C06",
        "DNS",
        "비승인 DNS 서버 사용",
        List.of("src_ip", "dst_ip"),
        5,
        5,
        "COUNT",
        DNS,
        "src_ip,dst_ip",
        null,
        null,
        null,
        false,
        "approved_dns");
    add(
        "D01",
        "C2·TLS·IOC",
        "주기적인 외부 통신",
        List.of("src_ip", "dst_ip"),
        30,
        20,
        "BEACON",
        NET + " AND " + OUT,
        "src_ip,dst_ip,dst_port",
        null,
        null,
        null,
        false,
        "");
    add(
        "D02",
        "C2·TLS·IOC",
        "신규 외부 서비스·포트",
        List.of("src_ip", "dst_ip", "dst_port"),
        15,
        5,
        "NEW",
        NET + " AND " + OUT,
        "src_ip,dst_ip,dst_port",
        null,
        null,
        null,
        true,
        "approved_services");
    add(
        "D03",
        "C2·TLS·IOC",
        "IP 주소를 직접 사용하는 HTTP",
        List.of("src_ip", "http_host", "dst_ip"),
        15,
        3,
        "NEW",
        "event_type IN ('zeek.http','suricata.http') AND (isIPv4String(http_host) OR isIPv6String(http_host)) AND "
            + OUT,
        "src_ip,dst_ip",
        null,
        null,
        null,
        true,
        "");
    add(
        "D04",
        "C2·TLS·IOC",
        "활성 IOC 일치",
        List.of(),
        15,
        1,
        "IOC",
        "1",
        "asset_uid",
        null,
        null,
        null,
        false,
        "indicators");
    add(
        "D05",
        "C2·TLS·IOC",
        "희귀 TLS 클라이언트 지문",
        List.of("src_ip", "tls_fingerprint", "dst_ip"),
        15,
        2,
        "RARE",
        "tls_fingerprint!='' AND " + OUT,
        "tls_fingerprint",
        "src_ip",
        null,
        null,
        true,
        "");
    add(
        "D06",
        "C2·TLS·IOC",
        "신규 외부 대상의 인증서 이상",
        List.of("src_ip", "dst_ip", "certificate_status"),
        15,
        3,
        "NEW",
        "certificate_status NOT IN ('','ok') AND connection_state IN ('SF','S1','true') AND " + OUT,
        "src_ip,dst_ip",
        null,
        null,
        null,
        true,
        "");
    add(
        "E01",
        "전송·유출",
        "외부 송신량 급증",
        List.of("src_ip", "dst_ip", "bytes_sent"),
        15,
        100000000,
        "BASELINE_SUM",
        NET + " AND " + OUT,
        "src_ip",
        "bytes_sent",
        null,
        null,
        true,
        "");
    add(
        "E02",
        "전송·유출",
        "송수신 비대칭",
        List.of("src_ip", "dst_ip", "bytes_sent", "bytes_received"),
        15,
        50000000,
        "ASYMMETRY",
        NET + " AND " + OUT,
        "src_ip,dst_ip",
        "bytes_sent",
        null,
        null,
        false,
        "");
    add(
        "E03",
        "전송·유출",
        "대용량 HTTP 업로드",
        List.of("src_ip", "http_method", "http_body_bytes"),
        5,
        10000000,
        "SUM",
        "http_method IN ('POST','PUT') AND " + OUT,
        "src_ip,dst_ip",
        "http_body_bytes",
        null,
        null,
        false,
        "");
    add(
        "E04",
        "전송·유출",
        "다수 신규 외부 대상으로 전송",
        List.of("src_ip", "dst_ip", "bytes_sent"),
        15,
        20,
        "FANOUT",
        NET + " AND " + OUT,
        "src_ip",
        "dst_ip",
        null,
        null,
        true,
        "");
    add(
        "E05",
        "전송·유출",
        "압축 후 외부 전송",
        List.of("process_id", "process_name", "bytes_sent"),
        10,
        10000000,
        "SEQUENCE",
        PROC + " AND match(lower(process_name),'(7z|zip|tar|rar)')",
        "asset_uid,actor",
        null,
        NET + " AND bytes_sent>=10000000 AND " + OUT,
        null,
        false,
        "");
    add(
        "E06",
        "전송·유출",
        "업무시간 밖 신규 대상 전송",
        List.of("src_ip", "dst_ip", "bytes_sent"),
        15,
        50000000,
        "NEW_SUM",
        NET + " AND " + OUT,
        "src_ip,dst_ip",
        "bytes_sent",
        null,
        null,
        true,
        "business_hours");
    add(
        "F01",
        "실행·프로세스",
        "문서·브라우저의 셸 실행",
        List.of("process_name", "parent_name"),
        5,
        1,
        "COUNT",
        PROC
            + " AND match(lower(parent_name),'(winword|excel|powerpnt|acrord|chrome|msedge|firefox)') AND match(lower(process_name),'(cmd|powershell|pwsh|wscript|cscript|bash|sh)(\\.exe)?$')",
        "asset_uid,actor,process_name",
        null,
        null,
        null,
        false,
        "");
    add(
        "F02",
        "실행·프로세스",
        "웹 서버의 셸과 외부 통신",
        List.of("process_name", "parent_name", "process_id"),
        5,
        1,
        "SEQUENCE",
        PROC
            + " AND match(lower(parent_name),'(httpd|apache|nginx|w3wp|tomcat)') AND match(lower(process_name),'(cmd|powershell|bash|sh)(\\.exe)?$')",
        "asset_uid,process_id",
        null,
        OUT + " AND dst_ip!=''",
        null,
        false,
        "");
    add(
        "F03",
        "실행·프로세스",
        "인코딩 PowerShell과 통신",
        List.of("process_name", "command_line", "process_id"),
        5,
        1,
        "SEQUENCE",
        PROC
            + " AND match(lower(process_name),'(powershell|pwsh)') AND match(lower(command_line),'-(enc|encodedcommand|e)\\s')",
        "asset_uid,process_id",
        null,
        OUT + " AND dst_ip!=''",
        null,
        false,
        "");
    add(
        "F04",
        "실행·프로세스",
        "시스템 도구를 통한 외부 URL 실행",
        List.of("process_name", "command_line"),
        5,
        1,
        "COUNT",
        PROC
            + " AND match(lower(process_name),'(mshta|regsvr32|rundll32|certutil|bitsadmin)') AND match(lower(command_line),'https?://')",
        "asset_uid,actor",
        null,
        null,
        null,
        false,
        "");
    add(
        "F05",
        "실행·프로세스",
        "임시 경로의 신규 실행 파일",
        List.of("process_name", "file_hash"),
        15,
        1,
        "NEW",
        PROC
            + " AND match(replaceAll(lower(process_name),char(92),'/'),'(/tmp/|/var/tmp/|/dev/shm/|/downloads/|/temp/)')",
        "asset_uid,file_hash",
        null,
        null,
        null,
        true,
        "");
    add(
        "F06",
        "실행·프로세스",
        "다운로드·권한 변경·실행",
        List.of("file_path", "actor", "command_line"),
        10,
        1,
        "SEQUENCE",
        "event_type='file.download'",
        "asset_uid,actor,file_path",
        null,
        "event_type='file.permission.change' AND positionCaseInsensitive(command_line,'chmod')>0",
        PROC,
        false,
        "");
    add(
        "G01",
        "지속성·권한 유지",
        "cron·systemd timer 변경",
        List.of("file_path"),
        15,
        1,
        "COUNT",
        "event_type IN ('file.create','file.modify') AND match(file_path,'(/cron|/systemd/.*\\.timer)')",
        "asset_uid,file_path",
        null,
        null,
        null,
        false,
        "");
    add(
        "G02",
        "지속성·권한 유지",
        "Windows 예약 작업 생성·변경",
        List.of("event_code", "actor"),
        15,
        1,
        "COUNT",
        "event_type IN ('task.create','task.modify') OR event_code IN ('4698','4702')",
        "asset_uid,actor",
        null,
        null,
        null,
        false,
        "");
    add(
        "G03",
        "지속성·권한 유지",
        "자동 실행 레지스트리 변경",
        List.of("registry_path"),
        15,
        1,
        "COUNT",
        "startsWith(event_type,'registry.') AND match(replaceAll(lower(registry_path),char(92),'/'),'(/run(once)?(/|$)|/services(/|$))')",
        "asset_uid,registry_path",
        null,
        null,
        null,
        false,
        "");
    add(
        "G04",
        "지속성·권한 유지",
        "신규 서비스 실행",
        List.of("service", "process_name"),
        10,
        1,
        "SEQUENCE",
        "event_type IN ('service.create','service.modify')",
        "asset_uid,service",
        null,
        PROC,
        null,
        false,
        "");
    add(
        "G05",
        "지속성·권한 유지",
        "SSH 접근 설정 변경",
        List.of("file_path"),
        15,
        1,
        "COUNT",
        "event_type IN ('file.create','file.modify') AND match(file_path,'(authorized_keys|sshd_config)')",
        "asset_uid,file_path",
        null,
        null,
        null,
        false,
        "");
    add(
        "G06",
        "지속성·권한 유지",
        "계정 생성 후 관리자 권한 부여",
        List.of("account_id"),
        10,
        1,
        "SEQUENCE",
        "event_type='account.create' OR event_code='4720'",
        "asset_uid,account_id",
        null,
        "event_type='account.privilege.add' OR event_code IN ('4728','4732','4756')",
        null,
        false,
        "");
    add(
        "H01",
        "자격 증명·방어 회피",
        "비허용 프로세스의 LSASS 접근",
        List.of("process_name", "target_process", "access_mask"),
        5,
        1,
        "COUNT",
        "event_type='process.access' AND endsWith(lower(target_process),'lsass.exe') AND access_mask IN ('0x1010','0x1410','0x1fffff','0x143a')",
        "asset_uid,process_name",
        null,
        null,
        null,
        false,
        "trusted_processes");
    add(
        "H02",
        "자격 증명·방어 회피",
        "자격 증명 파일 접근",
        List.of("file_path", "process_name"),
        5,
        1,
        "COUNT",
        "event_type='file.read' AND (file_path='/etc/shadow' OR position(file_path,'/.ssh/')>0)",
        "asset_uid,process_name",
        null,
        null,
        null,
        false,
        "trusted_processes");
    add(
        "H03",
        "자격 증명·방어 회피",
        "감사 로그 삭제·감사 기능 변경",
        List.of(),
        15,
        1,
        "COUNT",
        "event_type IN ('audit.disabled','audit.cleared') OR (event_code='1102' AND lower(channel)='security') OR (event_code='104' AND lower(provider) IN ('microsoft-windows-eventlog','eventlog'))",
        "asset_uid",
        null,
        null,
        null,
        false,
        "");
    add(
        "H04",
        "자격 증명·방어 회피",
        "보안 제어 중지",
        List.of("service", "action"),
        15,
        1,
        "COUNT",
        "event_type IN ('security.service.stopped','firewall.disabled')",
        "asset_uid,service",
        null,
        null,
        null,
        false,
        "");
    add(
        "H05",
        "자격 증명·방어 회피",
        "복구 수단 제거",
        List.of("process_name", "command_line"),
        15,
        1,
        "COUNT",
        PROC
            + " AND match(lower(command_line),'(vssadmin.*delete.*shadows|wmic.*shadowcopy.*delete|wbadmin.*delete.*catalog|bcdedit.*recoveryenabled.*no)')",
        "asset_uid,actor",
        null,
        null,
        null,
        false,
        "");
    add(
        "H06",
        "자격 증명·방어 회피",
        "보호 폴더의 대량 변경",
        List.of("file_path", "action"),
        1,
        100,
        "FILE_BURST",
        "event_type IN ('file.create','file.modify','file.rename')",
        "asset_uid",
        null,
        null,
        null,
        false,
        "protected_paths");
    if (scenarios.size() != 48) throw new IllegalStateException("Incomplete scenario catalogue");
  }

  private void add(
      String id,
      String group,
      String title,
      List<String> required,
      int minutes,
      double threshold,
      String mode,
      String condition,
      String keys,
      String value,
      String follow,
      String third,
      boolean baseline,
      String lookups) {
    scenarios.add(
        new Scenario(
            id,
            group,
            title,
            title + "이 발생했는지 필요한 원천과 시간 흐름을 대조합니다.",
            List.copyOf(required),
            minutes,
            threshold,
            mode,
            condition,
            List.of(keys.split(",")),
            value,
            follow,
            third,
            baseline,
            lookups.isEmpty() ? List.of() : List.of(lookups.split(",")),
            List.of("승인된 배포·점검·백업·관리 자동화", "공유 계정·프록시·센서 가시성 차이"),
            List.of(
                "발생 구간의 원본과 필수 필드 누락을 확인하세요.",
                "관련 호스트·계정·연결의 전후 활동을 조사하세요.",
                "승인 작업과 대조하고 증거와 판정을 조사에 저장하세요."),
            "2.2.0"));
  }

  public List<Scenario> all() {
    return List.copyOf(scenarios);
  }

  public Scenario require(String id) {
    return scenarios.stream()
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("시나리오를 찾을 수 없습니다."));
  }

  public HuntSql.Statement compile(
      Scenario s, HuntQuery query, Instant asOf, Map<String, List<String>> lookups, HuntSql sql) {
    String source = sql.source(query, asOf),
        scope = sql.where(query),
        condition = "(" + s.condition() + ")";
    boolean sequence = Set.of("SEQUENCE", "NEW_SEQUENCE", "BURST_SEQUENCE").contains(s.mode());
    if (!sequence)
      for (String field : s.requiredFields())
        condition +=
            " AND "
                + (HuntFields.require(field).type().equals("number")
                    ? "isNotNull(" + field + ")"
                    : field + "!=''");
    for (String name : s.requiredLookups()) {
      List<String> values = lookups.get(name);
      if (values == null || values.isEmpty())
        throw new IllegalStateException("설정이 필요한 룩업: " + name);
      if (name.equals("indicators")) {
        Map<String, String> types =
            Map.of(
                "src_ip",
                "ip",
                "dst_ip",
                "ip",
                "domain",
                "domain",
                "file_hash",
                "sha256",
                "tls_fingerprint",
                "tls_fingerprint");
        boolean typed = lookups.keySet().stream().anyMatch(k -> k.startsWith("indicator_"));
        condition =
            "("
                + types.entrySet().stream()
                    .filter(e -> !typed || lookups.containsKey("indicator_" + e.getValue()))
                    .map(
                        e ->
                            "lower("
                                + e.getKey()
                                + ") IN ("
                                + parameters(
                                    sql, typed ? lookups.get("indicator_" + e.getValue()) : values)
                                + ")")
                    .collect(java.util.stream.Collectors.joining(" OR "))
                + ")";
      } else if (name.equals("business_hours")) {
        String[] hours = values.getFirst().split("-");
        if (hours.length != 2) throw new IllegalArgumentException("업무시간은 시작시각-종료시각(예: 9-18)입니다.");
        int start = Integer.parseInt(hours[0]), end = Integer.parseInt(hours[1]);
        if (start < 0 || end > 24 || start >= end)
          throw new IllegalArgumentException("업무시간을 확인하세요.");
        condition +=
            " AND (toDayOfWeek(toTimeZone(observed_at,'Asia/Seoul'))>5 OR toHour(observed_at,'Asia/Seoul')<"
                + start
                + " OR toHour(observed_at,'Asia/Seoul')>="
                + end
                + ")";
      } else if (name.equals("protected_paths"))
        condition +=
            " AND ("
                + values.stream()
                    .map(v -> "startsWith(file_path," + sql.parameter(v, "String") + ")")
                    .collect(java.util.stream.Collectors.joining(" OR "))
                + ")";
      else if (name.equals("internal_networks"))
        condition +=
            " AND ("
                + values.stream()
                    .map(v -> HuntSql.addressInRange("dst_ip", sql.parameter(v, "String")))
                    .collect(java.util.stream.Collectors.joining(" OR "))
                + ")";
      else {
        String field =
            switch (name) {
              case "trusted_scanners" -> "src_ip";
              case "trusted_processes" -> "process_name";
              case "approved_services" -> "toString(dst_port)";
              default -> "dst_ip";
            };
        condition += " AND " + field + " NOT IN (" + parameters(sql, values) + ")";
      }
    }
    String keys = String.join(",", s.keys()), seconds = Integer.toString(s.windowMinutes() * 60);
    long windowMillis = s.windowMinutes() * 60_000L;
    String noveltyKeys =
        keys
            + (Set.of("FANOUT", "NEW_SEQUENCE").contains(s.mode()) && s.value() != null
                ? "," + s.value()
                : "");
    if (sequence)
      scope +=
          " AND "
              + s.keys().stream()
                  .filter(k -> !k.equals("realm"))
                  .map(k -> k + "!=''")
                  .collect(java.util.stream.Collectors.joining(" AND "));
    String base = "WITH data AS (SELECT * FROM " + source + " WHERE " + scope + "), ";
    String filtered = "SELECT * FROM data WHERE " + condition;
    // Newness compares the same entity key to the prior fourteen days, never to the current window.
    if (s.baseline()) {
      var baseline =
          new HuntQuery(
              query.from().minusSeconds(14 * 86400L),
              query.from(),
              null,
              "",
              query.groupBy(),
              "count",
              200,
              null);
      String prior = sql.source(baseline, asOf);
      base += "prior AS (SELECT * FROM " + prior + "), ";
      if (s.mode().equals("NEW")
          || s.mode().equals("NEW_SUM")
          || s.mode().equals("FANOUT")
          || s.mode().equals("NEW_SEQUENCE"))
        filtered +=
            " AND ("
                + noveltyKeys
                + ") NOT IN (SELECT "
                + noveltyKeys
                + " FROM prior WHERE "
                + s.condition()
                + ")";
    }
    String output = episodeOutput(keys, windowMillis, "SELECT * FROM hits WHERE qualified", "");
    if (Set.of("SEQUENCE", "NEW_SEQUENCE", "BURST_SEQUENCE").contains(s.mode())) {
      String first =
          s.mode().equals("NEW_SEQUENCE")
              ? "("
                  + condition
                  + " AND ("
                  + noveltyKeys
                  + ") NOT IN (SELECT "
                  + noveltyKeys
                  + " FROM prior WHERE "
                  + s.condition()
                  + "))"
              : condition;
      if (s.mode().equals("BURST_SEQUENCE")) {
        String failed = first;
        base +=
            "burst AS (SELECT *,countIf("
                + failed
                + ") OVER (PARTITION BY "
                + keys
                + " ORDER BY toUnixTimestamp64Milli(observed_at) RANGE BETWEEN "
                + windowMillis
                + " PRECEDING AND CURRENT ROW) AS failures,minIf(toUnixTimestamp64Milli(observed_at),"
                + failed
                + ") OVER (PARTITION BY "
                + keys
                + " ORDER BY toUnixTimestamp64Milli(observed_at) RANGE BETWEEN "
                + windowMillis
                + " PRECEDING AND CURRENT ROW) AS burst_start_ms FROM data), ";
        first = "(" + first + " AND failures>=" + s.threshold() + ")";
      }
      // Match each later event to an actual earlier stage, preserving the matching episode.
      // Millisecond clocks and a strict ASOF condition cannot reuse one event for two stages.
      String clock = "toUnixTimestamp64Milli(observed_at)";
      base +=
          "seeds AS (SELECT "
              + keys
              + ","
              + clock
              + " AS at_ms,"
              + (s.mode().equals("BURST_SEQUENCE") ? "burst_start_ms" : clock)
              + " AS chain_ms FROM "
              + (s.mode().equals("BURST_SEQUENCE") ? "burst" : "data")
              + " WHERE "
              + first
              + "), ";
      base +=
          matchingStage(
                  "second_matches",
                  s.follow(),
                  "seeds",
                  s.keys(),
                  s.mode().equals("BURST_SEQUENCE") ? 600_000L : windowMillis,
                  "at_ms")
              + ", ";
      String matches = "second_matches";
      if (s.third() != null) {
        base +=
            matchingStage("third_matches", s.third(), matches, s.keys(), windowMillis, "chain_ms")
                + ", ";
        matches = "third_matches";
      }
      return sql.statement(
          base
              + "episodes AS (SELECT "
              + keys
              + ",fromUnixTimestamp64Milli(chain_ms) AS first_seen,fromUnixTimestamp64Milli(max(at_ms)) AS last_seen,"
              + (s.third() == null ? 2 : 3)
              + " AS hits FROM "
              + matches
              + " GROUP BY "
              + keys
              + ",chain_ms) SELECT * FROM episodes ORDER BY last_seen DESC LIMIT 101 FORMAT JSONEachRow");
    }
    String metric =
        switch (s.mode()) {
          case "DISTINCT", "DNS_FAILURE", "FAST_FLUX", "RARE", "FANOUT", "SLOW_SCAN" ->
              "uniqExact(" + (s.value() == null ? "id" : s.value()) + ")";
          case "SUM", "BASELINE_SUM", "NEW_SUM", "ASYMMETRY" -> "sum(" + s.value() + ")";
          default -> "count()";
        };
    String window =
        "PARTITION BY "
            + keys
            + " ORDER BY toUnixTimestamp64Milli(observed_at) RANGE BETWEEN "
            + windowMillis
            + " PRECEDING AND CURRENT ROW";
    String qualification = "hits>=" + s.threshold();
    if (s.mode().equals("DNS_FAILURE")) qualification = "hits>=10 AND events>=" + s.threshold();
    if (s.mode().equals("FAST_FLUX")) qualification += " AND events>=20";
    if (s.id().equals("A02")) qualification += " AND events>=20";
    if (s.id().equals("A03")) qualification += " AND events>=20";
    String extra = ",count() OVER (" + window + ") AS events";
    if (s.mode().equals("ASYMMETRY")) {
      extra += ",sum(bytes_received) OVER (" + window + ") AS inbound";
      qualification += " AND hits>10*greatest(ifNull(inbound,0),1)";
    }
    if (s.mode().equals("FILE_BURST")) {
      metric = "uniqExact(file_path)";
      extra += ",uniqExactIf(file_path,event_type='file.rename') OVER (" + window + ") AS renames";
      qualification += " AND renames>=20";
    }
    if (s.mode().equals("FAST_FLUX")) {
      // Do not count serialized answer arrays or their order as separate IP addresses.
      filtered =
          "SELECT *,arrayFilter(ip -> isIPv4String(ip) OR isIPv6String(ip),arrayMap(answer -> if(JSONType(answer)='String',JSONExtractString(answer),JSONExtractString(answer,'rdata')),JSONExtractArrayRaw(dns_answers))) AS answer_ips FROM ("
              + filtered
              + ")";
      metric = "length(arrayDistinct(arrayFlatten(groupArray(answer_ips) OVER (" + window + "))))";
    }
    if (s.mode().equals("RARE")) {
      qualification =
          "hits>=1 AND ("
              + keys
              + ") NOT IN (SELECT "
              + keys
              + " FROM prior GROUP BY "
              + keys
              + " HAVING uniqExact("
              + s.value()
              + ")>"
              + s.threshold()
              + ") AND ("
              + keys
              + ","
              + s.value()
              + ") NOT IN (SELECT "
              + keys
              + ","
              + s.value()
              + " FROM prior)";
    }
    if (s.mode().equals("BASELINE_COUNT") || s.mode().equals("BASELINE_SUM")) {
      String historical = s.mode().equals("BASELINE_SUM") ? "sum(" + s.value() + ")" : "count()";
      base +=
          "baseline AS (SELECT "
              + keys
              + ",median(amount) AS usual FROM (SELECT "
              + keys
              + ",toStartOfInterval(observed_at,INTERVAL "
              + s.windowMinutes()
              + " MINUTE) AS slot,"
              + historical
              + " AS amount FROM prior WHERE "
              + s.condition()
              + " GROUP BY "
              + keys
              + ",slot) GROUP BY "
              + keys
              + "), ";
      filtered =
          "SELECT current.*,ifNull(b.usual,0) AS baseline_usual FROM ("
              + filtered
              + ") AS current LEFT JOIN baseline AS b USING ("
              + keys
              + ")";
      qualification += " AND hits>=5*baseline_usual";
    }
    if (s.mode().equals("BEACON")) {
      base +=
          "intervals AS (SELECT *,(toUnixTimestamp64Milli(observed_at)-lagInFrame(toNullable(toUnixTimestamp64Milli(observed_at)),1,NULL) OVER (PARTITION BY "
              + keys
              + " ORDER BY observed_at ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING))/1000.0 AS gap FROM ("
              + filtered
              + ")), ";
      return sql.statement(
          base
              + "stats AS (SELECT *,min(observed_at) OVER ("
              + window
              + ") AS window_first,count() OVER ("
              + window
              + ") AS hits,medianIf(gap,gap>0 AND gap<="
              + seconds
              + ") OVER ("
              + window
              + ") AS period,stddevPopIf(gap,gap>0 AND gap<="
              + seconds
              + ") OVER ("
              + window
              + ")/avgIf(gap,gap>0 AND gap<="
              + seconds
              + ") OVER ("
              + window
              + ") AS variation FROM intervals)"
              + episodeOutput(
                  keys,
                  windowMillis,
                  "SELECT * FROM stats WHERE hits>="
                      + s.threshold()
                      + " AND period BETWEEN 10 AND 300 AND variation<=0.2",
                  ",argMax(period,observed_at) AS period,argMax(variation,observed_at) AS variation"));
    }
    if (s.mode().equals("FANOUT")) filtered += " AND bytes_sent>=1000000";
    if (s.mode().equals("SLOW_SCAN")) {
      extra +=
          ",uniqExact("
              + s.value()
              + ") OVER (PARTITION BY "
              + keys
              + " ORDER BY toUnixTimestamp64Milli(observed_at) RANGE BETWEEN 300000 PRECEDING AND CURRENT ROW) AS fast_hosts,uniqExact(dst_port) OVER (PARTITION BY "
              + keys
              + " ORDER BY toUnixTimestamp64Milli(observed_at) RANGE BETWEEN 300000 PRECEDING AND CURRENT ROW) AS fast_ports";
      qualification += " AND fast_hosts<20 AND fast_ports<10";
    }
    return sql.statement(
        base
            + "windowed AS (SELECT *,min(observed_at) OVER ("
            + window
            + ") AS window_first,"
            + (s.mode().equals("FAST_FLUX") ? metric : metric + " OVER (" + window + ")")
            + " AS hits"
            + extra
            + " FROM ("
            + filtered
            + ")), hits AS (SELECT *,"
            + qualification
            + " AS qualified FROM windowed) "
            + output);
  }

  private static String episodeOutput(
      String keys, long windowMillis, String qualified, String extra) {
    String order =
        "PARTITION BY "
            + keys
            + " ORDER BY toUnixTimestamp64Milli(window_first),toUnixTimestamp64Milli(observed_at),id";
    return ", hunt_episode_rows AS (SELECT *,maxOrNull(toUnixTimestamp64Milli(observed_at)) OVER ("
        + order
        + " ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS hunt_episode_previous FROM ("
        + qualified
        + "))"
        + ", hunt_episode_marks AS (SELECT *,if(isNull(hunt_episode_previous) OR toUnixTimestamp64Milli(window_first)>hunt_episode_previous+"
        + windowMillis
        + ",1,0) AS hunt_episode_start FROM hunt_episode_rows)"
        + ", hunt_episode_groups AS (SELECT *,sum(hunt_episode_start) OVER ("
        + order
        + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS hunt_episode_id FROM hunt_episode_marks)"
        + " SELECT "
        + keys
        + ",min(window_first) AS first_seen,max(observed_at) AS last_seen,max(hits) AS hits"
        + extra
        + " FROM hunt_episode_groups GROUP BY "
        + keys
        + ",hunt_episode_id ORDER BY last_seen DESC LIMIT 101 FORMAT JSONEachRow";
  }

  private static String matchingStage(
      String name,
      String condition,
      String previous,
      List<String> keys,
      long windowMillis,
      String origin) {
    String select =
        keys.stream()
            .map(key -> "next." + key + " AS " + key)
            .collect(java.util.stream.Collectors.joining(","));
    String join =
        keys.stream()
            .map(key -> "next." + key + "=seed." + key)
            .collect(java.util.stream.Collectors.joining(" AND "));
    return name
        + " AS (SELECT "
        + select
        + ",seed.chain_ms AS chain_ms,next.at_ms AS at_ms FROM (SELECT "
        + String.join(",", keys)
        + ",toUnixTimestamp64Milli(observed_at) AS at_ms FROM data WHERE "
        + condition
        + ") AS next ASOF INNER JOIN "
        + previous
        + " AS seed ON "
        + join
        + " AND next.at_ms>seed.at_ms WHERE next.at_ms<=seed."
        + origin
        + "+"
        + windowMillis
        + ")";
  }

  private static String parameters(HuntSql sql, List<String> values) {
    if (values.size() > 1000) throw new IllegalArgumentException("룩업은 최대 1000개입니다.");
    return values.stream()
        .map(v -> sql.parameter(v, "String"))
        .collect(java.util.stream.Collectors.joining(","));
  }
}
