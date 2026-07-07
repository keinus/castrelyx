package collectors

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	slashpath "path"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	"castrelyx/agent/internal/envelope"
)

const (
	defaultLogMessageMaxBytes       = 1024
	initialFileTailLines            = 50
	initialJournalTailLines         = 50
	initialWindowsChannelEvents     = 20
	incrementalFileTailMaxLines     = 200
	incrementalJournalTailMaxEvents = 200
	incrementalWindowsChannelEvents = 100
	tailReadWindowBytes             = 64 * 1024
)

var (
	syslogLinePattern       = regexp.MustCompile(`^([A-Z][a-z]{2}\s+\d{1,2}\s+\d\d:\d\d:\d\d)\s+(\S+)\s+([^:\[]+)(?:\[(\d+)\])?:\s?(.*)$`)
	secretAssignmentPattern = regexp.MustCompile(`(?i)\b(password|passwd|pwd|token|api[_-]?key|authorization|secret|credential)\b\s*[:=]\s*[^,\s;]+`)
	bearerTokenPattern      = regexp.MustCompile(`(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+`)
	userForPattern          = regexp.MustCompile(`\bfor (?:invalid user )?([A-Za-z0-9._@+-]+)`)
	userFieldPattern        = regexp.MustCompile(`\bUSER=([A-Za-z0-9._@+-]+)`)
)

type logTailerCollector struct {
	options Options
}

type logCursorState struct {
	Files          map[string]fileLogCursor `json:"files,omitempty"`
	JournaldCursor string                   `json:"journald_cursor,omitempty"`
	Windows        map[string]uint64        `json:"windows,omitempty"`
}

type fileLogCursor struct {
	FileID string `json:"file_id"`
	Offset int64  `json:"offset"`
}

type normalizedLogEvent struct {
	Platform      string
	SourceName    string
	Channel       string
	Program       string
	Provider      string
	PID           string
	EventID       int
	RecordID      uint64
	EventTime     string
	EventType     string
	EventCategory string
	Severity      string
	Actor         string
	Action        string
	Outcome       string
	Message       string
	RecordRef     string
	Fields        map[string]any
}

type linuxLogLine struct {
	EventTime string
	Hostname  string
	Program   string
	PID       string
	Message   string
}

func newLogTailerCollector(options Options) logTailerCollector {
	if options.LogMessageMaxBytes <= 0 {
		options.LogMessageMaxBytes = defaultLogMessageMaxBytes
	}
	return logTailerCollector{options: options}
}

func (logTailerCollector) Name() string { return "log_tailer" }

func (c logTailerCollector) Collect(context.Context) ([]envelope.Item, error) {
	state := loadLogCursorState(c.options.LogCursorPath)
	items := collectPlatformLogEvents(c.options, state)
	_ = saveLogCursorState(c.options.LogCursorPath, state)
	return items, nil
}

func collectPlatformLogEvents(options Options, state *logCursorState) []envelope.Item {
	if state == nil {
		state = newLogCursorState()
	}
	switch runtime.GOOS {
	case "linux":
		return collectLinuxLogEvents(options, state)
	case "windows":
		return collectWindowsLogEvents(options, state)
	default:
		return nil
	}
}

func collectLinuxLogEvents(options Options, state *logCursorState) []envelope.Item {
	items := []envelope.Item{}
	for _, source := range linuxFileLogSources() {
		items = append(items, collectLinuxFileLogSource(source, options, state)...)
	}
	items = append(items, collectLinuxJournalEvents(options, state)...)
	return items
}

func linuxFileLogSources() []string {
	sources := []string{
		"/var/log/auth.log",
		"/var/log/secure",
		"/var/log/syslog",
		"/var/log/messages",
		"/var/log/suricata/eve.json",
		"/var/log/suricata/fast.log",
		"/var/log/suricata/suricata.log",
	}
	zeekDirs := []string{
		"/var/log/zeek/current",
		"/opt/zeek/logs/current",
		"/usr/local/zeek/logs/current",
		"/var/log/bro/current",
	}
	zeekLogs := []string{
		"conn.log",
		"notice.log",
		"dns.log",
		"http.log",
		"ssl.log",
		"weird.log",
	}
	for _, dir := range zeekDirs {
		for _, name := range zeekLogs {
			sources = append(sources, slashpath.Join(dir, name))
		}
	}
	return dedupeStrings(sources)
}

func collectLinuxFileLogSource(path string, options Options, state *logCursorState) []envelope.Item {
	if state.Files == nil {
		state.Files = map[string]fileLogCursor{}
	}
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return nil
	}

	cursor := state.Files[path]
	fileID := fileIdentity(stat)
	startOffset := cursor.Offset
	initialTail := cursor.FileID == "" || cursor.FileID != fileID || cursor.Offset > stat.Size()
	if initialTail {
		startOffset = stat.Size() - tailReadWindowBytes
		if startOffset < 0 {
			startOffset = 0
		}
	}
	if _, err := file.Seek(startOffset, io.SeekStart); err != nil {
		return nil
	}
	data, err := io.ReadAll(file)
	if err != nil {
		return nil
	}

	lines := splitLogLines(string(data))
	if startOffset > 0 && initialTail && len(lines) > 0 {
		lines = lines[1:]
	}
	if initialTail && len(lines) > initialFileTailLines {
		lines = lines[len(lines)-initialFileTailLines:]
	}
	if !initialTail && len(lines) > incrementalFileTailMaxLines {
		lines = lines[len(lines)-incrementalFileTailMaxLines:]
	}

	state.Files[path] = fileLogCursor{FileID: fileID, Offset: stat.Size()}
	items := make([]envelope.Item, 0, len(lines))
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || shouldSkipLinuxFileLogLine(path, line) {
			continue
		}
		items = append(items, buildLinuxLogEventItem(path, line, options.LogMessageMaxBytes))
	}
	return items
}

func collectLinuxJournalEvents(options Options, state *logCursorState) []envelope.Item {
	args := []string{"--no-pager", "--output", "json"}
	if state.JournaldCursor == "" {
		args = append(args, "-n", strconv.Itoa(initialJournalTailLines))
	} else {
		args = append(args, "--after-cursor", state.JournaldCursor, "-n", strconv.Itoa(incrementalJournalTailMaxEvents))
	}
	out, err := runCommand("journalctl", args...)
	if err != nil || strings.TrimSpace(out) == "" {
		return nil
	}

	items := []envelope.Item{}
	scanner := bufio.NewScanner(strings.NewReader(out))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		row := map[string]any{}
		if err := json.Unmarshal(scanner.Bytes(), &row); err != nil {
			continue
		}
		message := jsonString(row, "MESSAGE")
		cursor := jsonString(row, "__CURSOR")
		if cursor != "" {
			state.JournaldCursor = cursor
		}
		if strings.TrimSpace(message) == "" {
			continue
		}
		event := normalizedLogEvent{
			Platform:      "linux",
			SourceName:    "journald",
			Channel:       "journald",
			Program:       firstNonBlank(jsonString(row, "SYSLOG_IDENTIFIER"), jsonString(row, "_COMM"), jsonString(row, "_SYSTEMD_UNIT")),
			PID:           jsonString(row, "_PID"),
			EventTime:     journalTimestamp(row),
			Severity:      severityFromJournalPriority(jsonString(row, "PRIORITY")),
			Message:       message,
			RecordRef:     cursor,
			EventCategory: "system",
			EventType:     "system.event",
			Outcome:       "unknown",
		}
		classifyLinuxLog(&event)
		items = append(items, buildNormalizedLogEventItem(event, options.LogMessageMaxBytes))
	}
	return items
}

func collectWindowsLogEvents(options Options, state *logCursorState) []envelope.Item {
	channels := []string{
		"Security",
		"System",
		"Application",
		"Windows PowerShell",
		"Microsoft-Windows-PowerShell/Operational",
		"Microsoft-Windows-TerminalServices-LocalSessionManager/Operational",
		"Microsoft-Windows-Windows Defender/Operational",
	}
	items := []envelope.Item{}
	if state.Windows == nil {
		state.Windows = map[string]uint64{}
	}
	for _, channel := range channels {
		items = append(items, collectWindowsChannelEvents(channel, options, state)...)
	}
	return items
}

func collectWindowsChannelEvents(channel string, options Options, state *logCursorState) []envelope.Item {
	lastRecordID := state.Windows[channel]
	maxEvents := initialWindowsChannelEvents
	if lastRecordID > 0 {
		maxEvents = incrementalWindowsChannelEvents
	}
	command := fmt.Sprintf(
		`Get-WinEvent -LogName %s -MaxEvents %d -ErrorAction SilentlyContinue | Select-Object TimeCreated,Id,RecordId,ProviderName,LevelDisplayName,Message | ConvertTo-Json -Compress`,
		powerShellString(channel),
		maxEvents,
	)
	out, err := runPowerShell(command)
	if err != nil || strings.TrimSpace(out) == "" {
		return nil
	}
	rows := decodeJSONObjects(out)
	sort.Slice(rows, func(i, j int) bool {
		return jsonUint64(rows[i], "RecordId") < jsonUint64(rows[j], "RecordId")
	})

	items := []envelope.Item{}
	maxRecordID := lastRecordID
	for _, row := range rows {
		recordID := jsonUint64(row, "RecordId")
		if recordID > 0 && recordID <= lastRecordID {
			continue
		}
		message := jsonString(row, "Message")
		if strings.TrimSpace(message) == "" {
			if recordID > maxRecordID {
				maxRecordID = recordID
			}
			continue
		}
		item := buildWindowsLogEventItem(channel, row, options.LogMessageMaxBytes)
		items = append(items, item)
		if recordID > maxRecordID {
			maxRecordID = recordID
		}
	}
	if maxRecordID > lastRecordID {
		state.Windows[channel] = maxRecordID
	}
	return items
}

func buildLogEventItem(platform, sourceName, message string) envelope.Item {
	if platform == "windows" {
		return buildNormalizedLogEventItem(normalizedLogEvent{
			Platform:      "windows",
			SourceName:    sourceName,
			Channel:       sourceName,
			EventType:     "system.event",
			EventCategory: "system",
			Severity:      "INFO",
			Outcome:       "unknown",
			Message:       message,
		}, defaultLogMessageMaxBytes)
	}
	return buildLinuxLogEventItem(sourceName, message, defaultLogMessageMaxBytes)
}

func buildLinuxLogEventItem(sourceName, line string, maxMessageBytes int) envelope.Item {
	parsed := parseLinuxLogLine(line)
	event := normalizedLogEvent{
		Platform:      "linux",
		SourceName:    sourceName,
		Channel:       linuxLogChannel(sourceName),
		Program:       parsed.Program,
		PID:           parsed.PID,
		EventTime:     parsed.EventTime,
		EventType:     "system.event",
		EventCategory: "system",
		Severity:      "INFO",
		Outcome:       "unknown",
		Message:       parsed.Message,
	}
	if event.Message == "" {
		event.Message = line
	}
	if !classifySuricataLog(&event, line) && !classifyZeekLog(&event, line) {
		classifyLinuxLog(&event)
	}
	return buildNormalizedLogEventItem(event, maxMessageBytes)
}

func buildWindowsLogEventItem(channel string, row map[string]any, maxMessageBytes int) envelope.Item {
	recordID := jsonUint64(row, "RecordId")
	eventID := jsonInt(row, "Id")
	event := normalizedLogEvent{
		Platform:      "windows",
		SourceName:    channel,
		Channel:       channel,
		Provider:      jsonString(row, "ProviderName"),
		EventID:       eventID,
		RecordID:      recordID,
		EventTime:     jsonString(row, "TimeCreated"),
		EventType:     "system.event",
		EventCategory: "system",
		Severity:      severityFromWindowsLevel(jsonString(row, "LevelDisplayName")),
		Outcome:       "unknown",
		Message:       jsonString(row, "Message"),
	}
	if recordID > 0 {
		event.RecordRef = strconv.FormatUint(recordID, 10)
	}
	classifyWindowsLog(&event)
	return buildNormalizedLogEventItem(event, maxMessageBytes)
}

func buildNormalizedLogEventItem(event normalizedLogEvent, maxMessageBytes int) envelope.Item {
	if maxMessageBytes <= 0 {
		maxMessageBytes = defaultLogMessageMaxBytes
	}
	message := limitStringBytes(scrubLogMessage(event.Message), maxMessageBytes)
	dedup := hashText(strings.Join([]string{
		event.Platform,
		event.SourceName,
		event.EventTime,
		event.RecordRef,
		message,
	}, "\x00"))
	payload := map[string]any{
		"event_type":     firstNonBlank(event.EventType, "system.event"),
		"event_category": firstNonBlank(event.EventCategory, "system"),
		"platform":       event.Platform,
		"source":         "agent",
		"source_name":    event.SourceName,
		"channel":        nullIfEmpty(event.Channel),
		"program":        nullIfEmpty(event.Program),
		"provider":       nullIfEmpty(event.Provider),
		"pid":            nullIfEmpty(event.PID),
		"event_id":       nil,
		"record_id":      nil,
		"event_time":     nullIfEmpty(event.EventTime),
		"observed_at":    time.Now().UTC().Format(time.RFC3339Nano),
		"actor":          nullIfEmpty(event.Actor),
		"action":         nullIfEmpty(event.Action),
		"outcome":        firstNonBlank(event.Outcome, "unknown"),
		"severity":       firstNonBlank(event.Severity, "INFO"),
		"message":        message,
		"raw_ref":        nil,
		"dedup_key":      dedup,
	}
	if event.EventID != 0 {
		payload["event_id"] = event.EventID
	}
	if event.RecordID != 0 {
		payload["record_id"] = event.RecordID
	}
	for key, value := range event.Fields {
		if key == "" || value == nil {
			continue
		}
		if _, exists := payload[key]; exists {
			continue
		}
		payload[key] = value
	}
	return envelope.Item{
		Kind:    "event",
		Type:    "log",
		Key:     event.SourceName + ":" + dedup[:16],
		Payload: payload,
	}
}

func classifyLinuxLog(event *normalizedLogEvent) {
	text := strings.ToLower(event.Message)
	program := strings.ToLower(event.Program)
	channel := strings.ToLower(event.Channel)
	authSource := channel == "auth" || strings.Contains(event.SourceName, "auth") || strings.Contains(event.SourceName, "secure")
	if authSource {
		event.EventCategory = "auth"
		event.EventType = "auth.event"
	}
	if strings.Contains(text, "pam_") || strings.Contains(text, "pam_unix") {
		event.EventCategory = "auth"
		event.Action = "pam"
		switch {
		case strings.Contains(text, "session opened"):
			event.EventType = "pam.session.open"
			event.Action = "session.open"
			event.Outcome = "success"
		case strings.Contains(text, "session closed"):
			event.EventType = "pam.session.close"
			event.Action = "session.close"
			event.Outcome = "success"
		case strings.Contains(text, "authentication failure") || strings.Contains(text, "auth could not identify password"):
			event.EventType = "pam.authentication.failure"
			event.Action = "authenticate"
			event.Outcome = "failure"
			event.Severity = "WARNING"
		default:
			event.EventType = "pam.event"
		}
	}
	if strings.Contains(program, "sshd") || strings.Contains(text, "sshd") {
		event.EventCategory = "auth"
		switch {
		case strings.Contains(text, "failed password") || strings.Contains(text, "invalid user") || strings.Contains(text, "authentication failure"):
			event.EventType = "auth.login.failure"
			event.Action = "login"
			event.Outcome = "failure"
			event.Severity = "WARNING"
		case strings.Contains(text, "accepted password") || strings.Contains(text, "accepted publickey") || strings.Contains(text, "accepted keyboard-interactive"):
			event.EventType = "auth.login.success"
			event.Action = "login"
			event.Outcome = "success"
		}
	}
	if strings.Contains(program, "sudo") || strings.Contains(text, "sudo:") {
		event.EventCategory = "auth"
		event.EventType = "auth.privilege.sudo"
		event.Action = "privilege.sudo"
		if strings.Contains(text, "authentication failure") || strings.Contains(text, "incorrect password") {
			event.Outcome = "failure"
			event.Severity = "WARNING"
		} else if strings.Contains(text, "command=") || strings.Contains(text, "pam_unix(sudo:session): session opened") {
			event.Outcome = "success"
		}
	}
	if strings.Contains(program, "systemd") || strings.Contains(event.SourceName, "journald") {
		if strings.Contains(text, "failed") && (strings.Contains(text, ".service") || strings.Contains(text, "unit")) {
			event.EventCategory = "system"
			event.EventType = "system.service.failure"
			event.Action = "service.failure"
			event.Outcome = "failure"
			event.Severity = "ERROR"
		}
	}
	if strings.Contains(program, "kernel") {
		event.EventCategory = "system"
		event.EventType = "system.kernel"
	}
	if event.Actor == "" {
		event.Actor = extractActor(event.Message)
	}
}

func classifySuricataLog(event *normalizedLogEvent, line string) bool {
	if !isSuricataLogSource(event.SourceName) {
		return false
	}
	event.Channel = "suricata"
	event.Provider = "suricata"
	event.Program = firstNonBlank(event.Program, "suricata")
	event.EventCategory = "security"
	event.EventType = "suricata.event"
	event.Action = "observe"
	event.Outcome = "unknown"
	event.Severity = firstNonBlank(event.Severity, "INFO")
	event.Fields = mergeFields(event.Fields, map[string]any{
		"sensor":     "suricata",
		"log_family": "ids",
	})

	row := map[string]any{}
	if strings.HasSuffix(strings.ToLower(filepath.Base(event.SourceName)), ".json") && json.Unmarshal([]byte(line), &row) == nil {
		eventType := jsonString(row, "event_type")
		event.EventType = "suricata." + firstNonBlank(eventType, "event")
		event.EventTime = firstNonBlank(jsonString(row, "timestamp"), event.EventTime)
		event.RecordRef = firstNonBlank(jsonString(row, "flow_id"), event.RecordRef)
		fields := map[string]any{
			"suricata_event_type": eventType,
			"src_ip":              jsonString(row, "src_ip"),
			"src_port":            jsonNumberValue(row, "src_port"),
			"dest_ip":             jsonString(row, "dest_ip"),
			"dest_port":           jsonNumberValue(row, "dest_port"),
			"proto":               jsonString(row, "proto"),
			"flow_id":             jsonString(row, "flow_id"),
			"app_proto":           jsonString(row, "app_proto"),
		}
		if alert, ok := jsonObject(row, "alert"); ok {
			signature := jsonString(alert, "signature")
			severityValue := jsonNumberValue(alert, "severity")
			event.EventType = "suricata.alert"
			event.Action = "detect"
			event.Outcome = "detected"
			event.Severity = suricataSeverity(severityValue)
			event.Message = suricataMessage(row, signature)
			fields["signature"] = signature
			fields["signature_id"] = jsonNumberValue(alert, "signature_id")
			fields["signature_category"] = jsonString(alert, "category")
			fields["signature_severity"] = severityValue
		} else if event.Message == "" || event.Message == line {
			event.Message = suricataMessage(row, eventType)
		}
		event.Fields = mergeFields(event.Fields, fields)
		return true
	}

	base := strings.ToLower(filepath.Base(event.SourceName))
	if strings.Contains(base, "fast") {
		event.EventType = "suricata.alert"
		event.Action = "detect"
		event.Outcome = "detected"
		event.Severity = "WARNING"
	} else {
		event.EventType = "suricata.log"
	}
	event.Message = line
	return true
}

func classifyZeekLog(event *normalizedLogEvent, line string) bool {
	if !isZeekLogSource(event.SourceName) {
		return false
	}
	event.Channel = "zeek"
	event.Provider = "zeek"
	event.Program = firstNonBlank(event.Program, "zeek")
	event.EventCategory = "security"
	event.EventType = "zeek.event"
	event.Action = "observe"
	event.Outcome = "observed"
	event.Severity = "INFO"

	logType := zeekLogType(event.SourceName)
	fields := parseZeekLogFields(logType, line)
	if len(fields) == 0 {
		event.EventType = "zeek." + firstNonBlank(logType, "log")
		event.Message = limitStringBytes(line, defaultLogMessageMaxBytes)
		event.Fields = mergeFields(event.Fields, map[string]any{
			"sensor":        "zeek",
			"log_family":    "network-security",
			"zeek_log_type": logType,
		})
		return true
	}

	event.EventType = "zeek." + logType
	event.EventTime = zeekTimestamp(fields["ts"])
	event.RecordRef = firstNonBlank(fields["uid"], event.RecordRef)
	event.Message = zeekMessage(logType, fields)
	if logType == "notice" || logType == "weird" {
		event.Severity = "WARNING"
	}
	if logType == "notice" {
		event.Action = "notice"
	}
	event.Fields = mergeFields(event.Fields, zeekPayloadFields(logType, fields))
	return true
}

func classifyWindowsLog(event *normalizedLogEvent) {
	channel := strings.ToLower(event.Channel)
	switch event.EventID {
	case 4624:
		event.EventCategory = "auth"
		event.EventType = "auth.login.success"
		event.Action = "login"
		event.Outcome = "success"
	case 4625:
		event.EventCategory = "auth"
		event.EventType = "auth.login.failure"
		event.Action = "login"
		event.Outcome = "failure"
		event.Severity = "WARNING"
	case 4634, 4647:
		event.EventCategory = "auth"
		event.EventType = "auth.logout"
		event.Action = "logout"
		event.Outcome = "success"
	case 4672:
		event.EventCategory = "auth"
		event.EventType = "auth.privilege.assigned"
		event.Action = "privilege.assigned"
		event.Outcome = "success"
	case 4720, 4726, 4732, 4733:
		event.EventCategory = "auth"
		event.EventType = "auth.account.change"
		event.Action = "account.change"
	case 4740:
		event.EventCategory = "auth"
		event.EventType = "auth.account.lockout"
		event.Action = "account.lockout"
		event.Outcome = "failure"
		event.Severity = "WARNING"
	case 7000, 7001, 7023, 7024, 7031, 7034:
		event.EventCategory = "system"
		event.EventType = "system.service.failure"
		event.Action = "service.failure"
		event.Outcome = "failure"
		event.Severity = "ERROR"
	case 6005, 6006, 6008, 1074:
		event.EventCategory = "system"
		event.EventType = "system.reboot"
		event.Action = "reboot"
	}
	if strings.Contains(channel, "powershell") {
		event.EventCategory = "security"
		event.EventType = "security.powershell"
	}
	if strings.Contains(channel, "defender") {
		event.EventCategory = "security"
		event.EventType = "security.defender"
	}
	if event.EventType == "system.event" && channel == "security" {
		event.EventCategory = "auth"
		event.EventType = "auth.event"
	}
	if event.Actor == "" {
		event.Actor = extractActor(event.Message)
	}
}

func shouldSkipLinuxFileLogLine(sourceName, line string) bool {
	return isZeekLogSource(sourceName) && strings.HasPrefix(line, "#")
}

func isSuricataLogSource(sourceName string) bool {
	normalized := strings.ToLower(filepath.ToSlash(sourceName))
	return strings.Contains(normalized, "/suricata/")
}

func isZeekLogSource(sourceName string) bool {
	normalized := strings.ToLower(filepath.ToSlash(sourceName))
	return strings.Contains(normalized, "/zeek/logs/current/") ||
		strings.Contains(normalized, "/zeek/current/") ||
		strings.Contains(normalized, "/bro/current/")
}

func suricataSeverity(value any) string {
	switch v := value.(type) {
	case float64:
		return suricataSeverityNumber(int(v))
	case int:
		return suricataSeverityNumber(v)
	case int64:
		return suricataSeverityNumber(int(v))
	case string:
		n, err := strconv.Atoi(strings.TrimSpace(v))
		if err == nil {
			return suricataSeverityNumber(n)
		}
	}
	return "INFO"
}

func suricataSeverityNumber(value int) string {
	switch {
	case value <= 0:
		return "INFO"
	case value == 1:
		return "ERROR"
	case value == 2:
		return "WARNING"
	default:
		return "INFO"
	}
}

func suricataMessage(row map[string]any, fallback string) string {
	eventType := firstNonBlank(jsonString(row, "event_type"), fallback, "event")
	signature := ""
	if alert, ok := jsonObject(row, "alert"); ok {
		signature = jsonString(alert, "signature")
	}
	left := endpointLabel(jsonString(row, "src_ip"), jsonNumberValue(row, "src_port"))
	right := endpointLabel(jsonString(row, "dest_ip"), jsonNumberValue(row, "dest_port"))
	proto := jsonString(row, "proto")
	parts := []string{"suricata", eventType}
	if signature != "" {
		parts = append(parts, signature)
	}
	if left != "" || right != "" {
		flow := strings.TrimSpace(left + " -> " + right)
		if proto != "" {
			flow += " (" + proto + ")"
		}
		parts = append(parts, flow)
	}
	return strings.Join(nonEmptyStrings(parts), " ")
}

func zeekLogType(sourceName string) string {
	base := strings.ToLower(filepath.Base(sourceName))
	return strings.TrimSuffix(base, ".log")
}

func parseZeekLogFields(logType, line string) map[string]string {
	names := zeekKnownFields(logType)
	if len(names) == 0 {
		return nil
	}
	parts := strings.Split(line, "\t")
	if len(parts) < len(names) {
		spaceParts := strings.Fields(line)
		if len(spaceParts) >= len(names) {
			parts = spaceParts
		}
	}
	if len(parts) == 0 {
		return nil
	}
	fields := make(map[string]string, len(names))
	for i, name := range names {
		if i >= len(parts) {
			break
		}
		value := strings.TrimSpace(parts[i])
		if value == "-" || value == "(empty)" {
			value = ""
		}
		fields[name] = value
	}
	return fields
}

func zeekKnownFields(logType string) []string {
	switch logType {
	case "conn":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "proto", "service", "duration", "orig_bytes", "resp_bytes", "conn_state"}
	case "notice":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "proto", "note", "msg", "sub", "src", "dst", "p", "n", "peer_descr", "actions", "suppress_for", "dropped"}
	case "dns":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "proto", "trans_id", "rtt", "query", "qclass", "qclass_name", "qtype", "qtype_name", "rcode", "rcode_name"}
	case "http":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "trans_depth", "method", "host", "uri", "referrer", "version", "user_agent", "origin", "request_body_len", "response_body_len", "status_code", "status_msg"}
	case "ssl":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "version", "cipher", "curve", "server_name", "resumed", "last_alert", "next_protocol", "established", "cert_chain_fuids", "client_cert_chain_fuids", "subject", "issuer", "client_subject", "client_issuer", "validation_status"}
	case "weird":
		return []string{"ts", "uid", "id.orig_h", "id.orig_p", "id.resp_h", "id.resp_p", "name", "addl", "notice", "peer"}
	default:
		return nil
	}
}

func zeekTimestamp(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	seconds, err := strconv.ParseFloat(value, 64)
	if err != nil {
		return ""
	}
	whole, frac := math.Modf(seconds)
	return time.Unix(int64(whole), int64(frac*1_000_000_000)).UTC().Format(time.RFC3339Nano)
}

func zeekMessage(logType string, fields map[string]string) string {
	src := endpointLabel(fields["id.orig_h"], fields["id.orig_p"])
	dst := endpointLabel(fields["id.resp_h"], fields["id.resp_p"])
	switch logType {
	case "conn":
		return strings.TrimSpace(fmt.Sprintf("zeek conn %s %s -> %s service=%s state=%s",
			fields["proto"], src, dst, fields["service"], fields["conn_state"]))
	case "notice":
		return strings.TrimSpace(fmt.Sprintf("zeek notice %s %s", fields["note"], firstNonBlank(fields["msg"], fields["sub"])))
	case "dns":
		return strings.TrimSpace(fmt.Sprintf("zeek dns %s rcode=%s %s -> %s",
			fields["query"], fields["rcode_name"], src, dst))
	case "http":
		return strings.TrimSpace(fmt.Sprintf("zeek http %s %s%s status=%s",
			fields["method"], fields["host"], fields["uri"], fields["status_code"]))
	case "ssl":
		return strings.TrimSpace(fmt.Sprintf("zeek ssl %s version=%s validation=%s %s -> %s",
			fields["server_name"], fields["version"], fields["validation_status"], src, dst))
	case "weird":
		return strings.TrimSpace(fmt.Sprintf("zeek weird %s %s", fields["name"], fields["addl"]))
	default:
		return strings.TrimSpace("zeek " + logType + " " + strings.Join(nonEmptyStrings([]string{src, "->", dst}), " "))
	}
}

func zeekPayloadFields(logType string, fields map[string]string) map[string]any {
	payload := map[string]any{
		"sensor":        "zeek",
		"log_family":    "network-security",
		"zeek_log_type": logType,
		"uid":           fields["uid"],
		"src_ip":        fields["id.orig_h"],
		"src_port":      parseZeekInt(fields["id.orig_p"]),
		"dest_ip":       fields["id.resp_h"],
		"dest_port":     parseZeekInt(fields["id.resp_p"]),
		"proto":         fields["proto"],
	}
	switch logType {
	case "conn":
		payload["service"] = fields["service"]
		payload["connection_state"] = fields["conn_state"]
	case "notice":
		payload["note"] = fields["note"]
		payload["sub"] = fields["sub"]
	case "dns":
		payload["query"] = fields["query"]
		payload["qtype"] = fields["qtype_name"]
		payload["rcode"] = fields["rcode_name"]
	case "http":
		payload["method"] = fields["method"]
		payload["host"] = fields["host"]
		payload["uri"] = fields["uri"]
		payload["status_code"] = parseZeekInt(fields["status_code"])
	case "ssl":
		payload["server_name"] = fields["server_name"]
		payload["tls_version"] = fields["version"]
		payload["validation_status"] = fields["validation_status"]
	case "weird":
		payload["weird_name"] = fields["name"]
		payload["addl"] = fields["addl"]
	}
	return payload
}

func parseZeekInt(value string) any {
	if value == "" {
		return nil
	}
	n, err := strconv.Atoi(value)
	if err != nil {
		return value
	}
	return n
}

func endpointLabel(host string, port any) string {
	host = strings.TrimSpace(host)
	if host == "" {
		return ""
	}
	portText := ""
	switch v := port.(type) {
	case string:
		portText = strings.TrimSpace(v)
	case int:
		if v > 0 {
			portText = strconv.Itoa(v)
		}
	case int64:
		if v > 0 {
			portText = strconv.FormatInt(v, 10)
		}
	case float64:
		if v > 0 {
			portText = strconv.FormatInt(int64(v), 10)
		}
	}
	if portText == "" {
		return host
	}
	return host + ":" + portText
}

func jsonObject(row map[string]any, key string) (map[string]any, bool) {
	value, ok := row[key]
	if !ok || value == nil {
		return nil, false
	}
	object, ok := value.(map[string]any)
	return object, ok
}

func jsonNumberValue(row map[string]any, key string) any {
	value, ok := row[key]
	if !ok || value == nil {
		return nil
	}
	switch v := value.(type) {
	case float64:
		if v == float64(int64(v)) {
			return int64(v)
		}
		return v
	case int, int64, uint64:
		return v
	case string:
		if strings.TrimSpace(v) == "" {
			return nil
		}
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
		return v
	default:
		return v
	}
}

func mergeFields(base map[string]any, updates map[string]any) map[string]any {
	if base == nil {
		base = map[string]any{}
	}
	for key, value := range updates {
		if key == "" || isEmptyFieldValue(value) {
			continue
		}
		base[key] = value
	}
	return base
}

func isEmptyFieldValue(value any) bool {
	switch v := value.(type) {
	case nil:
		return true
	case string:
		return strings.TrimSpace(v) == ""
	default:
		return false
	}
}

func nonEmptyStrings(values []string) []string {
	out := make([]string, 0, len(values))
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			out = append(out, value)
		}
	}
	return out
}

func dedupeStrings(values []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(values))
	for _, value := range values {
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		out = append(out, value)
	}
	return out
}

func parseLinuxLogLine(line string) linuxLogLine {
	matches := syslogLinePattern.FindStringSubmatch(line)
	if len(matches) == 6 {
		return linuxLogLine{
			EventTime: matches[1],
			Hostname:  matches[2],
			Program:   matches[3],
			PID:       matches[4],
			Message:   matches[5],
		}
	}
	return linuxLogLine{Message: line}
}

func linuxLogChannel(sourceName string) string {
	base := filepath.Base(sourceName)
	switch {
	case strings.Contains(base, "auth"), strings.Contains(base, "secure"):
		return "auth"
	case strings.Contains(base, "syslog"), strings.Contains(base, "messages"):
		return "system"
	default:
		return sourceName
	}
}

func splitLogLines(text string) []string {
	lines := strings.Split(text, "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	return lines
}

func scrubLogMessage(message string) string {
	message = bearerTokenPattern.ReplaceAllString(message, "Bearer [REDACTED]")
	message = secretAssignmentPattern.ReplaceAllStringFunc(message, func(match string) string {
		key := match
		if idx := strings.IndexAny(match, ":="); idx >= 0 {
			key = strings.TrimSpace(match[:idx])
		}
		return key + "=[REDACTED]"
	})
	return message
}

func limitStringBytes(value string, maxBytes int) string {
	if maxBytes <= 0 || len(value) <= maxBytes {
		return value
	}
	if maxBytes <= 3 {
		return value[:maxBytes]
	}
	limit := maxBytes - 3
	for idx := range value {
		if idx >= limit {
			return value[:idx] + "..."
		}
	}
	if len(value) > limit {
		return value[:limit] + "..."
	}
	return value
}

func extractActor(message string) string {
	if match := userFieldPattern.FindStringSubmatch(message); len(match) == 2 {
		return match[1]
	}
	if match := userForPattern.FindStringSubmatch(message); len(match) == 2 {
		return match[1]
	}
	return ""
}

func severityFromJournalPriority(priority string) string {
	n, err := strconv.Atoi(strings.TrimSpace(priority))
	if err != nil {
		return "INFO"
	}
	switch {
	case n <= 3:
		return "ERROR"
	case n == 4:
		return "WARNING"
	default:
		return "INFO"
	}
}

func severityFromWindowsLevel(level string) string {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "critical", "error":
		return "ERROR"
	case "warning":
		return "WARNING"
	default:
		return "INFO"
	}
}

func journalTimestamp(row map[string]any) string {
	value := jsonString(row, "_SOURCE_REALTIME_TIMESTAMP")
	if value == "" {
		value = jsonString(row, "__REALTIME_TIMESTAMP")
	}
	micros, err := strconv.ParseInt(value, 10, 64)
	if err != nil || micros <= 0 {
		return ""
	}
	return time.Unix(0, micros*int64(time.Microsecond)).UTC().Format(time.RFC3339Nano)
}

func powerShellString(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func newLogCursorState() *logCursorState {
	return &logCursorState{
		Files:   map[string]fileLogCursor{},
		Windows: map[string]uint64{},
	}
}

func loadLogCursorState(path string) *logCursorState {
	state := newLogCursorState()
	if path == "" {
		return state
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return state
	}
	if err := json.Unmarshal(data, state); err != nil {
		return newLogCursorState()
	}
	if state.Files == nil {
		state.Files = map[string]fileLogCursor{}
	}
	if state.Windows == nil {
		state.Windows = map[string]uint64{}
	}
	return state
}

func saveLogCursorState(path string, state *logCursorState) error {
	if path == "" || state == nil {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	encoded, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, encoded, 0o600); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

func fileIdentity(info os.FileInfo) string {
	if info == nil {
		return ""
	}
	sys := reflect.ValueOf(info.Sys())
	if sys.IsValid() {
		if sys.Kind() == reflect.Ptr && !sys.IsNil() {
			sys = sys.Elem()
		}
		if sys.IsValid() && sys.Kind() == reflect.Struct {
			dev, hasDev := numericStructField(sys, "Dev")
			ino, hasIno := numericStructField(sys, "Ino")
			if hasIno {
				return fmt.Sprintf("%d:%d", dev, ino)
			}
			if hasDev {
				return fmt.Sprintf("%d", dev)
			}
		}
	}
	return info.Name()
}

func numericStructField(value reflect.Value, name string) (uint64, bool) {
	field := value.FieldByName(name)
	if !field.IsValid() {
		return 0, false
	}
	switch field.Kind() {
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return field.Uint(), true
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return uint64(field.Int()), true
	default:
		return 0, false
	}
}
