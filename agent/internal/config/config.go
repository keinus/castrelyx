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
	ManagerURL          string
	EnrollmentToken     string
	AgentID             string
	TenantID            string
	BatchInterval       time.Duration
	SpoolDir            string
	MaxSpoolRecord      int64
	CertDir             string
	CACertPath          string
	ClientCertPath      string
	ClientKeyPath       string
	TLSServerName       string
	IngestTransport     string
	TCPIngestAddr       string
	TCPIngestServerName string
	EnabledCollectors   []string
}

func Load(path string) (Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return Config{}, err
	}
	defer f.Close()

	cfg := Config{
		TenantID:          "default",
		BatchInterval:     30 * time.Second,
		SpoolDir:          defaultSpoolDir(),
		MaxSpoolRecord:    8 * 1024 * 1024,
		IngestTransport:   "https",
		EnabledCollectors: defaultCollectors(),
	}

	var inCollectors bool
	var explicitCollectors bool
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		raw := stripComment(scanner.Text())
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if strings.HasSuffix(line, ":") {
			inCollectors = strings.TrimSuffix(line, ":") == "collectors"
			continue
		}
		if inCollectors && strings.HasPrefix(line, "-") {
			value := strings.TrimSpace(strings.TrimPrefix(line, "-"))
			if value != "" {
				if !explicitCollectors {
					cfg.EnabledCollectors = nil
					explicitCollectors = true
				}
				cfg.EnabledCollectors = append(cfg.EnabledCollectors, value)
			}
			continue
		}
		inCollectors = false

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
		case "max_spool_record_bytes":
			n, err := parseBytes(value)
			if err != nil {
				return Config{}, fmt.Errorf("invalid max_spool_record_bytes: %w", err)
			}
			cfg.MaxSpoolRecord = n
		default:
			return Config{}, fmt.Errorf("unknown config key %q", key)
		}
	}
	if err := scanner.Err(); err != nil {
		return Config{}, err
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
	if cfg.EnrollmentToken == "" && !certificateFilesExist(cfg) {
		return Config{}, errors.New("enrollment_token is required when client certificate files are missing")
	}

	return cfg, nil
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
