package config

import (
  "os"
  "path/filepath"
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
collectors:
  - identity
  - metric
  - log_tailer
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
  if got := cfg.EnabledCollectors; len(got) != 3 || got[0] != "identity" || got[2] != "log_tailer" {
    t.Fatalf("EnabledCollectors = %#v", got)
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
