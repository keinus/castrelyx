package collectors

import (
	"castrelyx/agent/internal/envelope"
	"strings"
	"testing"
	"time"
)

func auditRecord(line string) envelope.Item {
	return buildLinuxLogEventItem("/var/log/audit/audit.log", line, 12)
}
func TestAuditAssemblySurvivesCursorCloneAndDoesNotEmitPartialExecution(t *testing.T) {
	now := time.Now()
	state := newLogCursorState()
	first := []envelope.Item{auditRecord(`type=SYSCALL msg=audit(1792627200.123:42): arch=c000003e syscall=59 success=yes pid=80 ppid=70 auid=1000 uid=1000 exe="/bin/bash" key="castrelyx_exec"`)}
	if items := assembleAuditRecords(first, state, now); len(items) != 0 {
		t.Fatal("emitted execution before EOE")
	}
	clone := cloneLogCursorState(state)
	second := []envelope.Item{auditRecord(`type=EXECVE msg=audit(1792627200.123:42): argc=3 a0="bash" a1="-c" a2=6563686F2061626364`), auditRecord(`type=EOE msg=audit(1792627200.123:42):`)}
	items := assembleAuditRecords(second, clone, now.Add(time.Second))
	if len(items) != 1 {
		t.Fatalf("events=%d", len(items))
	}
	payload := items[0].Payload.(map[string]any)
	if payload["event_type"] != "process.start" || payload["command_line"] != "bash -c echo abcd" || payload["actor"] != "1000" || payload["process_name"] != "/bin/bash" {
		t.Fatalf("missing assembly: %#v", payload)
	}
	if len(state.Audit) != 1 || len(clone.Audit) != 0 {
		t.Fatal("pending cursor state was aliased or not consumed")
	}
}

func TestAuditTimeoutIsExplicitAndLiteralArgumentsAreNotDecodedAsHex(t *testing.T) {
	now := time.Now()
	state := newLogCursorState()
	assembleAuditRecords([]envelope.Item{auditRecord(`type=EXECVE msg=audit(1792627200.123:1): argc=1 a0="abcd"`)}, state, now)
	items := assembleAuditRecords(nil, state, now.Add(31*time.Second))
	payload := items[0].Payload.(map[string]any)
	if payload["event_type"] != "audit.incomplete" || payload["audit_complete"] != false {
		t.Fatal("incomplete record became complete")
	}
	state = newLogCursorState()
	items = assembleAuditRecords([]envelope.Item{auditRecord(`type=SYSCALL msg=audit(1792627200.123:2): success=yes pid=80 uid=0 exe="/bin/test"`), auditRecord(`type=EXECVE msg=audit(1792627200.123:2): argc=1 a0="abcd"`), auditRecord(`type=EOE msg=audit(1792627200.123:2):`)}, state, now)
	if command := items[0].Payload.(map[string]any)["command_line"]; command != "abcd" {
		t.Fatalf("literal argument was decoded: %v", command)
	}
}

func TestAuditBufferIsBoundedAndOversizedGroupNeverBecomesProcessStart(t *testing.T) {
	now := time.Now()
	state := newLogCursorState()
	items := []envelope.Item{auditRecord(`type=SYSCALL msg=audit(1792627200.123:2): success=yes exe="/bin/bash"`), auditRecord(`type=EXECVE msg=audit(1792627200.123:2): argc=1 a0="` + strings.Repeat("x", 40*1024) + `"`), auditRecord(`type=EOE msg=audit(1792627200.123:2):`)}
	result := assembleAuditRecords(items, state, now)
	if len(state.Audit) != 0 || result[0].Payload.(map[string]any)["event_type"] != "audit.incomplete" {
		t.Fatal("oversized execution silently accepted")
	}
}

func TestAuditInitialTailStartingMidEventIsMarkedIncomplete(t *testing.T) {
	items := assembleAuditRecords([]envelope.Item{auditRecord(`type=EXECVE msg=audit(1792627200.123:9): argc=1 a0="bash"`), auditRecord(`type=EOE msg=audit(1792627200.123:9):`)}, newLogCursorState(), time.Now())
	payload := items[0].Payload.(map[string]any)
	if payload["event_type"] != "audit.incomplete" || payload["audit_incomplete_reason"] != "missing_syscall" {
		t.Fatal("partial initial tail was presented as a complete execution")
	}
}

func TestAuditFileWatchUsesTheObjectPathInsteadOfItsParentDirectory(t *testing.T) {
	items := assembleAuditRecords([]envelope.Item{auditRecord(`type=SYSCALL msg=audit(1792627200.123:19): success=yes pid=90 uid=0 exe="/bin/editor" key="castrelyx_persistence"`), auditRecord(`type=PATH msg=audit(1792627200.123:19): item=0 name="/etc/ssh" nametype=PARENT`), auditRecord(`type=PATH msg=audit(1792627200.123:19): item=1 name="/etc/ssh/sshd_config" nametype=NORMAL`), auditRecord(`type=EOE msg=audit(1792627200.123:19):`)}, newLogCursorState(), time.Now())
	payload := items[0].Payload.(map[string]any)
	if payload["event_type"] != "file.modify" || payload["file_path"] != "/etc/ssh/sshd_config" {
		t.Fatalf("file watch attributed to a parent path: %#v", payload)
	}
}
