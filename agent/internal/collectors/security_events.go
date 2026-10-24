package collectors

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"strconv"
	"strings"
)

// Parse named EventData instead of localized Windows display messages. The
// original data remains attached; display-message limits do not truncate it.
func enrichWindowsSecurityEvent(event *normalizedLogEvent, document string) error {
	var root struct {
		System struct {
			TimeCreated struct {
				SystemTime string `xml:"SystemTime,attr"`
			} `xml:"TimeCreated"`
		} `xml:"System"`
		EventData struct {
			Data []struct {
				Name  string `xml:"Name,attr"`
				Value string `xml:",chardata"`
			} `xml:"Data"`
		} `xml:"EventData"`
	}
	if err := xml.Unmarshal([]byte(document), &root); err != nil {
		return fmt.Errorf("parse Windows event XML: %w", err)
	}
	fields := make(map[string]any, len(root.EventData.Data)+8)
	for _, value := range root.EventData.Data {
		if value.Name != "" {
			fields[value.Name] = value.Value
		}
	}
	event.Fields = mergeFields(event.Fields, fields)
	if root.System.TimeCreated.SystemTime != "" {
		event.EventTime = root.System.TimeCreated.SystemTime
	}
	event.Actor = firstNonBlank(jsonString(fields, "TargetUserName"), jsonString(fields, "SubjectUserName"), jsonString(fields, "User"), event.Actor)
	event.Fields["realm"] = firstNonBlank(jsonString(fields, "TargetDomainName"), jsonString(fields, "SubjectDomainName"))
	event.Fields["src_ip"] = firstNonBlank(jsonString(fields, "IpAddress"), jsonString(fields, "SourceIp"))
	event.Fields["dest_ip"] = jsonString(fields, "DestinationIp")
	event.Fields["src_port"] = firstNonBlank(jsonString(fields, "IpPort"), jsonString(fields, "SourcePort"))
	event.Fields["dest_port"] = jsonString(fields, "DestinationPort")
	event.Fields["proto"] = strings.ToLower(jsonString(fields, "Protocol"))
	event.Fields["process_id"] = firstNonBlank(jsonString(fields, "ProcessGuid"), jsonString(fields, "NewProcessId"))
	event.Fields["parent_guid"] = jsonString(fields, "ParentProcessGuid")
	event.Fields["file_path"] = firstNonBlank(jsonString(fields, "TargetFilename"), jsonString(fields, "ObjectName"), jsonString(fields, "Image"))
	event.Fields["service"] = jsonString(fields, "ServiceName")
	event.Fields["session_id"] = firstNonBlank(jsonString(fields, "TargetLogonId"), jsonString(fields, "SubjectLogonId"), jsonString(fields, "LogonGuid"))
	channel := strings.ToLower(event.Channel)
	if strings.Contains(channel, "sysmon") {
		event.EventCategory = "security"
		switch event.EventID {
		case 1:
			event.EventType = "process.start"
		case 3:
			event.EventType = "process.network"
		case 5:
			event.EventType = "process.exit"
		case 10:
			event.EventType = "process.access"
			event.Fields["process_name"] = jsonString(fields, "SourceImage")
			event.Fields["process_id"] = firstNonBlank(jsonString(fields, "SourceProcessGUID"), jsonString(fields, "SourceProcessGuid"))
		case 11:
			event.EventType = "file.create"
		case 12, 13, 14:
			event.EventType = "registry.modify"
		case 22:
			event.EventType = "process.dns"
			event.Fields["query"] = jsonString(fields, "QueryName")
		case 23, 26:
			event.EventType = "file.delete"
		case 4:
			event.EventType = "sensor.state"
		case 16:
			event.EventType = "sensor.config.change"
		}
		for _, hash := range strings.Split(jsonString(fields, "Hashes"), ",") {
			parts := strings.SplitN(hash, "=", 2)
			if len(parts) == 2 && strings.EqualFold(parts[0], "SHA256") {
				event.Fields["sha256"] = strings.ToLower(parts[1])
			}
		}
		return nil
	}
	switch event.EventID {
	case 4688:
		event.EventType = "process.start"
		event.Fields["Image"] = jsonString(fields, "NewProcessName")
		event.Fields["file_path"] = jsonString(fields, "NewProcessName")
	case 4689:
		event.EventType = "process.exit"
	case 4698:
		event.EventType = "task.create"
	case 4702:
		event.EventType = "task.modify"
	case 4697, 7045:
		event.EventType = "service.create"
		event.Fields["process_name"] = firstNonBlank(jsonString(fields, "ServiceFileName"), jsonString(fields, "ImagePath"))
	case 4720:
		event.EventType = "account.create"
		event.Fields["account_id"] = jsonString(fields, "TargetSid")
	case 4728, 4732, 4756:
		event.EventType = "account.privilege.add"
		event.Actor = jsonString(fields, "SubjectUserName")
		event.Fields["account_id"] = jsonString(fields, "MemberSid")
	case 1102:
		if strings.EqualFold(event.Channel, "Security") {
			event.EventType = "audit.cleared"
		}
	case 104:
		if strings.EqualFold(event.Provider, "Microsoft-Windows-Eventlog") || strings.EqualFold(event.Provider, "EventLog") {
			event.EventType = "audit.cleared"
		}
	}
	return nil
}

func enrichSuricataFields(fields map[string]any, row map[string]any) {
	// Preserve all EVE fields, including numeric flow IDs, transaction IDs and
	// nested protocol payloads. flow_id identifies a flow, not an event.
	fields["data"] = row
	fields["community_id"] = jsonString(row, "community_id")
	if flow, ok := jsonObject(row, "flow"); ok {
		fields["bytes_sent"] = jsonNumberValue(flow, "bytes_toserver")
		fields["bytes_received"] = jsonNumberValue(flow, "bytes_toclient")
		fields["connection_state"] = jsonString(flow, "state")
	}
	if http, ok := jsonObject(row, "http"); ok {
		fields["http_host"] = jsonString(http, "hostname")
		fields["method"] = jsonString(http, "http_method")
		fields["uri"] = jsonString(http, "url")
		fields["user_agent"] = jsonString(http, "http_user_agent")
	}
	if tls, ok := jsonObject(row, "tls"); ok {
		fields["server_name"] = jsonString(tls, "sni")
		fields["tls_version"] = jsonString(tls, "version")
		fields["ja4"] = jsonString(tls, "ja4")
		if ja3, ok := jsonObject(tls, "ja3"); ok {
			fields["ja3"] = jsonString(ja3, "hash")
		}
	}
	if dns, ok := jsonObject(row, "dns"); ok {
		fields["query"] = jsonString(dns, "rrname")
		fields["qtype_name"] = jsonString(dns, "rrtype")
		fields["rcode_name"] = jsonString(dns, "rcode")
		if queries, ok := dns["queries"].([]any); ok && len(queries) > 0 {
			if query, ok := queries[0].(map[string]any); ok {
				fields["query"] = jsonString(query, "rrname")
				fields["qtype_name"] = jsonString(query, "rrtype")
			}
		}
		if answers, ok := dns["answers"]; ok {
			encoded, _ := json.Marshal(answers)
			fields["answers"] = string(encoded)
			if values, ok := answers.([]any); ok {
				var minimum *float64
				for _, value := range values {
					if answer, ok := value.(map[string]any); ok {
						if n, err := strconv.ParseFloat(fmt.Sprint(answer["ttl"]), 64); err == nil {
							if minimum == nil || n < *minimum {
								minimum = &n
							}
						}
					}
				}
				if minimum != nil {
					fields["min_ttl"] = *minimum
				}
			}
		}
	}
	if file, ok := jsonObject(row, "fileinfo"); ok {
		fields["file_path"] = jsonString(file, "filename")
		fields["sha256"] = jsonString(file, "sha256")
	}
}
