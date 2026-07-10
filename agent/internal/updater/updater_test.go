package updater

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestVerifyAcceptsSignedManifestAndMatchingArtifact(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := filepath.Join(dir, "update_public_key.pem")
	der, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(publicKeyPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}

	artifact := []byte("new-agent-binary")
	manifest := `{"version":"0.2.0","os":"` + runtimeOS() + `","arch":"` + runtimeArch() + `","channel":"stable","sha256":"` + sha256Hex(artifact) + `","size_bytes":16}`
	signature := base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, []byte(manifest)))
	u := Updater{config: Config{Version: "0.1.0", Channel: "stable", PublicKeyPath: publicKeyPath}}

	parsed, err := u.verify(CheckResponse{
		Release:   Release{Version: "0.2.0", OS: runtimeOS(), Arch: runtimeArch(), Channel: "stable"},
		Manifest:  manifest,
		Signature: signature,
	}, artifact)
	if err != nil {
		t.Fatalf("verify returned error: %v", err)
	}
	if parsed.Version != "0.2.0" {
		t.Fatalf("Version = %q", parsed.Version)
	}
}

func TestVerifyRejectsTamperedArtifact(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := filepath.Join(dir, "update_public_key.pem")
	der, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(publicKeyPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}

	manifestBytes, err := json.Marshal(Manifest{
		Version:   "0.2.0",
		OS:        runtimeOS(),
		Arch:      runtimeArch(),
		Channel:   "stable",
		SHA256:    sha256Hex([]byte("expected")),
		SizeBytes: 8,
	})
	if err != nil {
		t.Fatal(err)
	}
	u := Updater{config: Config{Version: "0.1.0", Channel: "stable", PublicKeyPath: publicKeyPath}}
	_, err = u.verify(CheckResponse{
		Release:   Release{Version: "0.2.0", OS: runtimeOS(), Arch: runtimeArch(), Channel: "stable"},
		Manifest:  string(manifestBytes),
		Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, manifestBytes)),
	}, []byte("tampered"))
	if err == nil {
		t.Fatal("verify returned nil error for tampered artifact")
	}
}

func TestDownloadAndVerifyStreamsLegitimateArtifactToStage(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := writePublicKey(t, dir, publicKey)
	artifact := bytes.Repeat([]byte("castrelyx-agent"), 8192)
	response, expectedManifest := signedCheckResponse(t, privateKey, artifact, int64(len(artifact)))

	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/artifact" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write(artifact)
	}))
	defer server.Close()
	response.ArtifactURL = server.URL + "/artifact"

	tlsConfig := server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	u, err := New(Config{
		BaseURL:          server.URL,
		Version:          "0.1.0",
		Channel:          "stable",
		UpdateDir:        filepath.Join(dir, "updates"),
		PublicKeyPath:    publicKeyPath,
		MaxArtifactBytes: int64(len(artifact)) + 1,
		TLSConfig:        tlsConfig,
	})
	if err != nil {
		t.Fatal(err)
	}
	manifest, err := u.verifyManifest(response)
	if err != nil {
		t.Fatal(err)
	}
	if manifest != expectedManifest {
		t.Fatalf("manifest = %#v, want %#v", manifest, expectedManifest)
	}
	stageDir := filepath.Join(dir, "updates", "staged", response.DeploymentID)
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stageDir, executableName()), []byte("old-stage"), 0o755); err != nil {
		t.Fatal(err)
	}
	artifactPath, err := u.downloadAndVerify(context.Background(), response, manifest)
	if err != nil {
		t.Fatal(err)
	}
	staged, err := os.ReadFile(artifactPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(staged, artifact) {
		t.Fatalf("staged artifact length = %d, want %d", len(staged), len(artifact))
	}
}

func TestDownloadAndVerifyRejectsOversizeChunkedArtifactAndRemovesTempFile(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := writePublicKey(t, dir, publicKey)
	artifact := []byte("123456789ABCDEFGHIJK")
	response, _ := signedCheckResponse(t, privateKey, artifact, 0)

	var mu sync.Mutex
	remoteAddresses := map[string]struct{}{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		remoteAddresses[r.RemoteAddr] = struct{}{}
		mu.Unlock()
		if r.URL.Path == "/small" {
			_, _ = io.WriteString(w, "ok")
			return
		}
		w.WriteHeader(http.StatusOK)
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		_, _ = w.Write(artifact)
	}))
	defer server.Close()
	response.ArtifactURL = server.URL + "/artifact"

	tlsConfig := server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	u, err := New(Config{
		BaseURL:          server.URL,
		Version:          "0.1.0",
		Channel:          "stable",
		UpdateDir:        filepath.Join(dir, "updates"),
		PublicKeyPath:    publicKeyPath,
		MaxArtifactBytes: 8,
		TLSConfig:        tlsConfig,
	})
	if err != nil {
		t.Fatal(err)
	}
	manifest, err := u.verifyManifest(response)
	if err != nil {
		t.Fatal(err)
	}
	artifactPath, err := u.downloadAndVerify(context.Background(), response, manifest)
	if err == nil || !strings.Contains(err.Error(), "exceeds maximum size") {
		t.Fatalf("downloadAndVerify error = %v", err)
	}
	if artifactPath != "" {
		t.Fatalf("artifactPath = %q, want empty", artifactPath)
	}
	stageDir := filepath.Join(dir, "updates", "staged", response.DeploymentID)
	if _, err := os.Stat(filepath.Join(stageDir, executableName())); !os.IsNotExist(err) {
		t.Fatalf("unexpected staged artifact, err=%v", err)
	}
	if matches, err := filepath.Glob(filepath.Join(stageDir, ".artifact-*.tmp")); err != nil || len(matches) != 0 {
		t.Fatalf("temporary files left behind: %v, err=%v", matches, err)
	}
	if _, _, err := u.streamArtifact(context.Background(), server.URL+"/small", io.Discard, 8); err != nil {
		t.Fatalf("follow-up download failed: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(remoteAddresses) != 1 {
		t.Fatalf("HTTP connection was not reused after draining oversize response: %v", remoteAddresses)
	}
}

func TestVerifyManifestRejectsDeclaredArtifactAboveConfiguredMaximum(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := writePublicKey(t, dir, publicKey)
	artifact := []byte("123456789")
	response, _ := signedCheckResponse(t, privateKey, artifact, int64(len(artifact)))
	u := Updater{config: Config{PublicKeyPath: publicKeyPath, MaxArtifactBytes: 8}}

	if _, err := u.verifyManifest(response); err == nil || !strings.Contains(err.Error(), "exceeds maximum size") {
		t.Fatalf("verifyManifest error = %v", err)
	}
}

func TestCheckAndApplySerializesRollbackStateTransition(t *testing.T) {
	dir := t.TempDir()
	checkStarted := make(chan struct{})
	releaseCheck := make(chan struct{})
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(releaseCheck) }) }
	defer release()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/agent/updates/check":
			close(checkStarted)
			<-releaseCheck
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"update_available":false}`)
		case "/api/agent/updates/status":
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	u, err := New(Config{
		BaseURL:       server.URL,
		Version:       "0.1.0",
		Channel:       "stable",
		UpdateDir:     filepath.Join(dir, "updates"),
		PublicKeyPath: filepath.Join(dir, "unused-public-key.pem"),
		TLSConfig:     server.Client().Transport.(*http.Transport).TLSClientConfig.Clone(),
	})
	if err != nil {
		t.Fatal(err)
	}
	targetPath := filepath.Join(dir, "castrelyx-agent")
	previousPath := filepath.Join(dir, "castrelyx-agent.prev")
	if err := os.WriteFile(targetPath, []byte("current"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		Status:       "APPLYING",
	}); err != nil {
		t.Fatal(err)
	}
	u.rollbackArtifact = func(state State) error {
		return replaceFile(state.PreviousPath, state.TargetPath, 0o755)
	}

	checkDone := make(chan error, 1)
	go func() { checkDone <- u.CheckAndApply(context.Background()) }()
	select {
	case <-checkStarted:
	case <-time.After(time.Second):
		t.Fatal("update check did not start")
	}
	rollbackDone := make(chan error, 1)
	go func() { rollbackDone <- u.Rollback(context.Background(), "post-update collection failed") }()
	select {
	case err := <-rollbackDone:
		t.Fatalf("rollback raced with active update check: %v", err)
	case <-time.After(150 * time.Millisecond):
	}
	if data, err := os.ReadFile(targetPath); err != nil || string(data) != "current" {
		t.Fatalf("target changed while update operation was active: data=%q err=%v", data, err)
	}

	release()
	if err := <-checkDone; err != nil {
		t.Fatalf("CheckAndApply returned error: %v", err)
	}
	if err := <-rollbackDone; !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("Rollback error = %v, want ErrRestartRequired", err)
	}
	if data, err := os.ReadFile(targetPath); err != nil || string(data) != "previous" {
		t.Fatalf("serialized rollback did not restore target: data=%q err=%v", data, err)
	}
}

func TestCheckAndApplyDownloadStatusFailureDoesNotLeavePendingApply(t *testing.T) {
	u, serverState := newCheckAndApplyTestUpdater(t, "DOWNLOADED")
	applyCalled := false
	u.applyArtifact = func(State) error {
		applyCalled = true
		return nil
	}

	err := u.CheckAndApply(context.Background())
	if err == nil || !strings.Contains(err.Error(), "status 503") {
		t.Fatalf("CheckAndApply error = %v, want status report failure", err)
	}
	if applyCalled {
		t.Fatal("binary apply was called after DOWNLOADED status report failed")
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "FAILED" || u.HasPendingApply() {
		t.Fatalf("state status = %q, pending = %v; want FAILED and not pending", state.Status, u.HasPendingApply())
	}
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "DOWNLOADED,FAILED" {
		t.Fatalf("reported statuses = %q, want DOWNLOADED,FAILED", got)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
}

func TestCheckAndApplyFailurePersistsAndReportsFailedState(t *testing.T) {
	u, serverState := newCheckAndApplyTestUpdater(t, "")
	u.applyArtifact = func(state State) error {
		persisted, err := u.loadState()
		if err != nil {
			t.Fatal(err)
		}
		if state.Status != "APPLY_INTENT" || persisted.Status != "APPLY_INTENT" {
			t.Fatalf("apply observed state argument=%q persisted=%q, want APPLY_INTENT", state.Status, persisted.Status)
		}
		return errors.New("injected replacement failure")
	}

	err := u.CheckAndApply(context.Background())
	if err == nil || !strings.Contains(err.Error(), "injected replacement failure") {
		t.Fatalf("CheckAndApply error = %v, want replacement failure", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "FAILED" || !strings.Contains(state.Message, "binary replacement failed") {
		t.Fatalf("state = %#v, want accurate FAILED replacement state", state)
	}
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "DOWNLOADED,APPLYING,FAILED" {
		t.Fatalf("reported statuses = %q, want DOWNLOADED,APPLYING,FAILED", got)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
}

func TestCheckAndApplyPersistsApplyingOnlyAfterApplyStartsAndCleansAfterApplied(t *testing.T) {
	u, serverState := newCheckAndApplyTestUpdater(t, "APPLYING")
	applyCalled := false
	u.applyArtifact = func(state State) error {
		applyCalled = true
		persisted, err := u.loadState()
		if err != nil {
			t.Fatal(err)
		}
		if state.Status != "APPLY_INTENT" || persisted.Status != "APPLY_INTENT" {
			t.Fatalf("apply observed state argument=%q persisted=%q, want APPLY_INTENT", state.Status, persisted.Status)
		}
		return nil
	}

	err := u.CheckAndApply(context.Background())
	if !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("CheckAndApply error = %v, want ErrRestartRequired", err)
	}
	if !applyCalled {
		t.Fatal("binary apply was not called")
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if err := copyFile(state.TargetPath, state.ArtifactPath, 0o755); err != nil {
		t.Fatalf("simulate completed replacement: %v", err)
	}
	if state.Status != "APPLYING" || !u.HasPendingApply() {
		t.Fatalf("state status = %q, pending = %v; want an accurate pending apply", state.Status, u.HasPendingApply())
	}
	if _, err := os.Stat(filepath.Join(u.config.UpdateDir, "staged")); err != nil {
		t.Fatalf("pending apply staging was removed: %v", err)
	}

	serverState.setFailedStatus("")
	if err := u.MarkApplied(context.Background()); err != nil {
		t.Fatalf("MarkApplied returned error: %v", err)
	}
	state, err = u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "APPLIED" {
		t.Fatalf("state status = %q, want APPLIED", state.Status)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "DOWNLOADED,APPLYING,APPLIED" {
		t.Fatalf("reported statuses = %q, want DOWNLOADED,APPLYING,APPLIED", got)
	}
}

func TestRollbackCleansTerminalDeploymentStaging(t *testing.T) {
	u, _ := newCheckAndApplyTestUpdater(t, "")
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	if err := os.WriteFile(targetPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	if err := os.WriteFile(artifactPath, []byte("artifact"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		ArtifactPath: artifactPath,
		Status:       "APPLYING",
	}); err != nil {
		t.Fatal(err)
	}
	u.rollbackArtifact = func(state State) error {
		return replaceFile(state.PreviousPath, state.TargetPath, 0o755)
	}

	if err := u.Rollback(context.Background(), "health check failed"); !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("Rollback error = %v, want ErrRestartRequired", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACKING" {
		t.Fatalf("state status before reconciliation = %q, want ROLLBACKING", state.Status)
	}
	if _, err := os.Stat(filepath.Join(u.config.UpdateDir, "staged")); err != nil {
		t.Fatalf("rollback staging was removed before helper completion: %v", err)
	}
	if err := u.ResumePendingReplacement(context.Background()); err != nil {
		t.Fatalf("ResumePendingReplacement returned error: %v", err)
	}
	if pending := u.HasPendingApply(); pending {
		t.Fatal("completed rollback remained pending after target reconciliation")
	}
	state, err = u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACK" {
		t.Fatalf("state status = %q, want ROLLBACK", state.Status)
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "previous" {
		t.Fatalf("rollback target = %q, err=%v; want previous", payload, err)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
}

func TestResumePendingRollbackIntentCompletesSynchronousReplacementAndRequiresRestart(t *testing.T) {
	u, _ := newCheckAndApplyTestUpdater(t, "")
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	artifactPath := filepath.Join(stageDir, executableName())
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(artifactPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		ArtifactPath: artifactPath,
		Status:       "ROLLBACK_INTENT",
	}); err != nil {
		t.Fatal(err)
	}
	u.rollbackArtifact = func(state State) error {
		return replaceFile(state.PreviousPath, state.TargetPath, 0o755)
	}

	if err := u.ResumePendingReplacement(context.Background()); !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("ResumePendingReplacement error = %v, want ErrRestartRequired", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACK" {
		t.Fatalf("state status = %q, want ROLLBACK", state.Status)
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "previous" {
		t.Fatalf("resumed rollback target = %q, err=%v; want previous", payload, err)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
}

func TestResumePendingRollbackIntentStartsAsyncHelperAndPreservesSources(t *testing.T) {
	u, _ := newCheckAndApplyTestUpdater(t, "")
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	artifactPath := filepath.Join(stageDir, executableName())
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(artifactPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		ArtifactPath: artifactPath,
		Status:       "ROLLBACK_INTENT",
	}); err != nil {
		t.Fatal(err)
	}
	helperStarted := false
	u.rollbackRunsAsync = true
	u.rollbackArtifact = func(State) error {
		helperStarted = true
		return nil
	}

	if err := u.ResumePendingReplacement(context.Background()); !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("ResumePendingReplacement error = %v, want ErrRestartRequired", err)
	}
	if !helperStarted {
		t.Fatal("rollback helper was not restarted")
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACKING" {
		t.Fatalf("state status = %q, want ROLLBACKING", state.Status)
	}
	if _, err := os.Stat(previousPath); err != nil {
		t.Fatalf("previous binary was removed while helper is pending: %v", err)
	}
	if _, err := os.Stat(artifactPath); err != nil {
		t.Fatalf("rollback staging was removed while helper is pending: %v", err)
	}
}

func TestResumePendingRollbackFailureRetainsRetryableIntentAndSources(t *testing.T) {
	u, _ := newCheckAndApplyTestUpdater(t, "")
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	artifactPath := filepath.Join(stageDir, executableName())
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(artifactPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		ArtifactPath: artifactPath,
		Status:       "ROLLBACK_INTENT",
	}); err != nil {
		t.Fatal(err)
	}
	u.rollbackArtifact = func(State) error { return errors.New("injected helper start failure") }

	if err := u.ResumePendingReplacement(context.Background()); err == nil || !strings.Contains(err.Error(), "injected helper start failure") {
		t.Fatalf("ResumePendingReplacement error = %v, want helper start failure", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACK_INTENT" {
		t.Fatalf("state status = %q, want retryable ROLLBACK_INTENT", state.Status)
	}
	if _, err := os.Stat(previousPath); err != nil {
		t.Fatalf("previous binary was removed after retryable failure: %v", err)
	}
	if _, err := os.Stat(artifactPath); err != nil {
		t.Fatalf("staging was removed after retryable failure: %v", err)
	}
}

func TestHasPendingApplyRecoversInterruptedApplyIntent(t *testing.T) {
	for _, test := range []struct {
		name          string
		targetPayload string
		wantPending   bool
		wantStatus    string
		wantStaging   bool
	}{
		{name: "replacement completed", targetPayload: "artifact", wantPending: true, wantStatus: "APPLYING", wantStaging: true},
		{name: "replacement not started", targetPayload: "current", wantPending: false, wantStatus: "FAILED", wantStaging: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			u, _ := newCheckAndApplyTestUpdater(t, "")
			stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
			if err := os.MkdirAll(stageDir, 0o700); err != nil {
				t.Fatal(err)
			}
			artifactPath := filepath.Join(stageDir, executableName())
			targetPath := filepath.Join(t.TempDir(), executableName())
			if err := os.WriteFile(artifactPath, []byte("artifact"), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(targetPath, []byte(test.targetPayload), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := u.writeState(State{
				DeploymentID: "deployment-1",
				ArtifactPath: artifactPath,
				TargetPath:   targetPath,
				Status:       "APPLY_INTENT",
			}); err != nil {
				t.Fatal(err)
			}

			resumeErr := u.ResumePendingReplacement(context.Background())
			if test.wantStatus == "FAILED" && resumeErr == nil {
				t.Fatal("ResumePendingReplacement returned nil for an unstarted replacement")
			}
			if test.wantStatus != "FAILED" && resumeErr != nil {
				t.Fatalf("ResumePendingReplacement returned error: %v", resumeErr)
			}
			if pending := u.HasPendingApply(); pending != test.wantPending {
				t.Fatalf("HasPendingApply = %v, want %v", pending, test.wantPending)
			}
			state, err := u.loadState()
			if err != nil {
				t.Fatal(err)
			}
			if state.Status != test.wantStatus {
				t.Fatalf("state status = %q, want %q", state.Status, test.wantStatus)
			}
			_, statErr := os.Stat(filepath.Join(u.config.UpdateDir, "staged"))
			if test.wantStaging && statErr != nil {
				t.Fatalf("pending staging was removed: %v", statErr)
			}
			if !test.wantStaging && !os.IsNotExist(statErr) {
				t.Fatalf("terminal staging still exists, err=%v", statErr)
			}
		})
	}
}

func TestMarkAppliedDoesNotAcceptHelperStartBeforeTargetReplacement(t *testing.T) {
	u, serverState := newCheckAndApplyTestUpdater(t, "")
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	targetPath := filepath.Join(t.TempDir(), executableName())
	if err := os.WriteFile(artifactPath, []byte("replacement"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("current"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		ArtifactPath: artifactPath,
		TargetPath:   targetPath,
		Status:       "APPLYING",
	}); err != nil {
		t.Fatal(err)
	}
	helperStatePath := filepath.Join(u.config.UpdateDir, "update_helper_state.json")
	if err := os.WriteFile(helperStatePath, []byte("helper owns this snapshot"), 0o600); err != nil {
		t.Fatal(err)
	}

	if pending := u.HasPendingApply(); !pending {
		t.Fatal("helper-started replacement was not retained as pending")
	}
	if err := u.ResumePendingReplacement(context.Background()); !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("ResumePendingReplacement error = %v, want ErrRestartRequired while helper is active", err)
	}
	if err := u.MarkApplied(context.Background()); err == nil || !strings.Contains(err.Error(), "has not completed") {
		t.Fatalf("MarkApplied error = %v, want incomplete helper replacement", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "APPLYING" {
		t.Fatalf("state status = %q, want APPLYING while helper is active", state.Status)
	}
	if _, err := os.Stat(stageDir); err != nil {
		t.Fatalf("pending helper staging was removed: %v", err)
	}
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "" {
		t.Fatalf("reported statuses = %q, must not report APPLIED before replacement", got)
	}

	staleHelperTime := time.Now().Add(-windowsHelperActivityWindow - time.Minute)
	if err := os.Chtimes(helperStatePath, staleHelperTime, staleHelperTime); err != nil {
		t.Fatal(err)
	}
	if err := u.MarkApplied(context.Background()); err == nil || !strings.Contains(err.Error(), "expected target") {
		t.Fatalf("MarkApplied after helper failure error = %v, want target reconciliation failure", err)
	}
	state, err = u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "FAILED" {
		t.Fatalf("state status = %q, want FAILED after helper exited without replacement", state.Status)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "FAILED" {
		t.Fatalf("reported statuses = %q, want FAILED", got)
	}
}

func TestRollbackHelperKeepsStagingUntilPreviousBinaryIsTarget(t *testing.T) {
	u, serverState := newCheckAndApplyTestUpdater(t, "")
	stageDir := filepath.Join(u.config.UpdateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	if err := os.WriteFile(artifactPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("updated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("previous"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ReleaseID:    1,
		ArtifactPath: artifactPath,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		Status:       "ROLLBACKING",
	}); err != nil {
		t.Fatal(err)
	}
	helperStatePath := filepath.Join(u.config.UpdateDir, "update_helper_state.json")
	if err := os.WriteFile(helperStatePath, []byte("helper owns this snapshot"), 0o600); err != nil {
		t.Fatal(err)
	}

	if pending := u.HasPendingApply(); !pending {
		t.Fatal("helper-started rollback was not retained as pending")
	}
	if err := u.MarkApplied(context.Background()); err == nil || !strings.Contains(err.Error(), "has not completed") {
		t.Fatalf("MarkApplied error = %v, want incomplete rollback helper", err)
	}
	if _, err := os.Stat(stageDir); err != nil {
		t.Fatalf("rollback staging was removed while helper was active: %v", err)
	}
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "" {
		t.Fatalf("reported statuses = %q, must not report APPLIED during rollback", got)
	}

	if err := replaceFile(previousPath, targetPath, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := u.MarkApplied(context.Background()); err != nil {
		t.Fatalf("MarkApplied reconciliation returned error: %v", err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACK" {
		t.Fatalf("state status = %q, want ROLLBACK", state.Status)
	}
	assertStagingRemoved(t, u.config.UpdateDir)
	if got := strings.Join(serverState.reportedStatuses(), ","); got != "" {
		t.Fatalf("reported statuses = %q, must not report APPLIED after rollback", got)
	}
}

func TestRunWindowsHelperReplacesFromImmutableSnapshotAndRemovesIt(t *testing.T) {
	dir := t.TempDir()
	sourcePath := filepath.Join(dir, "replacement.bin")
	targetPath := filepath.Join(dir, "target.bin")
	helperStatePath := filepath.Join(dir, "update_helper_state.json")
	if err := os.WriteFile(sourcePath, []byte("replacement"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("current"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := writeStateFile(helperStatePath, State{
		ArtifactPath: sourcePath,
		TargetPath:   targetPath,
		Status:       "APPLY_INTENT",
	}); err != nil {
		t.Fatal(err)
	}

	if err := RunWindowsHelper(helperStatePath, ""); err != nil {
		t.Fatalf("RunWindowsHelper returned error: %v", err)
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "replacement" {
		t.Fatalf("helper target = %q, err=%v; want replacement", payload, err)
	}
	if _, err := os.Stat(helperStatePath); !os.IsNotExist(err) {
		t.Fatalf("helper snapshot was not removed, err=%v", err)
	}
}

func TestRunWindowsHelperPersistsFailureAndRetriesOldServiceRecovery(t *testing.T) {
	dir := t.TempDir()
	mainStatePath := filepath.Join(dir, "update_state.json")
	helperStatePath := filepath.Join(dir, "update_helper_state.json")
	sourcePath := filepath.Join(dir, "replacement.bin")
	targetPath := filepath.Join(dir, "target.bin")
	if err := os.WriteFile(sourcePath, []byte("replacement"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("recoverable-old-target"), 0o755); err != nil {
		t.Fatal(err)
	}
	state := State{
		DeploymentID:          "deployment-1",
		ReleaseID:             1,
		ArtifactPath:          sourcePath,
		ReplacementSourcePath: sourcePath,
		TargetPath:            targetPath,
		HelperMainStatePath:   mainStatePath,
		Status:                "APPLYING",
	}
	if err := writeStateFile(mainStatePath, state); err != nil {
		t.Fatal(err)
	}
	if err := writeStateFile(helperStatePath, state); err != nil {
		t.Fatal(err)
	}
	serviceStarts := 0
	replacementFailure := errors.New("injected replacement failure")
	err := runWindowsHelper(
		helperStatePath,
		"CastrelyxAgent",
		1,
		3,
		0,
		func(string, string, os.FileMode) error { return replacementFailure },
		func(string) error {
			serviceStarts++
			if serviceStarts == 1 {
				return errors.New("service still stopping")
			}
			return nil
		},
	)
	if !errors.Is(err, replacementFailure) {
		t.Fatalf("runWindowsHelper error = %v, want replacement failure", err)
	}
	if serviceStarts != 2 {
		t.Fatalf("service start attempts = %d, want 2", serviceStarts)
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "recoverable-old-target" {
		t.Fatalf("old target was not preserved: payload=%q err=%v", payload, err)
	}
	mainState, err := readState(mainStatePath)
	if err != nil {
		t.Fatal(err)
	}
	if mainState.Status != "FAILED" || !strings.Contains(mainState.Message, "injected replacement failure") {
		t.Fatalf("main state = %#v, want durable helper FAILED state", mainState)
	}
	if _, err := os.Stat(helperStatePath); !os.IsNotExist(err) {
		t.Fatalf("helper snapshot was not removed, err=%v", err)
	}
}

func TestRunWindowsHelperSurfacesServiceRestartFailure(t *testing.T) {
	dir := t.TempDir()
	mainStatePath := filepath.Join(dir, "update_state.json")
	helperStatePath := filepath.Join(dir, "update_helper_state.json")
	state := State{
		DeploymentID:        "deployment-1",
		ReleaseID:           1,
		ArtifactPath:        filepath.Join(dir, "replacement.bin"),
		TargetPath:          filepath.Join(dir, "target.bin"),
		HelperMainStatePath: mainStatePath,
		Status:              "APPLYING",
	}
	if err := writeStateFile(mainStatePath, state); err != nil {
		t.Fatal(err)
	}
	if err := writeStateFile(helperStatePath, state); err != nil {
		t.Fatal(err)
	}
	serviceStarts := 0
	serviceFailure := errors.New("injected service start failure")
	err := runWindowsHelper(
		helperStatePath,
		"CastrelyxAgent",
		1,
		3,
		0,
		func(string, string, os.FileMode) error { return nil },
		func(string) error {
			serviceStarts++
			return serviceFailure
		},
	)
	if !errors.Is(err, serviceFailure) {
		t.Fatalf("runWindowsHelper error = %v, want service start failure", err)
	}
	if serviceStarts != 3 {
		t.Fatalf("service start attempts = %d, want 3", serviceStarts)
	}
	mainState, err := readState(mainStatePath)
	if err != nil {
		t.Fatal(err)
	}
	if mainState.Status != "FAILED" || !strings.Contains(mainState.Message, "injected service start failure") {
		t.Fatalf("main state = %#v, want durable service FAILED state", mainState)
	}
}

func TestPrepareUpdateStartupRollsBackSecondUnhealthyBoot(t *testing.T) {
	updateDir := t.TempDir()
	stageDir := filepath.Join(updateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	for path, payload := range map[string]string{
		artifactPath: "new-binary",
		targetPath:   "new-binary",
		previousPath: "previous-binary",
	} {
		if err := os.WriteFile(path, []byte(payload), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	u := &Updater{config: Config{UpdateDir: updateDir}}
	u.rollbackArtifact = func(state State) error {
		return replaceFile(state.PreviousPath, state.TargetPath, 0o755)
	}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ArtifactPath: artifactPath,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		Status:       "APPLYING",
	}); err != nil {
		t.Fatal(err)
	}

	pending, err := prepareUpdateStartupWithUpdater(context.Background(), u)
	if err != nil || !pending {
		t.Fatalf("first startup pending=%v err=%v, want probation", pending, err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.ProbationAttempts != 1 || state.ProbationPassed {
		t.Fatalf("first startup state = %#v", state)
	}

	pending, err = prepareUpdateStartupWithUpdater(context.Background(), u)
	if pending || !errors.Is(err, ErrRestartRequired) {
		t.Fatalf("second startup pending=%v err=%v, want rollback restart", pending, err)
	}
	state, err = u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.Status != "ROLLBACK" {
		t.Fatalf("second startup state = %#v, want ROLLBACK", state)
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "previous-binary" {
		t.Fatalf("rollback target = %q err=%v", payload, err)
	}
}

func TestMarkUpdateStartupHealthyPreventsProbationRollback(t *testing.T) {
	updateDir := t.TempDir()
	stageDir := filepath.Join(updateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	for path, payload := range map[string]string{
		artifactPath: "healthy-new-binary",
		targetPath:   "healthy-new-binary",
		previousPath: "previous-binary",
	} {
		if err := os.WriteFile(path, []byte(payload), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	u := &Updater{config: Config{UpdateDir: updateDir}}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ArtifactPath: artifactPath,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		Status:       "APPLYING",
	}); err != nil {
		t.Fatal(err)
	}
	if pending, err := prepareUpdateStartupWithUpdater(context.Background(), u); err != nil || !pending {
		t.Fatalf("first startup pending=%v err=%v", pending, err)
	}
	if err := MarkUpdateStartupHealthy(updateDir); err != nil {
		t.Fatal(err)
	}
	if pending, err := prepareUpdateStartupWithUpdater(context.Background(), u); err != nil || pending {
		t.Fatalf("healthy restart pending=%v err=%v", pending, err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if !state.ProbationPassed || u.HasPendingApply() {
		t.Fatalf("healthy probation state=%#v pending=%v", state, u.HasPendingApply())
	}
	if payload, err := os.ReadFile(targetPath); err != nil || string(payload) != "healthy-new-binary" {
		t.Fatalf("healthy target changed: %q err=%v", payload, err)
	}
}

func TestPrepareUpdateStartupDoesNotConsumeProbationBeforeReplacement(t *testing.T) {
	updateDir := t.TempDir()
	stageDir := filepath.Join(updateDir, "staged", "deployment-1")
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		t.Fatal(err)
	}
	artifactPath := filepath.Join(stageDir, executableName())
	targetPath := filepath.Join(t.TempDir(), executableName())
	previousPath := filepath.Join(t.TempDir(), executableName()+".prev")
	if err := os.WriteFile(artifactPath, []byte("new"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetPath, []byte("old"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(previousPath, []byte("old"), 0o755); err != nil {
		t.Fatal(err)
	}
	u := &Updater{config: Config{UpdateDir: updateDir}}
	if err := u.writeState(State{
		DeploymentID: "deployment-1",
		ArtifactPath: artifactPath,
		TargetPath:   targetPath,
		PreviousPath: previousPath,
		Status:       "APPLY_INTENT",
	}); err != nil {
		t.Fatal(err)
	}

	pending, err := prepareUpdateStartupWithUpdater(context.Background(), u)
	if err != nil || pending {
		t.Fatalf("pre-replacement startup pending=%v err=%v", pending, err)
	}
	state, err := u.loadState()
	if err != nil {
		t.Fatal(err)
	}
	if state.ProbationAttempts != 0 || state.Status != "FAILED" {
		t.Fatalf("pre-replacement state = %#v", state)
	}
}

type checkAndApplyServerState struct {
	mu           sync.Mutex
	failedStatus string
	statuses     []string
}

func (s *checkAndApplyServerState) setFailedStatus(status string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.failedStatus = status
}

func (s *checkAndApplyServerState) recordStatus(status string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.statuses = append(s.statuses, status)
	return status == s.failedStatus
}

func (s *checkAndApplyServerState) reportedStatuses() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.statuses...)
}

func newCheckAndApplyTestUpdater(t *testing.T, failedStatus string) (*Updater, *checkAndApplyServerState) {
	t.Helper()
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := writePublicKey(t, dir, publicKey)
	artifact := []byte("signed-agent-update")
	response, _ := signedCheckResponse(t, privateKey, artifact, int64(len(artifact)))
	serverState := &checkAndApplyServerState{failedStatus: failedStatus}
	var server *httptest.Server
	server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/agent/updates/check":
			response.ArtifactURL = server.URL + "/artifact"
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(response)
		case "/artifact":
			_, _ = w.Write(artifact)
		case "/api/agent/updates/status":
			var request statusRequest
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			if serverState.recordStatus(request.Status) {
				http.Error(w, "injected status failure", http.StatusServiceUnavailable)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)

	u, err := New(Config{
		BaseURL:       server.URL,
		Version:       "0.1.0",
		Channel:       "stable",
		UpdateDir:     filepath.Join(dir, "updates"),
		PublicKeyPath: publicKeyPath,
		TLSConfig:     server.Client().Transport.(*http.Transport).TLSClientConfig.Clone(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return u, serverState
}

func assertStagingRemoved(t *testing.T, updateDir string) {
	t.Helper()
	if _, err := os.Stat(filepath.Join(updateDir, "staged")); !os.IsNotExist(err) {
		t.Fatalf("terminal staging still exists, err=%v", err)
	}
}

func writePublicKey(t *testing.T, dir string, publicKey ed25519.PublicKey) string {
	t.Helper()
	der, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "update_public_key.pem")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func signedCheckResponse(t *testing.T, privateKey ed25519.PrivateKey, artifact []byte, manifestSize int64) (CheckResponse, Manifest) {
	t.Helper()
	manifest := Manifest{
		Version:   "0.2.0",
		OS:        runtimeOS(),
		Arch:      runtimeArch(),
		Channel:   "stable",
		SHA256:    sha256Hex(artifact),
		SizeBytes: manifestSize,
	}
	manifestBytes, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	response := CheckResponse{
		UpdateAvailable: true,
		DeploymentID:    "deployment-1",
		Release: Release{
			ID:        1,
			Version:   manifest.Version,
			OS:        manifest.OS,
			Arch:      manifest.Arch,
			Channel:   manifest.Channel,
			SHA256:    manifest.SHA256,
			SizeBytes: manifest.SizeBytes,
		},
		Manifest:  string(manifestBytes),
		Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, manifestBytes)),
	}
	return response, manifest
}

func runtimeOS() string {
	return runtime.GOOS
}

func runtimeArch() string {
	return runtime.GOARCH
}

func sha256Hex(payload []byte) string {
	sum := sha256.Sum256(payload)
	return hex.EncodeToString(sum[:])
}
