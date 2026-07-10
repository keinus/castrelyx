package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadParsesAgentConfigAndDefaults(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte(`
manager_url: https://manager.castrelyx.local
enrollment_token: bootstrap-secret
agent_id: agent-01
tenant_id: tenant-01
batch_interval: 15s
spool_dir: ./spool
cert_dir: ./certs
tls_server_name: manager.castrelyx.local
update_enabled: true
update_channel: canary
update_check_interval: 10m
update_dir: ./updates
update_public_key_path: ./update_public_key.pem
file_manager_enabled: true
file_manager_allow_delete: false
file_manager_max_transfer_bytes: 10mb
file_manager_poll_interval: 3s
log_cursor_path: ./log-cursors.json
log_message_max_bytes: 512
collectors:
  - identity
  - metric
  - log_tailer
file_manager_roots:
  - ./managed
  - ./other-managed
`)

	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	if cfg.ManagerURL != "https://manager.castrelyx.local" {
		t.Fatalf("ManagerURL = %q", cfg.ManagerURL)
	}
	if cfg.EnrollmentToken != "bootstrap-secret" {
		t.Fatalf("EnrollmentToken = %q", cfg.EnrollmentToken)
	}
	if cfg.AgentID != "agent-01" {
		t.Fatalf("AgentID = %q", cfg.AgentID)
	}
	if cfg.TenantID != "tenant-01" {
		t.Fatalf("TenantID = %q", cfg.TenantID)
	}
	if cfg.BatchInterval != 15*time.Second {
		t.Fatalf("BatchInterval = %s", cfg.BatchInterval)
	}
	if cfg.SpoolDir != filepath.Join(dir, "spool") {
		t.Fatalf("SpoolDir = %q", cfg.SpoolDir)
	}
	if cfg.CertDir != filepath.Join(dir, "certs") {
		t.Fatalf("CertDir = %q", cfg.CertDir)
	}
	if cfg.CACertPath != filepath.Join(dir, "certs", "ca.pem") {
		t.Fatalf("CACertPath = %q", cfg.CACertPath)
	}
	if cfg.ClientCertPath != filepath.Join(dir, "certs", "client.pem") {
		t.Fatalf("ClientCertPath = %q", cfg.ClientCertPath)
	}
	if cfg.ClientKeyPath != filepath.Join(dir, "certs", "client.key") {
		t.Fatalf("ClientKeyPath = %q", cfg.ClientKeyPath)
	}
	if cfg.TLSServerName != "manager.castrelyx.local" {
		t.Fatalf("TLSServerName = %q", cfg.TLSServerName)
	}
	if !cfg.UpdateEnabled {
		t.Fatal("UpdateEnabled = false")
	}
	if cfg.UpdateChannel != "canary" {
		t.Fatalf("UpdateChannel = %q", cfg.UpdateChannel)
	}
	if cfg.UpdateCheckInterval != 10*time.Minute {
		t.Fatalf("UpdateCheckInterval = %s", cfg.UpdateCheckInterval)
	}
	if cfg.UpdateDir != filepath.Join(dir, "updates") {
		t.Fatalf("UpdateDir = %q", cfg.UpdateDir)
	}
	if cfg.UpdatePublicKeyPath != filepath.Join(dir, "update_public_key.pem") {
		t.Fatalf("UpdatePublicKeyPath = %q", cfg.UpdatePublicKeyPath)
	}
	if cfg.LogCursorPath != filepath.Join(dir, "log-cursors.json") {
		t.Fatalf("LogCursorPath = %q", cfg.LogCursorPath)
	}
	if cfg.LogMessageMaxBytes != 512 {
		t.Fatalf("LogMessageMaxBytes = %d", cfg.LogMessageMaxBytes)
	}
	if got := cfg.EnabledCollectors; len(got) != 3 || got[0] != "identity" || got[2] != "log_tailer" {
		t.Fatalf("EnabledCollectors = %#v", got)
	}
	if !cfg.FileManagerEnabled {
		t.Fatal("FileManagerEnabled = false")
	}
	if cfg.FileManagerAllowDelete {
		t.Fatal("FileManagerAllowDelete = true")
	}
	if cfg.FileManagerMaxTransferBytes != 10*1024*1024 {
		t.Fatalf("FileManagerMaxTransferBytes = %d", cfg.FileManagerMaxTransferBytes)
	}
	if cfg.FileManagerPollInterval != 3*time.Second {
		t.Fatalf("FileManagerPollInterval = %s", cfg.FileManagerPollInterval)
	}
	if got := cfg.FileManagerRoots; len(got) != 2 || got[0] != filepath.Join(dir, "managed") || got[1] != filepath.Join(dir, "other-managed") {
		t.Fatalf("FileManagerRoots = %#v", got)
	}
	if cfg.IngestTransport != "https" {
		t.Fatalf("IngestTransport = %q", cfg.IngestTransport)
	}
}

func TestLoadParsesTcpMTLSIngestSettings(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte(`
manager_url: https://castrelsign.castrelyx.local
enrollment_token: bootstrap-secret
agent_id: agent-01
tls_server_name: castrelsign.castrelyx.local
ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.castrelyx.local:9443
tcp_ingest_server_name: logparser.castrelyx.local
`)

	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	if cfg.IngestTransport != "tcp_mtls" {
		t.Fatalf("IngestTransport = %q", cfg.IngestTransport)
	}
	if cfg.TCPIngestAddr != "logparser.castrelyx.local:9443" {
		t.Fatalf("TCPIngestAddr = %q", cfg.TCPIngestAddr)
	}
	if cfg.TCPIngestServerName != "logparser.castrelyx.local" {
		t.Fatalf("TCPIngestServerName = %q", cfg.TCPIngestServerName)
	}
}

func TestLoadDefaultsToFullHostCollectorSet(t *testing.T) {
	dir := t.TempDir()
	certDir := filepath.Join(dir, "certs")
	if err := os.MkdirAll(certDir, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"ca.pem", "client.pem", "client.key"} {
		if err := os.WriteFile(filepath.Join(certDir, name), []byte("placeholder"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	path := filepath.Join(dir, "agent.yaml")
	input := []byte("manager_url: https://manager.local\ncert_dir: ./certs\n")
	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	want := []string{"identity", "metric", "network", "process", "service", "port", "package", "firewall", "log_tailer", "agent_health"}
	if len(cfg.EnabledCollectors) != len(want) {
		t.Fatalf("EnabledCollectors length = %d, want %d: %#v", len(cfg.EnabledCollectors), len(want), cfg.EnabledCollectors)
	}
	for i := range want {
		if cfg.EnabledCollectors[i] != want[i] {
			t.Fatalf("EnabledCollectors[%d] = %q, want %q; all = %#v", i, cfg.EnabledCollectors[i], want[i], cfg.EnabledCollectors)
		}
	}
	if cfg.LogCursorPath != filepath.Join(cfg.SpoolDir, "log-cursors.json") {
		t.Fatalf("LogCursorPath = %q", cfg.LogCursorPath)
	}
	if cfg.LogMessageMaxBytes != 1024 {
		t.Fatalf("LogMessageMaxBytes = %d", cfg.LogMessageMaxBytes)
	}
}

func TestLoadFallsBackTcpMTLSServerNameToManagerTLSServerName(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte(`
manager_url: https://castrelsign.castrelyx.local
enrollment_token: bootstrap-secret
agent_id: agent-01
tls_server_name: castrelsign.castrelyx.local
ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.castrelyx.local:9443
`)

	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	if cfg.TCPIngestServerName != "castrelsign.castrelyx.local" {
		t.Fatalf("TCPIngestServerName = %q", cfg.TCPIngestServerName)
	}
}

func TestLoadRejectsTcpMTLSWithoutAddress(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte(`
manager_url: https://castrelsign.castrelyx.local
enrollment_token: bootstrap-secret
agent_id: agent-01
ingest_transport: tcp_mtls
`)

	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load returned nil error for tcp_mtls without tcp_ingest_addr")
	}
}

func TestLoadRejectsMissingRequiredFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	if err := os.WriteFile(path, []byte("agent_id: agent-01\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load returned nil error for missing manager_url")
	}
}

func TestLoadRejectsPlaintextManagerURL(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte("manager_url: http://manager.local\nenrollment_token: token\n")
	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load returned nil error for plaintext manager_url")
	}
}

func TestLoadRejectsInvalidLogMessageMaxBytes(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte("manager_url: https://manager.local\nenrollment_token: token\nlog_message_max_bytes: 0\n")
	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load returned nil error for invalid log_message_max_bytes")
	}
}

func TestLoadRejectsMissingEnrollmentTokenWithoutCertificate(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	input := []byte("manager_url: https://manager.local\ncert_dir: ./certs\n")
	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err == nil {
		t.Fatal("Load returned nil error without enrollment_token or client certificate files")
	}
}

func TestLoadAllowsMissingEnrollmentTokenWhenCertificateFilesExist(t *testing.T) {
	dir := t.TempDir()
	certDir := filepath.Join(dir, "certs")
	if err := os.MkdirAll(certDir, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"ca.pem", "client.pem", "client.key"} {
		if err := os.WriteFile(filepath.Join(certDir, name), []byte("placeholder"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	path := filepath.Join(dir, "agent.yaml")
	input := []byte("manager_url: https://manager.local\ncert_dir: ./certs\n")
	if err := os.WriteFile(path, input, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := Load(path); err != nil {
		t.Fatalf("Load returned error with certificate files present: %v", err)
	}
}

func TestLoadParsesRuntimeLimitsAndCollectorCadence(t *testing.T) {
	cfg := loadConfigText(t, `
manager_url: https://manager.local
enrollment_token: token
sender_flush_interval: 3s
max_spool_record_bytes: 2mb
max_spool_bytes: 10mb
max_spool_records: 25
max_spool_age: 48h
max_batch_items: 50
max_batch_bytes: 1mb
max_item_bytes: 256kb
collector_interval_identity: 2h
collector_interval_metric: 15s
`)

	if cfg.SenderFlushInterval != 3*time.Second {
		t.Fatalf("SenderFlushInterval = %s", cfg.SenderFlushInterval)
	}
	if cfg.MaxSpoolRecord != 2*1024*1024 || cfg.MaxSpoolBytes != 10*1024*1024 || cfg.MaxSpoolRecords != 25 || cfg.MaxSpoolAge != 48*time.Hour {
		t.Fatalf("unexpected spool limits: record=%d bytes=%d records=%d age=%s", cfg.MaxSpoolRecord, cfg.MaxSpoolBytes, cfg.MaxSpoolRecords, cfg.MaxSpoolAge)
	}
	if cfg.MaxBatchItems != 50 || cfg.MaxBatchBytes != 1024*1024 || cfg.MaxItemBytes != 256*1024 {
		t.Fatalf("unexpected batch limits: items=%d bytes=%d item_bytes=%d", cfg.MaxBatchItems, cfg.MaxBatchBytes, cfg.MaxItemBytes)
	}
	if cfg.CollectorIntervals["identity"] != 2*time.Hour || cfg.CollectorIntervals["metric"] != 15*time.Second {
		t.Fatalf("unexpected collector intervals: %#v", cfg.CollectorIntervals)
	}
	if cfg.CollectorIntervals["package"] <= 0 || cfg.CollectorIntervals["agent_health"] <= 0 {
		t.Fatalf("default collector intervals were lost: %#v", cfg.CollectorIntervals)
	}
}

func TestLoadRejectsNonPositiveRuntimeIntervals(t *testing.T) {
	tests := map[string]string{
		"batch interval zero":        "batch_interval: 0s",
		"sender interval negative":   "sender_flush_interval: -1s",
		"spool age zero":             "max_spool_age: 0s",
		"collector interval zero":    "collector_interval_metric: 0s",
		"collector interval unknown": "collector_interval_not_real: 1m",
	}
	for name, setting := range tests {
		t.Run(name, func(t *testing.T) {
			path := writeConfigText(t, "manager_url: https://manager.local\nenrollment_token: token\n"+setting+"\n")
			if _, err := Load(path); err == nil {
				t.Fatalf("Load accepted invalid setting %q", setting)
			}
		})
	}
}

func TestLoadRejectsInconsistentRuntimeLimits(t *testing.T) {
	tests := map[string]string{
		"spool cannot hold record": strings.Join([]string{
			"max_spool_record_bytes: 2mb",
			"max_spool_bytes: 1mb",
			"max_batch_bytes: 1mb",
			"max_item_bytes: 256kb",
		}, "\n"),
		"item exceeds batch": strings.Join([]string{
			"max_batch_bytes: 256kb",
			"max_item_bytes: 512kb",
		}, "\n"),
		"batch exceeds record": strings.Join([]string{
			"max_spool_record_bytes: 1mb",
			"max_batch_bytes: 2mb",
			"max_item_bytes: 512kb",
		}, "\n"),
	}
	for name, settings := range tests {
		t.Run(name, func(t *testing.T) {
			path := writeConfigText(t, "manager_url: https://manager.local\nenrollment_token: token\n"+settings+"\n")
			if _, err := Load(path); err == nil {
				t.Fatalf("Load accepted inconsistent settings:\n%s", settings)
			}
		})
	}
}

func TestDiscoverUpdateDirIgnoresUnrelatedInvalidConfig(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")
	if err := os.WriteFile(path, []byte("this line breaks the full parser\nupdate_dir: ./recovery-updates\nunknown: value\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	updateDir, err := DiscoverUpdateDir(path)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(dir, "recovery-updates")
	if updateDir != want {
		t.Fatalf("DiscoverUpdateDir = %q, want %q", updateDir, want)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("full config unexpectedly accepted invalid syntax")
	}
}

func loadConfigText(t *testing.T, content string) Config {
	t.Helper()
	path := writeConfigText(t, content)
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}
	return cfg
}

func writeConfigText(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.yaml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}
