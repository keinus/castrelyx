package main

import (
  "context"
  "crypto/ecdsa"
  "errors"
  "flag"
  "fmt"
  "log"
  "net/http"
  "os"
  "os/signal"
  "path/filepath"
  "syscall"
  "time"

  agentruntime "castrelyx/agent/internal/agent"
  "castrelyx/agent/internal/collectors"
  "castrelyx/agent/internal/config"
  "castrelyx/agent/internal/enrollment"
  "castrelyx/agent/internal/sender"
  "castrelyx/agent/internal/spool"
  "castrelyx/agent/internal/tlsidentity"
)

const agentVersion = "0.1.0"
const renewBefore = 7 * 24 * time.Hour

func main() {
  configPath := flag.String("config", defaultConfigPath(), "path to agent config file")
  once := flag.Bool("once", false, "run collectors once and exit")
  flag.Parse()

  if err := runServiceOrConsole(*configPath, *once); err != nil {
    log.Fatal(err)
  }
}

func runAgent(parentCtx context.Context, configPath string, once bool) error {
  cfg, err := config.Load(configPath)
  if err != nil {
    return fmt.Errorf("load config: %w", err)
  }

  queue, err := spool.Open(cfg.SpoolDir, cfg.MaxSpoolRecord)
  if err != nil {
    return fmt.Errorf("open spool: %w", err)
  }

  collectorSet, err := collectors.Build(cfg.EnabledCollectors)
  if err != nil {
    return fmt.Errorf("build collectors: %w", err)
  }

  certPaths := tlsidentity.Paths{
    CertDir:        cfg.CertDir,
    CACertPath:     cfg.CACertPath,
    ClientCertPath: cfg.ClientCertPath,
    ClientKeyPath:  cfg.ClientKeyPath,
    MetadataPath:   filepath.Join(cfg.CertDir, "enrollment.json"),
  }

  ctx, stop := signal.NotifyContext(parentCtx, os.Interrupt, syscall.SIGTERM)
  defer stop()

  ingestURL, err := ensureIdentity(ctx, cfg, certPaths)
  if err != nil {
    return fmt.Errorf("ensure mTLS identity: %w", err)
  }

  telemetryServerName := cfg.TLSServerName
  if cfg.IngestTransport == "tcp_mtls" {
    telemetryServerName = cfg.TCPIngestServerName
  }
  tlsConfig, err := tlsidentity.BuildTLSConfig(certPaths, telemetryServerName)
  if err != nil {
    return fmt.Errorf("build tls config: %w", err)
  }
  var telemetrySender agentruntime.Sender
  if cfg.IngestTransport == "tcp_mtls" {
    telemetrySender, err = sender.NewTCPMTLS(cfg.TCPIngestAddr, tlsConfig)
  } else {
    telemetrySender, err = sender.New(ingestURL, tlsConfig)
  }
  if err != nil {
    return fmt.Errorf("create sender: %w", err)
  }
  runtime := agentruntime.New(agentruntime.Config{
    AgentID:  cfg.AgentID,
    TenantID: cfg.TenantID,
  }, telemetrySender, queue, collectorSet)

  if once {
    return runtime.RunOnce(ctx)
  }

  ticker := time.NewTicker(cfg.BatchInterval)
  defer ticker.Stop()

  for {
    if err := runtime.RunOnce(ctx); err != nil {
      log.Printf("collector batch failed: %v", err)
    }

    select {
    case <-ctx.Done():
      log.Print("agent stopped")
      return nil
    case <-ticker.C:
    }
  }
}

func defaultConfigPath() string {
  if os.Getenv("ProgramData") != "" {
    return os.Getenv("ProgramData") + `\Castrelyx\agent.yaml`
  }
  return "/etc/castrelyx/agent.yaml"
}

func ensureIdentity(ctx context.Context, cfg config.Config, paths tlsidentity.Paths) (string, error) {
  status, err := tlsidentity.CertificateStatus(paths, renewBefore)
  if err != nil {
    return "", err
  }
  if !status.NeedsRenewal {
    if metadata, err := tlsidentity.LoadMetadata(paths); err == nil && metadata.IngestURL != "" {
      return metadata.IngestURL, nil
    }
    return cfg.ManagerURL + "/api/agent/ingest", nil
  }

  key, err := tlsidentity.EnsurePrivateKey(paths)
  if err != nil {
    return "", err
  }
  hostname, _ := os.Hostname()
  request, err := enrollmentRequest(cfg.AgentID, hostname, key)
  if err != nil {
    return "", err
  }

  var response enrollment.Response
  if status.Exists && time.Now().Before(status.ExpiresAt) {
    currentTLS, err := tlsidentity.BuildTLSConfig(paths, cfg.TLSServerName)
    if err != nil {
      return "", err
    }
    renewClient := enrollment.New(cfg.ManagerURL, "", cfg.TLSServerName, &http.Transport{TLSClientConfig: currentTLS})
    response, err = renewClient.Renew(ctx, request)
    if err != nil {
      return "", err
    }
  } else {
    if cfg.EnrollmentToken == "" {
      return "", fmt.Errorf("enrollment token is required when no valid client certificate exists")
    }
    transport, err := enrollmentTransport(paths, cfg.TLSServerName)
    if err != nil {
      return "", err
    }
    enrollClient := enrollment.New(cfg.ManagerURL, cfg.EnrollmentToken, cfg.TLSServerName, transport)
    response, err = enrollClient.Enroll(ctx, request)
    if err != nil {
      return "", err
    }
  }

  if err := tlsidentity.SaveCertificates(paths, []byte(response.CACertPEM), []byte(response.ClientCertPEM)); err != nil {
    return "", err
  }
  expiresAt, err := time.Parse(time.RFC3339, response.ExpiresAt)
  if err != nil {
    return "", fmt.Errorf("parse enrollment expires_at: %w", err)
  }
  if err := tlsidentity.SaveMetadata(paths, tlsidentity.Metadata{
    AgentID:   response.AgentID,
    IngestURL: response.IngestURL,
    ExpiresAt: expiresAt,
  }); err != nil {
    return "", err
  }
  return response.IngestURL, nil
}

func enrollmentTransport(paths tlsidentity.Paths, serverName string) (http.RoundTripper, error) {
  tlsConfig, err := tlsidentity.BuildRootTLSConfig(paths, serverName)
  if err != nil {
    if errors.Is(err, os.ErrNotExist) {
      return nil, nil
    }
    return nil, err
  }
  return &http.Transport{TLSClientConfig: tlsConfig}, nil
}

func enrollmentRequest(agentID, hostname string, key *ecdsa.PrivateKey) (enrollment.Request, error) {
  csrPEM, err := tlsidentity.CreateCSR(key, tlsidentity.CSRInfo{
    AgentID:  agentID,
    Hostname: hostname,
    Version:  agentVersion,
  })
  if err != nil {
    return enrollment.Request{}, err
  }
  return enrollment.Request{
    AgentID:  agentID,
    Hostname: hostname,
    Version:  agentVersion,
    CSRPem:   string(csrPEM),
  }, nil
}
