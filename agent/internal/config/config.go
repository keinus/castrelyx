package config

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type Config struct {
	ManagerURL                  string
	EnrollmentToken             string
	AgentID                     string
	TenantID                    string
	BatchInterval               time.Duration
	SenderFlushInterval         time.Duration
	SpoolDir                    string
	MaxSpoolRecord              int64
	MaxSpoolBytes               int64
	MaxSpoolRecords             int
	MaxSpoolAge                 time.Duration
	MaxBatchItems               int
	MaxBatchBytes               int64
	MaxItemBytes                int64
	CertDir                     string
	CACertPath                  string
	ClientCertPath              string
	ClientKeyPath               string
	TLSServerName               string
	IngestTransport             string
	TCPIngestAddr               string
	TCPIngestServerName         string
	TCPIngestMaxIdle            time.Duration
	EnabledCollectors           []string
	CollectorIntervals          map[string]time.Duration
	CollectorFullIntervals      map[string]time.Duration
	LogCursorPath               string
	LogMessageMaxBytes          int
	UpdateEnabled               bool
	UpdateChannel               string
	UpdateCheckInterval         time.Duration
	UpdateDir                   string
	UpdatePublicKeyPath         string
	RemoteTasksEnabled          bool
	RemoteTaskInterval          time.Duration
	FileManagerEnabled          bool
	FileManagerRoots            []string
	FileManagerAllowDelete      bool
	FileManagerMaxTransferBytes int64
	FileManagerPollInterval     time.Duration
}

func Load(path string) (Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return Config{}, err
	}
	defer f.Close()

	cfg := Config{
		TenantID:                    "default",
		BatchInterval:               30 * time.Second,
		SenderFlushInterval:         2 * time.Second,
		SpoolDir:                    defaultSpoolDir(),
		MaxSpoolRecord:              8 * 1024 * 1024,
		MaxSpoolBytes:               256 * 1024 * 1024,
		MaxSpoolRecords:             10_000,
		MaxSpoolAge:                 7 * 24 * time.Hour,
		MaxBatchItems:               1_000,
		MaxBatchBytes:               4 * 1024 * 1024,
		MaxItemBytes:                512 * 1024,
		IngestTransport:             "https",
		TCPIngestMaxIdle:            15 * time.Second,
		EnabledCollectors:           defaultCollectors(),
		CollectorIntervals:          defaultCollectorIntervals(),
		CollectorFullIntervals:      defaultCollectorFullIntervals(),
		LogMessageMaxBytes:          1024,
		UpdateEnabled:               true,
		UpdateChannel:               "stable",
		UpdateCheckInterval:         6 * time.Hour,
		UpdateDir:                   defaultUpdateDir(),
		RemoteTasksEnabled:          true,
		RemoteTaskInterval:          10 * time.Second,
		FileManagerEnabled:          false,
		FileManagerRoots:            defaultFileManagerRoots(),
		FileManagerAllowDelete:      false,
		FileManagerMaxTransferBytes: 256 * 1024 * 1024,
		FileManagerPollInterval:     30 * time.Second,
	}

	var listKey string
	var explicitCollectors bool
	var explicitFileManagerRoots bool
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		raw := stripComment(scanner.Text())
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if strings.HasSuffix(line, ":") {
			key := strings.TrimSuffix(line, ":")
			if key == "collectors" || key == "file_manager_roots" {
				listKey = key
			} else {
				listKey = ""
			}
			continue
		}
		if listKey != "" && strings.HasPrefix(line, "-") {
			value := strings.TrimSpace(strings.TrimPrefix(line, "-"))
			if value == "" {
				continue
			}
			if listKey == "collectors" {
				if !explicitCollectors {
					cfg.EnabledCollectors = nil
					explicitCollectors = true
				}
				cfg.EnabledCollectors = append(cfg.EnabledCollectors, value)
			} else if listKey == "file_manager_roots" {
				if !explicitFileManagerRoots {
					cfg.FileManagerRoots = nil
					explicitFileManagerRoots = true
				}
				cfg.FileManagerRoots = append(cfg.FileManagerRoots, value)
			}
			continue
		}
		listKey = ""

		key, value, ok := strings.Cut(line, ":")
		if !ok {
			return Config{}, fmt.Errorf("invalid config line %q", line)
		}
		key = strings.TrimSpace(key)
		value = strings.Trim(strings.TrimSpace(value), `"'`)
		switch key {
		case "manager_url":
			cfg.ManagerURL = strings.TrimRight(value, "/")
		case "enrollment_token":
			cfg.EnrollmentToken = value
		case "agent_id":
			cfg.AgentID = value
		case "tenant_id":
			if value != "" {
				cfg.TenantID = value
			}
		case "batch_interval":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid batch_interval: %w", err)
			}
			cfg.BatchInterval = d
		case "sender_flush_interval":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid sender_flush_interval: %w", err)
			}
			cfg.SenderFlushInterval = d
		case "spool_dir":
			cfg.SpoolDir = value
		case "cert_dir":
			cfg.CertDir = value
		case "ca_cert_path":
			cfg.CACertPath = value
		case "client_cert_path":
			cfg.ClientCertPath = value
		case "client_key_path":
			cfg.ClientKeyPath = value
		case "tls_server_name":
			cfg.TLSServerName = value
		case "ingest_transport":
			cfg.IngestTransport = value
		case "tcp_ingest_addr":
			cfg.TCPIngestAddr = value
		case "tcp_ingest_server_name":
			cfg.TCPIngestServerName = value
		case "tcp_ingest_max_idle":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid tcp_ingest_max_idle: %w", err)
			}
			cfg.TCPIngestMaxIdle = d
		case "max_spool_record_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_spool_record_bytes: %w", err)
			}
			cfg.MaxSpoolRecord = n
		case "max_spool_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_spool_bytes: %w", err)
			}
			cfg.MaxSpoolBytes = n
		case "max_spool_records":
			n, err := parsePositiveInt(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_spool_records: %w", err)
			}
			cfg.MaxSpoolRecords = n
		case "max_spool_age":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_spool_age: %w", err)
			}
			cfg.MaxSpoolAge = d
		case "max_batch_items":
			n, err := parsePositiveInt(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_batch_items: %w", err)
			}
			cfg.MaxBatchItems = n
		case "max_batch_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_batch_bytes: %w", err)
			}
			cfg.MaxBatchBytes = n
		case "max_item_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_item_bytes: %w", err)
			}
			cfg.MaxItemBytes = n
		case "log_cursor_path":
			cfg.LogCursorPath = value
		case "log_message_max_bytes":
			n, err := parsePositiveInt(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid log_message_max_bytes: %w", err)
			}
			cfg.LogMessageMaxBytes = n
		case "update_enabled":
			enabled, err := parseBool(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid update_enabled: %w", err)
			}
			cfg.UpdateEnabled = enabled
		case "update_channel":
			if value != "" {
				cfg.UpdateChannel = value
			}
		case "update_check_interval":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid update_check_interval: %w", err)
			}
			cfg.UpdateCheckInterval = d
		case "update_dir":
			cfg.UpdateDir = value
		case "update_public_key_path":
			cfg.UpdatePublicKeyPath = value
		case "remote_tasks_enabled":
			enabled, err := parseBool(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid remote_tasks_enabled: %w", err)
			}
			cfg.RemoteTasksEnabled = enabled
		case "remote_task_interval":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid remote_task_interval: %w", err)
			}
			cfg.RemoteTaskInterval = d
		case "file_manager_enabled":
			enabled, err := parseBool(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid file_manager_enabled: %w", err)
			}
			cfg.FileManagerEnabled = enabled
		case "file_manager_allow_delete":
			enabled, err := parseBool(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid file_manager_allow_delete: %w", err)
			}
			cfg.FileManagerAllowDelete = enabled
		case "file_manager_max_transfer_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid file_manager_max_transfer_bytes: %w", err)
			}
			cfg.FileManagerMaxTransferBytes = n
		case "file_manager_poll_interval":
			d, err := time.ParseDuration(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid file_manager_poll_interval: %w", err)
			}
			cfg.FileManagerPollInterval = d
		default:
			if strings.HasPrefix(key, "collector_interval_") {
				name := strings.TrimPrefix(key, "collector_interval_")
				if _, ok := cfg.CollectorIntervals[name]; !ok {
					return Config{}, fmt.Errorf("unknown collector interval %q", name)
				}
				d, err := time.ParseDuration(value)
				if err != nil || d <= 0 {
					return Config{}, fmt.Errorf("invalid collector interval for %s", name)
				}
				cfg.CollectorIntervals[name] = d
				continue
			}
			if strings.HasPrefix(key, "collector_full_interval_") {
				name := strings.TrimPrefix(key, "collector_full_interval_")
				if _, ok := cfg.CollectorFullIntervals[name]; !ok {
					return Config{}, fmt.Errorf("unknown collector full interval %q", name)
				}
				d, err := time.ParseDuration(value)
				if err != nil || d <= 0 {
					return Config{}, fmt.Errorf("invalid collector full interval for %s", name)
				}
				cfg.CollectorFullIntervals[name] = d
				continue
			}
			return Config{}, fmt.Errorf("unknown config key %q", key)
		}
	}
	if err := scanner.Err(); err != nil {
		return Config{}, err
	}
	if cfg.BatchInterval <= 0 {
		return Config{}, errors.New("batch_interval must be positive")
	}
	if cfg.SenderFlushInterval <= 0 {
		return Config{}, errors.New("sender_flush_interval must be positive")
	}
	if cfg.TCPIngestMaxIdle <= 0 || cfg.TCPIngestMaxIdle >= 30*time.Second {
		return Config{}, errors.New("tcp_ingest_max_idle must be positive and less than 30s")
	}
	if cfg.MaxSpoolRecord <= 0 || cfg.MaxSpoolBytes < cfg.MaxSpoolRecord || cfg.MaxSpoolRecords <= 0 || cfg.MaxSpoolAge <= 0 {
		return Config{}, errors.New("spool limits must be positive and max_spool_bytes must cover one record")
	}
	if cfg.MaxBatchItems <= 0 || cfg.MaxBatchBytes <= 0 || cfg.MaxItemBytes <= 0 {
		return Config{}, errors.New("batch limits must be positive")
	}
	if cfg.MaxItemBytes > cfg.MaxBatchBytes {
		return Config{}, errors.New("max_item_bytes must not exceed max_batch_bytes")
	}
	if cfg.MaxBatchBytes > cfg.MaxSpoolRecord {
		return Config{}, errors.New("max_batch_bytes must not exceed max_spool_record_bytes")
	}

	if cfg.ManagerURL == "" {
		return Config{}, errors.New("manager_url is required")
	}
	parsedManagerURL, err := url.Parse(cfg.ManagerURL)
	if err != nil {
		return Config{}, fmt.Errorf("invalid manager_url: %w", err)
	}
	if parsedManagerURL.Scheme != "https" {
		return Config{}, errors.New("manager_url must use https")
	}
	switch cfg.IngestTransport {
	case "", "https":
		cfg.IngestTransport = "https"
	case "tcp_mtls":
		if cfg.TCPIngestAddr == "" {
			return Config{}, errors.New("tcp_ingest_addr is required when ingest_transport is tcp_mtls")
		}
		if _, _, err := net.SplitHostPort(cfg.TCPIngestAddr); err != nil {
			return Config{}, fmt.Errorf("invalid tcp_ingest_addr: %w", err)
		}
	default:
		return Config{}, fmt.Errorf("invalid ingest_transport %q", cfg.IngestTransport)
	}
	if cfg.AgentID == "" {
		hostname, _ := os.Hostname()
		cfg.AgentID = hostname
	}
	if cfg.AgentID == "" {
		return Config{}, errors.New("agent_id is required when hostname cannot be determined")
	}
	if !filepath.IsAbs(cfg.SpoolDir) {
		cfg.SpoolDir = filepath.Join(filepath.Dir(path), cfg.SpoolDir)
	}
	if cfg.CertDir == "" {
		cfg.CertDir = defaultCertDir()
	}
	if !filepath.IsAbs(cfg.CertDir) {
		cfg.CertDir = filepath.Join(filepath.Dir(path), cfg.CertDir)
	}
	if cfg.CACertPath == "" {
		cfg.CACertPath = filepath.Join(cfg.CertDir, "ca.pem")
	}
	if cfg.ClientCertPath == "" {
		cfg.ClientCertPath = filepath.Join(cfg.CertDir, "client.pem")
	}
	if cfg.ClientKeyPath == "" {
		cfg.ClientKeyPath = filepath.Join(cfg.CertDir, "client.key")
	}
	cfg.CACertPath = resolvePath(filepath.Dir(path), cfg.CACertPath)
	cfg.ClientCertPath = resolvePath(filepath.Dir(path), cfg.ClientCertPath)
	cfg.ClientKeyPath = resolvePath(filepath.Dir(path), cfg.ClientKeyPath)
	if cfg.IngestTransport == "tcp_mtls" && cfg.TCPIngestServerName == "" {
		cfg.TCPIngestServerName = cfg.TLSServerName
	}
	if cfg.LogCursorPath == "" {
		cfg.LogCursorPath = filepath.Join(cfg.SpoolDir, "log-cursors.json")
	} else {
		cfg.LogCursorPath = resolvePath(filepath.Dir(path), cfg.LogCursorPath)
	}
	if cfg.LogMessageMaxBytes <= 0 {
		cfg.LogMessageMaxBytes = 1024
	}
	if cfg.UpdateCheckInterval <= 0 {
		cfg.UpdateCheckInterval = 6 * time.Hour
	}
	if !filepath.IsAbs(cfg.UpdateDir) {
		cfg.UpdateDir = filepath.Join(filepath.Dir(path), cfg.UpdateDir)
	}
	if cfg.UpdatePublicKeyPath != "" {
		cfg.UpdatePublicKeyPath = resolvePath(filepath.Dir(path), cfg.UpdatePublicKeyPath)
	}
	if cfg.RemoteTaskInterval <= 0 {
		cfg.RemoteTaskInterval = 10 * time.Second
	}
	if cfg.FileManagerPollInterval <= 0 {
		return Config{}, errors.New("file_manager_poll_interval must be positive")
	}
	if cfg.FileManagerMaxTransferBytes <= 0 {
		return Config{}, errors.New("file_manager_max_transfer_bytes must be positive")
	}
	for i, root := range cfg.FileManagerRoots {
		cfg.FileManagerRoots[i] = resolvePath(filepath.Dir(path), root)
	}
	if cfg.EnrollmentToken == "" && !certificateFilesExist(cfg) {
		return Config{}, errors.New("enrollment_token is required when client certificate files are missing")
	}

	return cfg, nil
}

// DiscoverUpdateDir reads only the rollback-critical update_dir setting and
// deliberately ignores unrelated or invalid configuration. It runs before the
// full parser so an updated binary can still recover from a startup/config
// compatibility regression.
func DiscoverUpdateDir(path string) (string, error) {
	updateDir := defaultUpdateDir()
	f, err := os.Open(path)
	if err != nil {
		return updateDir, err
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(stripComment(scanner.Text()))
		key, value, ok := strings.Cut(line, ":")
		if !ok || strings.TrimSpace(key) != "update_dir" {
			continue
		}
		value = strings.Trim(strings.TrimSpace(value), `"'`)
		if value != "" {
			updateDir = value
		}
	}
	if err := scanner.Err(); err != nil {
		return updateDir, err
	}
	if !filepath.IsAbs(updateDir) {
		updateDir = filepath.Join(filepath.Dir(path), updateDir)
	}
	return filepath.Clean(updateDir), nil
}

func defaultUpdateDir() string {
	if runtime.GOOS == "windows" {
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, "Castrelyx", "update")
	}
	return "/var/lib/castrelyx-agent/update"
}

func defaultFileManagerRoots() []string {
	if runtime.GOOS == "windows" {
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return []string{filepath.Join(base, "Castrelyx", "files")}
	}
	return []string{"/var/log", "/etc/castrelyx", "/var/lib/castrelyx-agent"}
}

func defaultCollectors() []string {
	return []string{
		"identity",
		"metric",
		"network",
		"process",
		"service",
		"port",
		"package",
		"firewall",
		"log_tailer",
		"agent_health",
	}
}

func defaultCollectorIntervals() map[string]time.Duration {
	return map[string]time.Duration{
		"identity":     time.Hour,
		"metric":       30 * time.Second,
		"network":      5 * time.Minute,
		"process":      2 * time.Minute,
		"service":      5 * time.Minute,
		"port":         2 * time.Minute,
		"package":      12 * time.Hour,
		"firewall":     10 * time.Minute,
		"log_tailer":   30 * time.Second,
		"agent_health": 30 * time.Second,
	}
}

func defaultCollectorFullIntervals() map[string]time.Duration {
	return map[string]time.Duration{
		"identity": 24 * time.Hour,
		"network":  time.Hour,
		"process":  15 * time.Minute,
		"service":  time.Hour,
		"port":     15 * time.Minute,
		"package":  24 * time.Hour,
		"firewall": time.Hour,
	}
}

func defaultSpoolDir() string {
	if runtime.GOOS == "windows" {
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, "Castrelyx", "spool")
	}
	return "/var/lib/castrelyx-agent/spool"
}

func defaultCertDir() string {
	if runtime.GOOS == "windows" {
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, "Castrelyx", "certs")
	}
	return "/var/lib/castrelyx-agent/certs"
}

func resolvePath(base, value string) string {
	if filepath.IsAbs(value) {
		return value
	}
	return filepath.Join(base, value)
}

func certificateFilesExist(cfg Config) bool {
	for _, p := range []string{cfg.CACertPath, cfg.ClientCertPath, cfg.ClientKeyPath} {
		if _, err := os.Stat(p); err != nil {
			return false
		}
	}
	return true
}

func stripComment(line string) string {
	idx := strings.Index(line, "#")
	if idx < 0 {
		return line
	}
	return line[:idx]
}

func parseBytes(value string) (int64, error) {
	value = strings.TrimSpace(strings.ToLower(value))
	multiplier := int64(1)
	switch {
	case strings.HasSuffix(value, "kb"):
		multiplier = 1024
		value = strings.TrimSuffix(value, "kb")
	case strings.HasSuffix(value, "mb"):
		multiplier = 1024 * 1024
		value = strings.TrimSuffix(value, "mb")
	}
	value = strings.TrimSpace(value)
	var n int64
	if _, err := fmt.Sscanf(value, "%d", &n); err != nil {
		return 0, err
	}
	return n * multiplier, nil
}

func parsePositiveInt(value string) (int, error) {
	var n int
	if _, err := fmt.Sscanf(strings.TrimSpace(value), "%d", &n); err != nil {
		return 0, err
	}
	if n <= 0 {
		return 0, fmt.Errorf("must be positive")
	}
	return n, nil
}

func parseBool(value string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "true", "yes", "1", "on":
		return true, nil
	case "false", "no", "0", "off":
		return false, nil
	default:
		return false, fmt.Errorf("invalid boolean %q", value)
	}
}
