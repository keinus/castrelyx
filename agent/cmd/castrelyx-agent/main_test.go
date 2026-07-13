package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"castrelyx/agent/internal/agent"
	"castrelyx/agent/internal/config"
	"castrelyx/agent/internal/tlsidentity"
	"castrelyx/agent/internal/updater"
)

type fakeUpdateLoopClient struct {
	mu          sync.Mutex
	pending     bool
	markOnce    sync.Once
	markCalled  chan struct{}
	checkCalled chan struct{}
	markErr     error
}

func (f *fakeUpdateLoopClient) CheckAndApply(context.Context) error {
	f.checkCalled <- struct{}{}
	return nil
}

func (f *fakeUpdateLoopClient) MarkApplied(context.Context) error {
	f.mu.Lock()
	f.pending = false
	f.mu.Unlock()
	f.markOnce.Do(func() { close(f.markCalled) })
	return f.markErr
}

func TestRunUpdaterLoopPropagatesRestartRequiredFromMarkApplied(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &fakeUpdateLoopClient{
		pending:     true,
		markCalled:  make(chan struct{}),
		checkCalled: make(chan struct{}, 1),
		markErr:     updater.ErrRestartRequired,
	}
	collectionSucceeded := make(chan struct{}, 1)
	restartRequired := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		runUpdaterLoop(ctx, client, time.Hour, collectionSucceeded, restartRequired, true)
		close(done)
	}()

	collectionSucceeded <- struct{}{}
	select {
	case reason := <-restartRequired:
		if !strings.Contains(reason, "replacement resumed") {
			t.Fatalf("restart reason = %q", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("restart-required MarkApplied result was not propagated")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("updater loop did not stop after restart request")
	}
}

func (f *fakeUpdateLoopClient) HasPendingApply() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.pending
}

func TestRunUpdaterLoopDefersChecksUntilStartupApplyIsMarked(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	client := &fakeUpdateLoopClient{
		pending:     true,
		markCalled:  make(chan struct{}),
		checkCalled: make(chan struct{}, 1),
	}
	collectionSucceeded := make(chan struct{}, 1)
	restartRequired := make(chan string, 1)
	done := make(chan struct{})
	go func() {
		runUpdaterLoop(ctx, client, time.Hour, collectionSucceeded, restartRequired, true)
		close(done)
	}()

	select {
	case <-client.checkCalled:
		t.Fatal("update check ran while startup apply was still pending")
	case <-time.After(150 * time.Millisecond):
	}
	collectionSucceeded <- struct{}{}
	select {
	case <-client.markCalled:
	case <-time.After(time.Second):
		t.Fatal("startup apply was not marked after successful collection")
	}
	select {
	case <-client.checkCalled:
	case <-time.After(2 * time.Second):
		t.Fatal("update checks did not resume after startup apply was marked")
	}

	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("updater loop did not stop")
	}
}

func TestPostUpdateRollbackIgnoresLocalPersistenceBackpressure(t *testing.T) {
	localFailure := errors.Join(agent.ErrLocalPersistence, errors.New("spool full"))
	if shouldRollbackPostUpdateCollection(localFailure) {
		t.Fatal("local spool/cache/cursor failure triggered update rollback")
	}
	if !shouldRollbackPostUpdateCollection(errors.New("batch serialization invariant failed")) {
		t.Fatal("non-persistence health failure did not trigger update rollback")
	}
}

func TestEnsureIdentityDefersRemoteRenewalWithCompleteLocalIdentity(t *testing.T) {
	paths := writeTestIdentity(t, time.Now().Add(time.Hour))
	if err := tlsidentity.SaveMetadata(paths, tlsidentity.Metadata{
		AgentID:   "agent-1",
		IngestURL: "https://ingest.internal/api/agent/ingest",
		ExpiresAt: time.Now().Add(time.Hour),
	}); err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{
		ManagerURL:      "https://127.0.0.1:1",
		AgentID:         "agent-1",
		TLSServerName:   "manager.internal",
		EnrollmentToken: "unused-for-valid-certificate",
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	identity, err := ensureIdentity(ctx, cfg, paths)
	if err != nil {
		t.Fatalf("renewal outage rejected complete local identity: %v", err)
	}
	if !identity.RefreshDeferred {
		t.Fatal("renewal outage was not marked for background recovery")
	}
	if identity.IngestURL != "https://ingest.internal/api/agent/ingest" {
		t.Fatalf("ingest URL = %q", identity.IngestURL)
	}
	if _, err := tlsidentity.BuildTLSConfig(paths, cfg.TLSServerName); err != nil {
		t.Fatalf("degraded identity no longer enforces configured TLS material: %v", err)
	}
}

func TestEnsureIdentityDoesNotDeferWithoutUsableLocalIdentity(t *testing.T) {
	paths := tlsidentity.PathsFromDir(t.TempDir())
	cfg := config.Config{
		ManagerURL:      "https://127.0.0.1:1",
		AgentID:         "agent-1",
		TLSServerName:   "manager.internal",
		EnrollmentToken: "test-token",
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	identity, err := ensureIdentity(ctx, cfg, paths)
	if err == nil {
		t.Fatalf("missing local identity was accepted: %+v", identity)
	}
	if !strings.Contains(err.Error(), "existing local mTLS identity is unusable") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestEnsureIdentityRejectsLongLivedMismatchedAgentIdentity(t *testing.T) {
	paths := writeTestIdentity(t, time.Now().Add(30*24*time.Hour))
	cfg := config.Config{
		ManagerURL:    "https://127.0.0.1:1",
		AgentID:       "different-agent",
		TLSServerName: "manager.internal",
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	identity, err := ensureIdentity(ctx, cfg, paths)
	if err == nil {
		t.Fatalf("mismatched local identity was accepted: %+v", identity)
	}
	if !strings.Contains(err.Error(), "does not match agent id") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestEnsureIdentityContinuesAfterUnmarkedArtifactCleanupWarning(t *testing.T) {
	paths := writeTestIdentity(t, time.Now().Add(30*24*time.Hour))
	status, err := tlsidentity.CertificateStatus(paths, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := tlsidentity.SaveMetadata(paths, tlsidentity.Metadata{
		AgentID: "agent-1", IngestURL: "https://ingest.internal/api/agent/ingest", ExpiresAt: status.ExpiresAt,
	}); err != nil {
		t.Fatal(err)
	}
	staleArtifact := filepath.Join(filepath.Dir(paths.CACertPath), "."+filepath.Base(paths.CACertPath)+".identity-stage")
	if err := os.MkdirAll(staleArtifact, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(staleArtifact, "locked"), []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{ManagerURL: "https://manager.internal", AgentID: "agent-1", TLSServerName: "manager.internal"}

	identity, err := ensureIdentity(context.Background(), cfg, paths)
	if err != nil {
		t.Fatalf("non-transactional cleanup warning stopped startup: %v", err)
	}
	if identity.IngestURL != "https://ingest.internal/api/agent/ingest" {
		t.Fatalf("ingest URL = %q", identity.IngestURL)
	}
}

func TestEnsureIdentityRejectsUntrustedMetadataDestinationAndUsesConfiguredManager(t *testing.T) {
	paths := writeTestIdentity(t, time.Now().Add(30*24*time.Hour))
	status, err := tlsidentity.CertificateStatus(paths, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := tlsidentity.SaveMetadata(paths, tlsidentity.Metadata{
		AgentID: "different-agent", IngestURL: "https://unexpected.internal/ingest", ExpiresAt: status.ExpiresAt,
	}); err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{ManagerURL: "https://manager.internal", AgentID: "agent-1", TLSServerName: "manager.internal"}

	identity, err := ensureIdentity(context.Background(), cfg, paths)
	if err != nil {
		t.Fatalf("invalid optional metadata stopped startup: %v", err)
	}
	if identity.IngestURL != "https://manager.internal/api/agent/ingest" {
		t.Fatalf("untrusted metadata destination was used: %q", identity.IngestURL)
	}
}

func TestIdentityMaintenanceRestartsOnlyAfterRefreshReportsCommittedMaterial(t *testing.T) {
	paths := writeIdentityMaterialFixture(t)
	var calls atomic.Int32
	ensure := func(context.Context, config.Config, tlsidentity.Paths) (identityBootstrap, error) {
		if calls.Add(1) == 1 {
			return identityBootstrap{IngestURL: "https://ingest", RefreshDeferred: true}, nil
		}
		return identityBootstrap{IngestURL: "https://ingest", MaterialChanged: true}, nil
	}
	restartRequired := make(chan error, 2)
	done := make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	go func() {
		runIdentityMaintenanceLoopWithOptions(ctx, config.Config{}, paths, true, restartRequired, identityMaintenanceOptions{
			Ensure:        ensure,
			CheckInterval: time.Hour,
			RetryBase:     time.Millisecond,
			RetryMax:      2 * time.Millisecond,
		})
		close(done)
	}()

	select {
	case err := <-restartRequired:
		if !errors.Is(err, errIdentityRefreshRestart) {
			t.Fatalf("restart error = %v", err)
		}
	case <-ctx.Done():
		t.Fatal("recovered identity did not request restart")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("identity maintenance did not stop after requesting restart")
	}
	if calls.Load() != 2 {
		t.Fatalf("ensure calls = %d, want 2", calls.Load())
	}
	select {
	case err := <-restartRequired:
		t.Fatalf("duplicate restart request: %v", err)
	default:
	}
}

func TestIdentityMaintenanceFailureDoesNotRequestRestartAndHonorsCancellation(t *testing.T) {
	paths := writeIdentityMaterialFixture(t)
	var calls atomic.Int32
	ensure := func(context.Context, config.Config, tlsidentity.Paths) (identityBootstrap, error) {
		calls.Add(1)
		return identityBootstrap{}, errors.New("manager unavailable")
	}
	restartRequired := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		runIdentityMaintenanceLoopWithOptions(ctx, config.Config{}, paths, true, restartRequired, identityMaintenanceOptions{
			Ensure:        ensure,
			CheckInterval: time.Hour,
			RetryBase:     time.Millisecond,
			RetryMax:      2 * time.Millisecond,
		})
		close(done)
	}()
	deadline := time.Now().Add(time.Second)
	for calls.Load() < 2 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("identity maintenance ignored context cancellation")
	}
	if calls.Load() < 2 {
		t.Fatalf("retry calls = %d, want at least 2", calls.Load())
	}
	select {
	case err := <-restartRequired:
		t.Fatalf("failed refresh requested restart: %v", err)
	default:
	}
}

func writeTestIdentity(t *testing.T, clientNotAfter time.Time) tlsidentity.Paths {
	t.Helper()
	paths := tlsidentity.PathsFromDir(t.TempDir())
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test-ca"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(365 * 24 * time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	caCert, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	clientKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	clientTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "agent-1"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     clientNotAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	clientDER, err := x509.CreateCertificate(rand.Reader, clientTemplate, caCert, &clientKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(clientKey)
	if err != nil {
		t.Fatal(err)
	}
	writePEMFile(t, paths.CACertPath, "CERTIFICATE", caDER)
	writePEMFile(t, paths.ClientCertPath, "CERTIFICATE", clientDER)
	writePEMFile(t, paths.ClientKeyPath, "EC PRIVATE KEY", keyDER)
	return paths
}

func writePEMFile(t *testing.T, path, blockType string, der []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	data := pem.EncodeToMemory(&pem.Block{Type: blockType, Bytes: der})
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
}

func writeIdentityMaterialFixture(t *testing.T) tlsidentity.Paths {
	t.Helper()
	paths := tlsidentity.PathsFromDir(t.TempDir())
	for path, value := range map[string]string{
		paths.CACertPath:     "old-ca",
		paths.ClientCertPath: "old-client-certificate",
		paths.ClientKeyPath:  "old-client-key",
		paths.MetadataPath:   "old-metadata",
	} {
		if err := os.WriteFile(path, []byte(value), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return paths
}
