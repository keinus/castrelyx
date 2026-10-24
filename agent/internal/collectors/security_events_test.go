package collectors

import (
	"strings"
	"testing"
)

func TestSysmonStructuredFieldsSurviveDisplayMessageTruncation(t *testing.T) {
	row := map[string]any{"Id": float64(1), "RecordId": float64(91), "Message": "localized display text", "Xml": `<Event><System><TimeCreated SystemTime="2026-10-22T01:02:03.000Z"/></System><EventData><Data Name="ProcessGuid">{proc-1}</Data><Data Name="ParentProcessGuid">{parent-1}</Data><Data Name="Image">C:\Windows\powershell.exe</Data><Data Name="CommandLine">powershell -EncodedCommand abcdefghijklmnopqrstuvwxyz</Data><Data Name="User">DOMAIN\alice</Data><Data Name="Hashes">SHA256=ABCDEF</Data></EventData></Event>`}
	item := buildWindowsLogEventItem("Microsoft-Windows-Sysmon/Operational", row, 12)
	payload := item.Payload.(map[string]any)
	if payload["event_type"] != "process.start" || payload["process_id"] != "{proc-1}" || payload["parent_guid"] != "{parent-1}" {
		t.Fatalf("missing process identity: %#v", payload)
	}
	if payload["event_time"] != "2026-10-22T01:02:03.000Z" {
		t.Fatalf("event timestamp was lost: %#v", payload)
	}
	if !strings.Contains(payload["CommandLine"].(string), "abcdefghijklmnopqrstuvwxyz") {
		t.Fatal("structured command was truncated with display message")
	}
	if payload["sha256"] != "abcdef" {
		t.Fatalf("hash not normalized: %#v", payload)
	}
}

func TestWindowsAuthenticationUsesNamedDataWithoutLocalizedMessageParsing(t *testing.T) {
	row := map[string]any{"Id": float64(4624), "RecordId": float64(20), "Message": "인증 이벤트", "Xml": `<Event><EventData><Data Name="TargetUserName">alice</Data><Data Name="TargetDomainName">ACME</Data><Data Name="IpAddress">10.1.1.4</Data><Data Name="TargetLogonId">0x1234</Data></EventData></Event>`}
	payload := buildWindowsLogEventItem("Security", row, 1024).Payload.(map[string]any)
	if payload["actor"] != "alice" || payload["realm"] != "ACME" || payload["src_ip"] != "10.1.1.4" || payload["TargetLogonId"] != "0x1234" {
		t.Fatalf("named fields missing: %#v", payload)
	}
}

func TestSuricataKeepsProtocolPayloadAndCommunityId(t *testing.T) {
	line := `{"timestamp":"2026-10-22T00:00:00Z","event_type":"tls","flow_id":123456789,"community_id":"1:abc","src_ip":"10.0.0.1","dest_ip":"203.0.113.1","proto":"TCP","tls":{"sni":"example.org","ja4":"fingerprint"},"flow":{"bytes_toserver":100,"bytes_toclient":20}}`
	payload := buildLinuxLogEventItem("/var/log/suricata/eve.json", line, 20).Payload.(map[string]any)
	if payload["community_id"] != "1:abc" || payload["ja4"] != "fingerprint" || payload["server_name"] != "example.org" {
		t.Fatalf("protocol fields lost: %#v", payload)
	}
	if _, ok := payload["data"].(map[string]any); !ok {
		t.Fatal("original EVE structure was not preserved")
	}
	if payload["bytes_sent"] != int64(100) {
		t.Fatalf("flow counters lost: %#v", payload)
	}
}

func TestWindowsQueryRequestsEventXml(t *testing.T) {
	command := windowsLogChannelCommand("Security", 20, 100)
	if !strings.Contains(command, "ToXml()") || !strings.Contains(command, "EventRecordID > 20") {
		t.Fatalf("structured incremental query missing: %s", command)
	}
}

func TestWindowsLoginAndTaskUseTheSameNativeLogonIdentity(t *testing.T) {
	login := buildWindowsLogEventItem("Security", map[string]any{"Id": float64(4624), "RecordId": float64(1), "Xml": `<Event><EventData><Data Name="TargetLogonId">0x1234</Data><Data Name="LogonGuid">{00000000-0000-0000-0000-000000000000}</Data></EventData></Event>`}, 1024).Payload.(map[string]any)
	task := buildWindowsLogEventItem("Security", map[string]any{"Id": float64(4698), "RecordId": float64(2), "Xml": `<Event><EventData><Data Name="SubjectLogonId">0x1234</Data></EventData></Event>`}, 1024).Payload.(map[string]any)
	if login["session_id"] != "0x1234" || task["session_id"] != login["session_id"] {
		t.Fatal("native logon identifiers were not preserved for correlation")
	}
}

func TestSysmonProcessAccessKeepsSourceProcessForCredentialHunting(t *testing.T) {
	payload := buildWindowsLogEventItem("Microsoft-Windows-Sysmon/Operational", map[string]any{"Id": float64(10), "RecordId": float64(3), "Xml": `<Event><EventData><Data Name="SourceImage">C:\temp\tool.exe</Data><Data Name="SourceProcessGUID">{source-guid}</Data><Data Name="TargetImage">C:\Windows\System32\lsass.exe</Data><Data Name="GrantedAccess">0x1010</Data></EventData></Event>`}, 1024).Payload.(map[string]any)
	if payload["process_name"] != `C:\temp\tool.exe` || payload["process_id"] != "{source-guid}" || payload["TargetImage"] != `C:\Windows\System32\lsass.exe` {
		t.Fatalf("process access attribution lost: %#v", payload)
	}
}

func TestWindowsAuditClearRequiresTheEventLogProviderOrSecurityChannel(t *testing.T) {
	for _, row := range []struct {
		channel, provider string
		id                int
		cleared           bool
	}{
		{"System", "Microsoft-Windows-Eventlog", 104, true},
		{"Microsoft-Windows-TaskScheduler/Operational", "Microsoft-Windows-TaskScheduler", 104, false},
		{"Security", "Microsoft-Windows-Eventlog", 1102, true},
		{"Application", "OtherApplication", 1102, false},
	} {
		payload := buildWindowsLogEventItem(row.channel, map[string]any{"Id": float64(row.id), "RecordId": float64(123), "ProviderName": row.provider, "Xml": "<Event><EventData/></Event>"}, 1024).Payload.(map[string]any)
		if (payload["event_type"] == "audit.cleared") != row.cleared {
			t.Fatalf("wrong audit classification for %s/%s/%d: %v", row.channel, row.provider, row.id, payload["event_type"])
		}
	}
}

func TestSuricataIdentityPreservesFullFlowIdAndDistinctEventsAfterDisplayTruncation(t *testing.T) {
	base := `{"timestamp":"2026-10-22T00:00:00Z","event_type":"dns","flow_id":18446744073709551614,"src_ip":"10.0.0.1","dns":{"rrname":"DOMAIN","rrtype":"A"}}`
	first := buildLinuxLogEventItem("/var/log/suricata/eve.json", strings.ReplaceAll(base, "DOMAIN", "first.example"), 12).Payload.(map[string]any)
	second := buildLinuxLogEventItem("/var/log/suricata/eve.json", strings.ReplaceAll(base, "DOMAIN", "second.example"), 12).Payload.(map[string]any)
	if first["flow_id"] != "18446744073709551614" {
		t.Fatalf("64 bit flow identity was rounded: %v", first["flow_id"])
	}
	if first["dedup_key"] == second["dedup_key"] {
		t.Fatal("distinct DNS events collapsed after display truncation")
	}
}
