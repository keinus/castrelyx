package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log"
	"math/rand"
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
	"castrelyx/agent/internal/filemanager"
	"castrelyx/agent/internal/remotetasks"
	"castrelyx/agent/internal/sender"
	"castrelyx/agent/internal/spool"
	"castrelyx/agent/internal/tlsidentity"
	"castrelyx/agent/internal/updater"
)

const agentVersion = "0.2.2"
const renewBefore = 7 * 24 * time.Hour
const identityCheckInterval = time.Hour
const identityRetryBase = time.Minute
const identityRetryMax = 15 * time.Minute

var errIdentityRefreshRestart = errors.New("mTLS identity refreshed; service restart required")

func main() {
	configPath := flag.String("config", defaultConfigPath(), "path to agent config file")
	once := flag.Bool("once", false, "run collectors once and exit")
	showVersion := flag.Bool("version", false, "print agent version and exit")
	updateHelper := flag.Bool("update-helper", false, "run update helper")
	updateState := flag.String("update-state", "", "path to update state")
	updateService := flag.String("update-service", "CastrelyxAgent", "service name to restart after update")
	flag.Parse()
	if *showVersion {
		fmt.Println(agentVersion)
		return
	}

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
	updateDir, _ := config.DiscoverUpdateDir(configPath)
	probationPending, err := updater.PrepareUpdateStartup(parentCtx, updateDir)
	if err != nil {
		if errors.Is(err, updater.ErrRestartRequired) {
			log.Print("agent update startup recovery requested restart")
			return nil
		}
		return fmt.Errorf("prepare agent update startup: %w", err)
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	collectors.AgentVersion = agentVersion

	queue, err := spool.OpenWithOptions(cfg.SpoolDir, spool.Options{
		MaxRecordBytes: cfg.MaxSpoolRecord,
		MaxBytes:       cfg.MaxSpoolBytes,
		MaxRecords:     cfg.MaxSpoolRecords,
		MaxAge:         cfg.MaxSpoolAge,
	})
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

	identity, err := ensureIdentity(ctx, cfg, certPaths)
	if err != nil {
		return fmt.Errorf("ensure mTLS identity: %w", err)
	}

	managerTLSConfig, err := tlsidentity.BuildTLSConfig(certPaths, cfg.TLSServerName)
	if err != nil {
		return fmt.Errorf("build manager tls config: %w", err)
	}
	telemetryServerName := cfg.TLSServerName
	if cfg.IngestTransport == "tcp_mtls" {
		telemetryServerName = cfg.TCPIngestServerName
	}
	tlsConfig, err := tlsidentity.BuildTLSConfig(certPaths, telemetryServerName)
	if err != nil {
		return fmt.Errorf("build tls config: %w", err)
	}
	agentUpdater := buildUpdater(cfg, managerTLSConfig)
	if agentUpdater != nil {
		if err := agentUpdater.ResumePendingReplacement(ctx); err != nil {
			if errors.Is(err, updater.ErrRestartRequired) {
				log.Print("pending agent rollback resumed; restarting")
				return nil
			}
			return fmt.Errorf("resume pending agent replacement: %w", err)
		}
	}
	// Only an APPLYING state that existed before this process started is eligible
	// for first-collection rollback. A state created by the concurrent updater
	// loop belongs to the next process and must not be rolled back by this one.
	pendingApplyAtStartup := agentUpdater != nil && agentUpdater.HasPendingApply()
	remoteTaskClient := buildRemoteTaskClient(cfg, managerTLSConfig)
	fileManagerClient := buildFileManagerClient(cfg, managerTLSConfig)
	collectors.UpdateStatusProvider = func() (string, string, string) {
		if agentUpdater == nil {
			return cfg.UpdateChannel, "disabled", ""
		}
		status, message := agentUpdater.Status()
		return cfg.UpdateChannel, status, message
	}

	var telemetrySender agentruntime.Sender
	if cfg.IngestTransport == "tcp_mtls" {
		telemetrySender, err = sender.NewTCPMTLSWithMaxIdle(cfg.TCPIngestAddr, tlsConfig, cfg.TCPIngestMaxIdle)
	} else {
		telemetrySender, err = sender.New(identity.IngestURL, tlsConfig)
	}
	if err != nil {
		return fmt.Errorf("create sender: %w", err)
	}
	runtime := agentruntime.New(agentruntime.Config{
		AgentID:                cfg.AgentID,
		TenantID:               cfg.TenantID,
		CollectorIntervals:     cfg.CollectorIntervals,
		CollectorFullIntervals: cfg.CollectorFullIntervals,
		MaxBatchItems:          cfg.MaxBatchItems,
		MaxBatchBytes:          cfg.MaxBatchBytes,
		MaxItemBytes:           cfg.MaxItemBytes,
		StatePath:              filepath.Join(cfg.SpoolDir, "state-cache.json"),
	}, telemetrySender, queue, collectorSet)
	collectors.HealthStatusProvider = runtime.HealthSnapshot

	if once {
		err := runtime.RunOnce(ctx)
		if err == nil && probationPending {
			if !runtime.StartupCollectionHealthy() {
				return errors.New("agent update startup probation failed: critical collectors did not succeed")
			}
			if healthErr := updater.MarkUpdateStartupHealthy(updateDir); healthErr != nil {
				return fmt.Errorf("persist agent update startup health: %w", healthErr)
			}
			probationPending = false
		}
		if err == nil && remoteTaskClient != nil {
			_ = remoteTaskClient.PollAndRun(ctx)
		}
		if err == nil && agentUpdater != nil {
			_ = agentUpdater.MarkApplied(ctx)
		}
		return err
	}

	restartRequired := make(chan string, 1)
	identityRestartRequired := make(chan error, 1)
	if fileManagerClient != nil {
		go fileManagerClient.Run(ctx)
	}
	go runSenderLoop(ctx, runtime, cfg.SenderFlushInterval)
	if remoteTaskClient != nil {
		go runRemoteTaskLoop(ctx, remoteTaskClient, cfg.RemoteTaskInterval)
	}
	collectionSucceeded := make(chan struct{}, 1)
	if agentUpdater != nil {
		go runUpdaterLoop(ctx, agentUpdater, cfg.UpdateCheckInterval, collectionSucceeded, restartRequired, pendingApplyAtStartup)
	}
	go runIdentityMaintenanceLoop(ctx, cfg, certPaths, identity.RefreshDeferred, identityRestartRequired)

	for {
		if err := runtime.CollectOnce(ctx); err != nil {
			if agentUpdater != nil && pendingApplyAtStartup && shouldRollbackPostUpdateCollection(err) {
				rollbackErr := agentUpdater.Rollback(ctx, "first post-update collection failed: "+err.Error())
				if errors.Is(rollbackErr, updater.ErrRestartRequired) {
					log.Print("agent rollback applied; restarting")
					return nil
				}
				if rollbackErr != nil {
					log.Printf("agent rollback failed: %v", rollbackErr)
				}
			}
			log.Printf("collector enqueue failed: %v", err)
		} else {
			if probationPending {
				if !runtime.StartupCollectionHealthy() {
					return errors.New("agent update startup probation failed: critical collectors did not succeed")
				}
				if err := updater.MarkUpdateStartupHealthy(updateDir); err != nil {
					return fmt.Errorf("persist agent update startup health: %w", err)
				}
				probationPending = false
			}
			pendingApplyAtStartup = false
			select {
			case collectionSucceeded <- struct{}{}:
			default:
			}
		}

		timer := time.NewTimer(jitter(cfg.BatchInterval, 0.1))
		select {
		case <-ctx.Done():
			stopTimer(timer)
			log.Print("agent stopped")
			return nil
		case reason := <-restartRequired:
			stopTimer(timer)
			log.Printf("%s; restarting", reason)
			return nil
		case restartErr := <-identityRestartRequired:
			stopTimer(timer)
			return restartErr
		case <-timer.C:
		}
	}
}

func shouldRollbackPostUpdateCollection(err error) bool {
	return err != nil && !errors.Is(err, agentruntime.ErrLocalPersistence)
}

func runSenderLoop(ctx context.Context, runtime *agentruntime.Agent, interval time.Duration) {
	backoff := interval
	for {
		if err := runtime.FlushPending(ctx, 25); err != nil {
			log.Printf("telemetry delivery failed: %v", err)
			backoff = minDuration(maxDuration(backoff*2, time.Second), time.Minute)
		} else {
			backoff = interval
		}
		timer := time.NewTimer(jitter(backoff, 0.2))
		select {
		case <-ctx.Done():
			stopTimer(timer)
			return
		case <-timer.C:
		}
	}
}

func runRemoteTaskLoop(ctx context.Context, client *remotetasks.Client, interval time.Duration) {
	backoff := interval
	for {
		if err := client.PollAndRun(ctx); err != nil {
			log.Printf("remote task check failed: %v", err)
			backoff = minDuration(maxDuration(backoff*2, time.Second), time.Minute)
		} else {
			backoff = interval
		}
		timer := time.NewTimer(jitter(backoff, 0.2))
		select {
		case <-ctx.Done():
			stopTimer(timer)
			return
		case <-timer.C:
		}
	}
}

type updateLoopClient interface {
	CheckAndApply(context.Context) error
	MarkApplied(context.Context) error
	HasPendingApply() bool
}

func runUpdaterLoop(ctx context.Context, agentUpdater updateLoopClient, interval time.Duration, collectionSucceeded <-chan struct{}, restartRequired chan<- string, pendingApplyAtStartup bool) {
	var checkTimer *time.Timer
	var checkTimerC <-chan time.Time
	armCheckTimer := func(delay time.Duration) {
		if checkTimer == nil {
			checkTimer = time.NewTimer(jitter(delay, 0.2))
		} else {
			stopTimer(checkTimer)
			checkTimer.Reset(jitter(delay, 0.2))
		}
		checkTimerC = checkTimer.C
	}
	if !pendingApplyAtStartup {
		armCheckTimer(time.Second)
	}
	defer func() {
		if checkTimer != nil {
			checkTimer.Stop()
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-collectionSucceeded:
			if err := agentUpdater.MarkApplied(ctx); err != nil {
				if errors.Is(err, updater.ErrRestartRequired) {
					select {
					case restartRequired <- "pending agent replacement resumed":
					default:
					}
					return
				}
				log.Printf("agent update status report failed: %v", err)
			}
			if checkTimerC == nil && !agentUpdater.HasPendingApply() {
				armCheckTimer(time.Second)
			}
		case <-checkTimerC:
			checkTimerC = nil
			if err := agentUpdater.CheckAndApply(ctx); err != nil {
				if errors.Is(err, updater.ErrRestartRequired) {
					select {
					case restartRequired <- "agent update applied":
					default:
					}
					return
				}
				log.Printf("agent update check failed: %v", err)
			}
			armCheckTimer(interval)
		}
	}
}

func jitter(base time.Duration, fraction float64) time.Duration {
	if base <= 0 || fraction <= 0 {
		return base
	}
	factor := 1 - fraction + rand.Float64()*(2*fraction)
	return time.Duration(float64(base) * factor)
}

func minDuration(left, right time.Duration) time.Duration {
	if left < right {
		return left
	}
	return right
}

func maxDuration(left, right time.Duration) time.Duration {
	if left > right {
		return left
	}
	return right
}

func stopTimer(timer *time.Timer) {
	if timer.Stop() {
		return
	}
	select {
	case <-timer.C:
	default:
	}
}

func buildRemoteTaskClient(cfg config.Config, tlsConfig *tls.Config) *remotetasks.Client {
	if !cfg.RemoteTasksEnabled {
		return nil
	}
	client, err := remotetasks.New(remotetasks.Config{
		BaseURL:   cfg.ManagerURL,
		AgentID:   cfg.AgentID,
		TLSConfig: tlsConfig,
	})
	if err != nil {
		log.Printf("remote tasks disabled: %v", err)
		return nil
	}
	return client
}

func buildFileManagerClient(cfg config.Config, tlsConfig *tls.Config) *filemanager.Client {
	if !cfg.FileManagerEnabled {
		return nil
	}
	roots := make([]filemanager.Root, 0, len(cfg.FileManagerRoots))
	for _, path := range cfg.FileManagerRoots {
		roots = append(roots, filemanager.Root{Name: path, Path: path})
	}
	client, err := filemanager.New(filemanager.Config{
		BaseURL:          cfg.ManagerURL,
		AgentID:          cfg.AgentID,
		Roots:            roots,
		AllowDelete:      cfg.FileManagerAllowDelete,
		MaxTransferBytes: cfg.FileManagerMaxTransferBytes,
		PollInterval:     cfg.FileManagerPollInterval,
		TLSConfig:        tlsConfig,
	})
	if err != nil {
		log.Printf("file manager disabled: %v", err)
		return nil
	}
	return client
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

type identityBootstrap struct {
	IngestURL       string
	RefreshDeferred bool
	MaterialChanged bool
}

type identityEnsurer func(context.Context, config.Config, tlsidentity.Paths) (identityBootstrap, error)

type identityMaintenanceOptions struct {
	Ensure        identityEnsurer
	CheckInterval time.Duration
	RetryBase     time.Duration
	RetryMax      time.Duration
}

func ensureIdentity(ctx context.Context, cfg config.Config, paths tlsidentity.Paths) (identityBootstrap, error) {
	if err := tlsidentity.RecoverEnrollment(paths); err != nil {
		if errors.Is(err, tlsidentity.ErrEnrollmentArtifactCleanup) {
			log.Printf("stale mTLS identity transaction artifact cleanup deferred: %v", err)
		} else {
			return identityBootstrap{}, fmt.Errorf("recover interrupted identity update: %w", err)
		}
	}
	status, err := tlsidentity.CertificateStatus(paths, renewBefore)
	if err != nil {
		return identityBootstrap{}, err
	}
	if !status.NeedsRenewal {
		identityStatus, err := tlsidentity.ValidateClientIdentity(paths, cfg.AgentID, false)
		if err != nil {
			return identityBootstrap{}, fmt.Errorf("validate current client identity: %w", err)
		}
		ingestURL, err := identityIngestURL(cfg, paths, identityStatus.ExpiresAt, false)
		if err != nil {
			return identityBootstrap{}, err
		}
		return identityBootstrap{IngestURL: ingestURL}, nil
	}

	key, err := tlsidentity.EnsurePrivateKey(paths)
	if err != nil {
		return identityBootstrap{}, err
	}
	hostname, _ := os.Hostname()
	request, err := enrollmentRequest(cfg.AgentID, hostname, key)
	if err != nil {
		return identityBootstrap{}, err
	}

	var response enrollment.Response
	if status.Exists && time.Now().Before(status.ExpiresAt) {
		if _, err := tlsidentity.ValidateClientIdentity(paths, cfg.AgentID, false); err != nil {
			return identityBootstrap{}, fmt.Errorf("validate renewable client identity: %w", err)
		}
		currentTLS, err := tlsidentity.BuildTLSConfig(paths, cfg.TLSServerName)
		if err != nil {
			return identityBootstrap{}, err
		}
		renewClient := enrollment.New(cfg.ManagerURL, "", cfg.TLSServerName, &http.Transport{TLSClientConfig: currentTLS})
		defer renewClient.CloseIdleConnections()
		response, err = renewClient.Renew(ctx, request)
		if err != nil {
			return deferIdentityRefresh(cfg, paths, fmt.Errorf("renew client certificate: %w", err))
		}
	} else {
		if cfg.EnrollmentToken == "" {
			return deferIdentityRefresh(cfg, paths, errors.New("enrollment token is required when no valid client certificate exists"))
		}
		transport, err := enrollmentTransport(paths, cfg.TLSServerName)
		if err != nil {
			return identityBootstrap{}, err
		}
		enrollClient := enrollment.New(cfg.ManagerURL, cfg.EnrollmentToken, cfg.TLSServerName, transport)
		defer enrollClient.CloseIdleConnections()
		response, err = enrollClient.Enroll(ctx, request)
		if err != nil {
			return deferIdentityRefresh(cfg, paths, fmt.Errorf("enroll client certificate: %w", err))
		}
	}

	expiresAt, err := time.Parse(time.RFC3339, response.ExpiresAt)
	if err != nil {
		return identityBootstrap{}, fmt.Errorf("parse enrollment expires_at: %w", err)
	}
	metadata := tlsidentity.Metadata{
		AgentID:   response.AgentID,
		IngestURL: response.IngestURL,
		ExpiresAt: expiresAt,
	}
	commit, saveErr := tlsidentity.SaveEnrollment(paths, []byte(response.CACertPEM), []byte(response.ClientCertPEM), cfg.AgentID, metadata)
	if !commit.Committed {
		if saveErr == nil {
			saveErr = errors.New("identity transaction did not commit")
		}
		return identityBootstrap{}, saveErr
	}
	if saveErr != nil {
		log.Printf("mTLS identity committed with deferred artifact cleanup: %v", saveErr)
	}
	if !commit.Changed {
		log.Print("mTLS identity refresh returned unchanged material; retrying with renewal backoff")
		return identityBootstrap{IngestURL: response.IngestURL, RefreshDeferred: true}, nil
	}
	return identityBootstrap{IngestURL: response.IngestURL, MaterialChanged: commit.Changed}, nil
}

func identityIngestURL(cfg config.Config, paths tlsidentity.Paths, certificateExpiresAt time.Time, allowExpired bool) (string, error) {
	defaultURL := cfg.ManagerURL + "/api/agent/ingest"
	metadata, err := tlsidentity.LoadMetadata(paths)
	if errors.Is(err, os.ErrNotExist) {
		return defaultURL, nil
	}
	if err != nil {
		log.Printf("identity metadata unavailable; using configured manager ingest URL: %v", err)
		return defaultURL, nil
	}
	if err := tlsidentity.ValidateEnrollmentMetadata(metadata, cfg.AgentID, certificateExpiresAt, allowExpired); err != nil {
		log.Printf("identity metadata rejected; using configured manager ingest URL: %v", err)
		return defaultURL, nil
	}
	return metadata.IngestURL, nil
}

func deferIdentityRefresh(cfg config.Config, paths tlsidentity.Paths, refreshErr error) (identityBootstrap, error) {
	// Loading the complete key pair and CA is the safety boundary for degraded
	// startup. Network TLS remains strict; only local collection and spooling
	// continue while renewal is retried in the background.
	identityStatus, err := tlsidentity.ValidateClientIdentity(paths, cfg.AgentID, true)
	if err != nil {
		return identityBootstrap{}, errors.Join(refreshErr, fmt.Errorf("existing local mTLS identity is unusable: %w", err))
	}
	ingestURL, err := identityIngestURL(cfg, paths, identityStatus.ExpiresAt, true)
	if err != nil {
		return identityBootstrap{}, errors.Join(refreshErr, err)
	}
	log.Printf("mTLS identity refresh deferred; continuing local collection with the existing strict TLS identity: %v", refreshErr)
	return identityBootstrap{IngestURL: ingestURL, RefreshDeferred: true}, nil
}

func runIdentityMaintenanceLoop(ctx context.Context, cfg config.Config, paths tlsidentity.Paths, initialDeferred bool, restartRequired chan<- error) {
	runIdentityMaintenanceLoopWithOptions(ctx, cfg, paths, initialDeferred, restartRequired, identityMaintenanceOptions{
		Ensure:        ensureIdentity,
		CheckInterval: identityCheckInterval,
		RetryBase:     identityRetryBase,
		RetryMax:      identityRetryMax,
	})
}

func runIdentityMaintenanceLoopWithOptions(ctx context.Context, cfg config.Config, paths tlsidentity.Paths, initialDeferred bool, restartRequired chan<- error, options identityMaintenanceOptions) {
	if options.Ensure == nil {
		options.Ensure = ensureIdentity
	}
	if options.CheckInterval <= 0 {
		options.CheckInterval = identityCheckInterval
	}
	if options.RetryBase <= 0 {
		options.RetryBase = identityRetryBase
	}
	if options.RetryMax < options.RetryBase {
		options.RetryMax = options.RetryBase
	}

	backoff := options.RetryBase
	delay := options.CheckInterval
	if initialDeferred {
		delay = backoff
		backoff = minDuration(backoff*2, options.RetryMax)
	}
	for {
		timer := time.NewTimer(jitter(delay, 0.1))
		select {
		case <-ctx.Done():
			stopTimer(timer)
			return
		case <-timer.C:
		}

		identity, err := options.Ensure(ctx, cfg, paths)
		if err != nil {
			log.Printf("mTLS identity maintenance failed: %v", err)
			delay = backoff
			backoff = minDuration(backoff*2, options.RetryMax)
			continue
		}
		if identity.RefreshDeferred {
			delay = backoff
			backoff = minDuration(backoff*2, options.RetryMax)
			continue
		}

		if identity.MaterialChanged {
			select {
			case restartRequired <- errIdentityRefreshRestart:
			case <-ctx.Done():
			}
			return
		}

		backoff = options.RetryBase
		delay = options.CheckInterval
	}
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
