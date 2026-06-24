package main

import (
  "context"
  "crypto/ecdsa"
  "crypto/tls"
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
  "castrelyx/agent/internal/updater"
)

const agentVersion = "0.1.0"
const renewBefore = 7 * 24 * time.Hour

func main() {
  configPath := flag.String("config", defaultConfigPath(), "path to agent config file")
  once := flag.Bool("once", false, "run collectors once and exit")
  updateHelper := flag.Bool("update-helper", false, "run update helper")
  updateState := flag.String("update-state", "", "path to update state")
  updateService := flag.String("update-service", "CastrelyxAgent", "service name to restart after update")
  flag.Parse()

  if *updateHelper {
    if err := updater.RunWindowsHelper(*updateState, *updateService); err != nil {
      log.Fatal(err)
    }
    return
  }

  if err := runServiceOrConsole(*configPath, *once); err != nil {
    log.Fatal(err)
  }
}

func runAgent(parentCtx context.Context, configPath string, once bool) error {
  cfg, err := config.Load(configPath)
  if err != nil {
    return fmt.Errorf("load config: %w", err)
  }
  collectors.AgentVersion = agentVersion

  queue, err := spool.Open(cfg.SpoolDir, cfg.MaxSpoolRecord)
  if err != nil {
    return fmt.Errorf("open spool: %w", err)
  }

  collectorSet, err := collectors.BuildWithOptions(cfg.EnabledCollectors, collectors.Options{
    LogCursorPath:      cfg.LogCursorPath,
    LogMessageMaxBytes: cfg.LogMessageMaxBytes,
  })
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
  agentUpdater := buildUpdater(cfg, tlsConfig)
  collectors.UpdateStatusProvider = func() (string, string, string) {
    if agentUpdater == nil {
      return cfg.UpdateChannel, "disabled", ""
    }
    status, message := agentUpdater.Status()
    return cfg.UpdateChannel, status, message
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
    err := runtime.RunOnce(ctx)
    if err == nil && agentUpdater != nil {
      _ = agentUpdater.MarkApplied(ctx)
    }
    return err
  }

  ticker := time.NewTicker(cfg.BatchInterval)
  defer ticker.Stop()
  nextUpdateCheck := time.Now()

  for {
    if err := runtime.RunOnce(ctx); err != nil {
      if agentUpdater != nil && agentUpdater.HasPendingApply() {
        rollbackErr := agentUpdater.Rollback(ctx, "first post-update collection failed: "+err.Error())
        if errors.Is(rollbackErr, updater.ErrRestartRequired) {
          log.Print("agent rollback applied; restarting")
          return nil
        }
        if rollbackErr != nil {
          log.Printf("agent rollback failed: %v", rollbackErr)
        }
      }
      log.Printf("collector batch failed: %v", err)
    } else if agentUpdater != nil {
      if err := agentUpdater.MarkApplied(ctx); err != nil {
        log.Printf("agent update status report failed: %v", err)
      }
      if !time.Now().Before(nextUpdateCheck) {
        if err := agentUpdater.CheckAndApply(ctx); err != nil {
          if errors.Is(err, updater.ErrRestartRequired) {
            log.Print("agent update applied; restarting")
            return nil
          }
          log.Printf("agent update check failed: %v", err)
        }
        nextUpdateCheck = time.Now().Add(cfg.UpdateCheckInterval)
      }
    }

    select {
    case <-ctx.Done():
      log.Print("agent stopped")
      return nil
    case <-ticker.C:
    }
  }
}

func buildUpdater(cfg config.Config, tlsConfig *tls.Config) *updater.Updater {
  if !cfg.UpdateEnabled {
    return nil
  }
  if cfg.UpdatePublicKeyPath == "" {
    log.Print("agent updates disabled: update_public_key_path is not configured")
    return nil
  }
  if _, err := os.Stat(cfg.UpdatePublicKeyPath); err != nil {
    log.Printf("agent updates disabled: update public key unavailable: %v", err)
    return nil
  }
  updateClient, err := updater.New(updater.Config{
    BaseURL:       cfg.ManagerURL,
    AgentID:       cfg.AgentID,
    Version:       agentVersion,
    Channel:       cfg.UpdateChannel,
    UpdateDir:     cfg.UpdateDir,
    PublicKeyPath: cfg.UpdatePublicKeyPath,
    TLSConfig:     tlsConfig,
  })
  if err != nil {
    log.Printf("agent updates disabled: %v", err)
    return nil
  }
  return updateClient
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
