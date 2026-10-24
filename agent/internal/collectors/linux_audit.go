package collectors

import (
	"encoding/hex"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"castrelyx/agent/internal/envelope"
)

var auditHeader = regexp.MustCompile(`\btype=([A-Z0-9_]+)\s+msg=audit\(([0-9]+(?:\.[0-9]+)?):([0-9]+)\)`)
var auditPair = regexp.MustCompile(`\b([A-Za-z0-9_]+)=("(?:\\.|[^"\\])*"|[^\s]+)`)

// Pending records share the durable log cursor's commit boundary. A restart or
// a failed spool enqueue replays both the lines and the assembly state.
type auditGroup struct {
	ID            string   `json:"id"`
	EventTime     string   `json:"event_time"`
	FirstReceived int64    `json:"first_received"`
	Records       []string `json:"records"`
	Bytes         int      `json:"bytes"`
	Truncated     bool     `json:"truncated,omitempty"`
}

func classifyAuditLog(event *normalizedLogEvent, line string) bool {
	if !strings.HasSuffix(strings.ReplaceAll(event.SourceName, "\\", "/"), "/audit/audit.log") {
		return false
	}
	match := auditHeader.FindStringSubmatch(line)
	if match == nil {
		return false
	}
	event.EventType = "audit.record"
	event.EventCategory = "security"
	event.Program = "auditd"
	event.RecordRef = match[2] + ":" + match[3]
	if seconds, err := strconv.ParseFloat(match[2], 64); err == nil {
		event.EventTime = time.Unix(0, int64(seconds*1e9)).UTC().Format(time.RFC3339Nano)
	}
	event.Fields = mergeFields(event.Fields, map[string]any{"_audit_record": line, "audit_id": event.RecordRef, "audit_type": match[1]})
	return true
}

func assembleAuditRecords(items []envelope.Item, state *logCursorState, now time.Time) []envelope.Item {
	if state.Audit == nil {
		state.Audit = map[string]auditGroup{}
	}
	result := make([]envelope.Item, 0, len(items))
	for _, item := range items {
		payload, ok := item.Payload.(map[string]any)
		if !ok {
			result = append(result, item)
			continue
		}
		record, ok := payload["_audit_record"].(string)
		if !ok {
			result = append(result, item)
			continue
		}
		id := fmt.Sprint(payload["audit_id"])
		group, exists := state.Audit[id]
		if !exists {
			if len(state.Audit) >= 32 {
				oldest := ""
				var received int64
				for key, value := range state.Audit {
					if oldest == "" || value.FirstReceived < received {
						oldest = key
						received = value.FirstReceived
					}
				}
				result = append(result, auditItem(state.Audit[oldest], false, "pending_limit"))
				delete(state.Audit, oldest)
			}
			group = auditGroup{ID: id, EventTime: fmt.Sprint(payload["event_time"]), FirstReceived: now.UnixMilli()}
		}
		if group.Bytes+len(record) > 32*1024 || len(group.Records) >= 64 {
			group.Truncated = true
		} else {
			group.Records = append(group.Records, record)
			group.Bytes += len(record)
		}
		state.Audit[id] = group
		if payload["audit_type"] == "EOE" {
			complete := !group.Truncated
			reason := ""
			if !complete {
				reason = "record_limit"
			}
			result = append(result, auditItem(group, complete, reason))
			delete(state.Audit, id)
		}
	}
	for id, group := range state.Audit {
		if now.UnixMilli()-group.FirstReceived >= 30_000 {
			result = append(result, auditItem(group, false, "end_of_event_timeout"))
			delete(state.Audit, id)
		}
	}
	return result
}

func auditItem(group auditGroup, complete bool, reason string) envelope.Item {
	fields := map[string]any{"audit_id": group.ID, "audit_complete": complete, "audit_records": group.Records, "audit_incomplete_reason": reason}
	var syscall, execution map[string]string
	var paths []string
	primaryPath := ""
	configDisabled := false
	for _, record := range group.Records {
		header := auditHeader.FindStringSubmatch(record)
		if header == nil {
			continue
		}
		pairs := map[string]string{}
		for _, match := range auditPair.FindAllStringSubmatch(record, -1) {
			key, value := match[1], match[2]
			if strings.HasPrefix(value, "\"") {
				if decoded, err := strconv.Unquote(value); err == nil {
					value = decoded
				}
			} else if key == "exe" || key == "name" || key == "proctitle" || len(key) > 1 && key[0] == 'a' && key[1] >= '0' && key[1] <= '9' {
				value = auditText(value)
			}
			pairs[key] = value
		}
		switch header[1] {
		case "SYSCALL":
			syscall = pairs
		case "EXECVE":
			if execution == nil {
				execution = map[string]string{}
			}
			for key, value := range pairs {
				execution[key] = value
			}
		case "PATH":
			if name := pairs["name"]; name != "" && name != "(null)" {
				paths = append(paths, name)
				if primaryPath == "" && pairs["nametype"] != "PARENT" {
					primaryPath = name
				}
			}
		case "PROCTITLE":
			fields["command_line"] = strings.ReplaceAll(pairs["proctitle"], "\x00", " ")
		case "CONFIG_CHANGE":
			configDisabled = pairs["enabled"] == "0"
		}
	}
	if complete && execution != nil && syscall == nil {
		complete = false
		fields["audit_complete"] = false
		fields["audit_incomplete_reason"] = "missing_syscall"
	}
	typeName := "audit.activity"
	if !complete {
		typeName = "audit.incomplete"
	} else if configDisabled {
		typeName = "audit.disabled"
	} else if syscall != nil {
		fields["pid"] = syscall["pid"]
		fields["parent_pid"] = syscall["ppid"]
		fields["process_name"] = syscall["exe"]
		fields["audit_key"] = syscall["key"]
		fields["syscall"] = syscall["syscall"]
		actor := syscall["auid"]
		if actor == "" || actor == "4294967295" || actor == "unset" {
			actor = syscall["uid"]
		}
		fields["actor"] = actor
		if syscall["success"] == "yes" {
			fields["outcome"] = "success"
			if execution != nil {
				typeName = "process.start"
				fields["process_id"] = "audit:" + hashText(group.ID)
				if fields["command_line"] == nil {
					argc, err := strconv.Atoi(execution["argc"])
					if err != nil || argc > 256 {
						argc = 0
						typeName = "audit.incomplete"
						fields["audit_complete"] = false
					}
					args := []string{}
					for i := 0; i < argc; i++ {
						argument, ok := execution[fmt.Sprintf("a%d", i)]
						if !ok {
							typeName = "audit.incomplete"
							fields["audit_complete"] = false
						}
						args = append(args, argument)
					}
					fields["command_line"] = strings.Join(args, " ")
				}
			} else if strings.Contains(syscall["key"], "castrelyx_persistence") && len(paths) > 0 {
				typeName = "file.modify"
				fields["file_path"] = paths[0]
				fields["action"] = "write_or_attribute"
			} else if strings.Contains(syscall["key"], "castrelyx_credentials") && len(paths) > 0 {
				typeName = "file.read"
				fields["file_path"] = paths[0]
				fields["action"] = "read"
			}
		} else {
			fields["outcome"] = "failure"
		}
	}
	fields["file_paths"] = paths
	if primaryPath != "" && (typeName == "file.modify" || typeName == "file.read") {
		fields["file_path"] = primaryPath
	}
	event := normalizedLogEvent{Platform: "linux", SourceName: "/var/log/audit/audit.log", Channel: "audit", Program: "auditd", EventType: typeName, EventCategory: "security", EventTime: group.EventTime, RecordRef: group.ID, Severity: "INFO", Message: strings.Join(group.Records, "\n"), Fields: fields}
	if actor, ok := fields["actor"].(string); ok {
		event.Actor = actor
	}
	if outcome, ok := fields["outcome"].(string); ok {
		event.Outcome = outcome
	}
	if action, ok := fields["action"].(string); ok {
		event.Action = action
	}
	return buildNormalizedLogEventItem(event, defaultLogMessageMaxBytes)
}

func auditText(value string) string {
	// Audit encodes unsafe argument bytes as even-length hex strings.
	if len(value) >= 2 && len(value)%2 == 0 {
		if decoded, err := hex.DecodeString(value); err == nil {
			return string(decoded)
		}
	}
	return value
}
