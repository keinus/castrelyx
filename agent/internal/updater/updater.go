package updater

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

var ErrRestartRequired = errors.New("restart required to apply agent update")

var errArtifactVerification = errors.New("artifact verification failed")

var errReplacementHelperIncomplete = errors.New("Windows replacement helper has not completed")

const defaultMaxArtifactBytes int64 = 128 * 1024 * 1024

const windowsHelperActivityWindow = 5 * time.Minute

type Config struct {
	BaseURL          string
	AgentID          string
	Version          string
	Channel          string
	InstallMode      string
	UpdateDir        string
	PublicKeyPath    string
	MaxArtifactBytes int64
	TLSConfig        *tls.Config
}

type Updater struct {
	config            Config
	httpClient        *http.Client
	operationMu       sync.Mutex
	stateMu           sync.Mutex
	applyArtifact     func(State) error
	rollbackArtifact  func(State) error
	rollbackRunsAsync bool
}

type CheckRequest struct {
	Version     string `json:"version"`
	OS          string `json:"os"`
	Arch        string `json:"arch"`
	Channel     string `json:"channel"`
	InstallMode string `json:"install_mode"`
}

type CheckResponse struct {
	UpdateAvailable bool    `json:"update_available"`
	DeploymentID    string  `json:"deployment_id"`
	Release         Release `json:"release"`
	ArtifactURL     string  `json:"artifact_url"`
	Manifest        string  `json:"manifest"`
	Signature       string  `json:"signature"`
}

type Release struct {
	ID        int64  `json:"id"`
	Version   string `json:"version"`
	OS        string `json:"os"`
	Arch      string `json:"arch"`
	Channel   string `json:"channel"`
	SHA256    string `json:"sha256"`
	SizeBytes int64  `json:"sizeBytes"`
}

type Manifest struct {
	Version   string `json:"version"`
	OS        string `json:"os"`
	Arch      string `json:"arch"`
	Channel   string `json:"channel"`
	SHA256    string `json:"sha256"`
	SizeBytes int64  `json:"size_bytes"`
}

type State struct {
	DeploymentID          string `json:"deployment_id"`
	ReleaseID             int64  `json:"release_id"`
	FromVersion           string `json:"from_version"`
	ToVersion             string `json:"to_version"`
	TargetPath            string `json:"target_path"`
	PreviousPath          string `json:"previous_path"`
	ArtifactPath          string `json:"artifact_path"`
	ReplacementSourcePath string `json:"replacement_source_path,omitempty"`
	HelperMainStatePath   string `json:"helper_main_state_path,omitempty"`
	ProbationAttempts     int    `json:"probation_attempts,omitempty"`
	ProbationPassed       bool   `json:"probation_passed,omitempty"`
	Status                string `json:"status"`
	Message               string `json:"message,omitempty"`
	UpdatedAt             string `json:"updated_at"`
}

type statusRequest struct {
	DeploymentID string `json:"deployment_id"`
	ReleaseID    int64  `json:"release_id"`
	FromVersion  string `json:"from_version"`
	Status       string `json:"status"`
	Message      string `json:"message,omitempty"`
}

func New(config Config) (*Updater, error) {
	if config.BaseURL == "" {
		return nil, errors.New("base url is required")
	}
	if config.Version == "" {
		return nil, errors.New("version is required")
	}
	if config.Channel == "" {
		config.Channel = "stable"
	}
	if config.InstallMode == "" {
		config.InstallMode = defaultInstallMode()
	}
	if config.UpdateDir == "" {
		return nil, errors.New("update dir is required")
	}
	if config.PublicKeyPath == "" {
		return nil, errors.New("update public key path is required")
	}
	if config.TLSConfig == nil {
		return nil, errors.New("tls config is required")
	}
	if config.MaxArtifactBytes <= 0 {
		config.MaxArtifactBytes = defaultMaxArtifactBytes
	}
	return &Updater{
		config: config,
		httpClient: &http.Client{
			Timeout: 60 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: config.TLSConfig,
			},
		},
	}, nil
}

func (u *Updater) CheckAndApply(ctx context.Context) error {
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	response, err := u.check(ctx)
	if err != nil || !response.UpdateAvailable {
		return err
	}
	manifest, err := u.verifyManifest(response)
	if err != nil {
		_ = u.report(ctx, response, "VERIFY_FAILED", err.Error())
		return err
	}
	artifactPath, err := u.downloadAndVerify(ctx, response, manifest)
	if err != nil {
		if errors.Is(err, errArtifactVerification) {
			_ = u.report(ctx, response, "VERIFY_FAILED", err.Error())
		} else {
			_ = u.report(ctx, response, "FAILED", "download failed: "+err.Error())
		}
		return err
	}
	state, err := u.stageVerifiedArtifact(response, manifest, artifactPath)
	if err != nil {
		_ = u.report(ctx, response, "FAILED", "stage failed: "+err.Error())
		return err
	}
	if err := u.report(ctx, response, "DOWNLOADED", "artifact verified and staged"); err != nil {
		return u.failStagedUpdate(ctx, response, state, "download status report failed: "+err.Error(), err)
	}
	state.Status = "APPLY_INTENT"
	state.Message = "binary replacement is ready to start"
	if err := u.writeState(state); err != nil {
		return err
	}
	reportErr := u.report(ctx, response, "APPLYING", "replacing agent binary")
	if err := u.applyUpdate(state); err != nil {
		return u.failStagedUpdate(ctx, response, state, "binary replacement failed: "+err.Error(), err)
	}
	state.Status = "APPLYING"
	state.Message = "binary replacement initiated"
	if reportErr != nil {
		state.Message += "; APPLYING status report failed: " + reportErr.Error()
	}
	if err := u.writeState(state); err != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("persist applying state: %w", err))
	}
	if reportErr != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("report applying status: %w", reportErr))
	}
	return ErrRestartRequired
}

func (u *Updater) MarkApplied(ctx context.Context) error {
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	state, err := u.loadState()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	if state.Status == "ROLLBACK_INTENT" {
		return u.resumePendingReplacementLocked(ctx, state)
	}
	if isReplacementPendingStatus(state.Status) {
		reconciled, pending, reconcileErr := u.reconcileReplacementState(state)
		state = reconciled
		if reconcileErr != nil {
			if state.Status == "FAILED" {
				response := CheckResponse{DeploymentID: state.DeploymentID, Release: Release{ID: state.ReleaseID}}
				_ = u.report(ctx, response, "FAILED", state.Message)
			}
			return reconcileErr
		}
		if !pending {
			return nil
		}
	}
	if state.Status != "APPLYING" {
		if isTerminalStatus(state.Status) {
			return u.cleanupTerminalStaging(state)
		}
		return nil
	}
	response := CheckResponse{DeploymentID: state.DeploymentID, Release: Release{ID: state.ReleaseID}}
	if err := u.report(ctx, response, "APPLIED", "agent restarted after update"); err != nil {
		return err
	}
	state.Status = "APPLIED"
	state.Message = "agent restarted after update"
	if err := u.writeState(state); err != nil {
		return err
	}
	return u.cleanupTerminalStaging(state)
}

func (u *Updater) Rollback(ctx context.Context, reason string) error {
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	state, err := u.loadState()
	if err != nil {
		return err
	}
	if state.Status == "ROLLBACK_INTENT" || state.Status == "ROLLBACKING" {
		return u.resumePendingReplacementLocked(ctx, state)
	}
	if state.Status != "APPLYING" {
		if isTerminalStatus(state.Status) {
			return u.cleanupTerminalStaging(state)
		}
		return nil
	}
	response := CheckResponse{DeploymentID: state.DeploymentID, Release: Release{ID: state.ReleaseID}}
	state.Status = "ROLLBACK_INTENT"
	state.Message = reason
	if err := u.writeState(state); err != nil {
		return err
	}
	reportErr := u.report(ctx, response, "ROLLBACK", reason)
	if err := u.rollbackUpdate(state); err != nil {
		return u.failStagedUpdate(ctx, response, state, "rollback failed: "+err.Error(), err)
	}
	if runtime.GOOS == "windows" {
		state.Status = "ROLLBACKING"
		state.Message = "rollback helper started: " + reason
		if reportErr != nil {
			state.Message += "; ROLLBACK status report failed: " + reportErr.Error()
		}
		if err := u.writeState(state); err != nil {
			return errors.Join(ErrRestartRequired, fmt.Errorf("persist rollback helper state: %w", err))
		}
		if reportErr != nil {
			return errors.Join(ErrRestartRequired, fmt.Errorf("report rollback status: %w", reportErr))
		}
		return ErrRestartRequired
	}
	state.Status = "ROLLBACK"
	state.Message = reason
	if err := u.writeState(state); err != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("persist rollback state: %w", err))
	}
	if err := u.cleanupTerminalStaging(state); err != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("clean rollback staging: %w", err))
	}
	if reportErr != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("report rollback status: %w", reportErr))
	}
	return ErrRestartRequired
}

func (u *Updater) HasPendingApply() bool {
	state, err := u.loadState()
	if err != nil {
		return false
	}
	return isReplacementPendingStatus(state.Status) && !state.ProbationPassed
}

func (u *Updater) ResumePendingReplacement(ctx context.Context) error {
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	state, err := u.loadState()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	return u.resumePendingReplacementLocked(ctx, state)
}

// PrepareUpdateStartup runs before full config, spool, enrollment, or TLS
// initialization. The first verified boot of a new binary gets one probation
// attempt. A second boot without a durable health mark resumes rollback.
func PrepareUpdateStartup(ctx context.Context, updateDir string) (bool, error) {
	if strings.TrimSpace(updateDir) == "" {
		return false, errors.New("update dir is required for startup recovery")
	}
	u := &Updater{config: Config{UpdateDir: filepath.Clean(updateDir)}}
	return prepareUpdateStartupWithUpdater(ctx, u)
}

func prepareUpdateStartupWithUpdater(ctx context.Context, u *Updater) (bool, error) {
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	state, err := u.loadState()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	if state.Status == "ROLLBACK_INTENT" || state.Status == "ROLLBACKING" {
		return false, u.resumePendingReplacementLocked(ctx, state)
	}
	if state.Status != "APPLY_INTENT" && state.Status != "APPLYING" {
		return false, nil
	}

	if err := u.resumePendingReplacementLocked(ctx, state); err != nil {
		if errors.Is(err, ErrRestartRequired) {
			return false, err
		}
		latest, loadErr := u.loadState()
		if loadErr == nil && isTerminalStatus(latest.Status) {
			return false, nil
		}
		return false, err
	}
	state, err = u.loadState()
	if err != nil {
		return false, err
	}
	if state.Status != "APPLYING" || state.ProbationPassed {
		return false, nil
	}
	replaced, err := filesHaveSameContent(state.ArtifactPath, state.TargetPath)
	if err != nil {
		return false, fmt.Errorf("verify probation target: %w", err)
	}
	if !replaced {
		return false, errors.New("probation target does not match staged artifact")
	}
	if state.ProbationAttempts == 0 {
		state.ProbationAttempts = 1
		state.Message = "first updated-binary startup probation"
		if err := u.writeState(state); err != nil {
			return false, fmt.Errorf("persist startup probation: %w", err)
		}
		return true, nil
	}

	state.Status = "ROLLBACK_INTENT"
	state.Message = "updated binary restarted before startup probation completed"
	if err := u.writeState(state); err != nil {
		return false, fmt.Errorf("persist probation rollback intent: %w", err)
	}
	if err := u.resumePendingReplacementLocked(ctx, state); err != nil {
		return false, err
	}
	return false, ErrRestartRequired
}

// MarkUpdateStartupHealthy durably closes local probation before any remote
// status report, so a manager/TLS outage cannot roll back a healthy binary.
func MarkUpdateStartupHealthy(updateDir string) error {
	if strings.TrimSpace(updateDir) == "" {
		return errors.New("update dir is required for startup health")
	}
	u := &Updater{config: Config{UpdateDir: filepath.Clean(updateDir)}}
	u.operationMu.Lock()
	defer u.operationMu.Unlock()

	state, err := u.loadState()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	if state.Status != "APPLYING" || state.ProbationPassed {
		return nil
	}
	replaced, err := filesHaveSameContent(state.ArtifactPath, state.TargetPath)
	if err != nil {
		return fmt.Errorf("verify healthy update target: %w", err)
	}
	if !replaced {
		return errors.New("healthy update target does not match staged artifact")
	}
	state.ProbationPassed = true
	state.Message = "updated binary completed its first collection"
	return u.writeState(state)
}

func (u *Updater) Status() (string, string) {
	state, err := u.loadState()
	if err != nil {
		return "idle", ""
	}
	if state.Status == "" {
		return "idle", ""
	}
	return strings.ToLower(state.Status), state.Message
}

func (u *Updater) check(ctx context.Context) (CheckResponse, error) {
	body, err := json.Marshal(CheckRequest{
		Version:     u.config.Version,
		OS:          runtime.GOOS,
		Arch:        runtime.GOARCH,
		Channel:     u.config.Channel,
		InstallMode: u.config.InstallMode,
	})
	if err != nil {
		return CheckResponse{}, err
	}
	var response CheckResponse
	if err := u.doJSON(ctx, http.MethodPost, "/api/agent/updates/check", body, &response); err != nil {
		return CheckResponse{}, err
	}
	return response, nil
}

func (u *Updater) streamArtifact(ctx context.Context, artifactURL string, destination io.Writer, maxBytes int64) (int64, string, error) {
	if destination == nil {
		return 0, "", errors.New("artifact destination is required")
	}
	if maxBytes <= 0 || maxBytes > u.maxArtifactBytes() {
		maxBytes = u.maxArtifactBytes()
	}
	endpoint, err := u.absoluteURL(artifactURL)
	if err != nil {
		return 0, "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, "", err
	}
	resp, err := u.httpClient.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer drainResponseBody(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return 0, "", fmt.Errorf("artifact download returned status %d", resp.StatusCode)
	}
	if resp.ContentLength > maxBytes {
		return 0, "", fmt.Errorf("artifact exceeds maximum size of %d bytes", maxBytes)
	}
	hasher := sha256.New()
	written, err := io.Copy(io.MultiWriter(destination, hasher), io.LimitReader(resp.Body, maxBytes+1))
	if err != nil {
		return written, "", err
	}
	if written > maxBytes {
		return written, "", fmt.Errorf("artifact exceeds maximum size of %d bytes", maxBytes)
	}
	return written, hex.EncodeToString(hasher.Sum(nil)), nil
}

func (u *Updater) verify(response CheckResponse, artifact []byte) (Manifest, error) {
	manifest, err := u.verifyManifest(response)
	if err != nil {
		return Manifest{}, err
	}
	sum := sha256.Sum256(artifact)
	if err := verifyArtifact(manifest, int64(len(artifact)), hex.EncodeToString(sum[:])); err != nil {
		return Manifest{}, err
	}
	return manifest, nil
}

func (u *Updater) verifyManifest(response CheckResponse) (Manifest, error) {
	publicKey, err := readPublicKey(u.config.PublicKeyPath)
	if err != nil {
		return Manifest{}, err
	}
	signature, err := base64.StdEncoding.DecodeString(response.Signature)
	if err != nil {
		return Manifest{}, fmt.Errorf("invalid manifest signature encoding: %w", err)
	}
	if !ed25519.Verify(publicKey, []byte(response.Manifest), signature) {
		return Manifest{}, errors.New("manifest signature verification failed")
	}
	var manifest Manifest
	if err := json.Unmarshal([]byte(response.Manifest), &manifest); err != nil {
		return Manifest{}, fmt.Errorf("invalid manifest json: %w", err)
	}
	if manifest.Version != response.Release.Version || manifest.OS != runtime.GOOS || manifest.Arch != runtime.GOARCH {
		return Manifest{}, errors.New("manifest does not match this agent")
	}
	if response.Release.OS != "" && response.Release.OS != manifest.OS {
		return Manifest{}, errors.New("release OS does not match manifest")
	}
	if response.Release.Arch != "" && response.Release.Arch != manifest.Arch {
		return Manifest{}, errors.New("release architecture does not match manifest")
	}
	if manifest.Channel != response.Release.Channel {
		return Manifest{}, errors.New("manifest channel does not match release")
	}
	if manifest.SizeBytes < 0 {
		return Manifest{}, errors.New("manifest artifact size must not be negative")
	}
	if manifest.SizeBytes > u.maxArtifactBytes() {
		return Manifest{}, fmt.Errorf("artifact exceeds maximum size of %d bytes", u.maxArtifactBytes())
	}
	if response.Release.SizeBytes < 0 {
		return Manifest{}, errors.New("release artifact size must not be negative")
	}
	if response.Release.SizeBytes > u.maxArtifactBytes() {
		return Manifest{}, fmt.Errorf("artifact exceeds maximum size of %d bytes", u.maxArtifactBytes())
	}
	if response.Release.SizeBytes > 0 && manifest.SizeBytes > 0 && response.Release.SizeBytes != manifest.SizeBytes {
		return Manifest{}, errors.New("release size does not match manifest")
	}
	if response.Release.SHA256 != "" && !strings.EqualFold(response.Release.SHA256, manifest.SHA256) {
		return Manifest{}, errors.New("release sha256 does not match manifest")
	}
	return manifest, nil
}

func verifyArtifact(manifest Manifest, sizeBytes int64, actualSHA256 string) error {
	if !strings.EqualFold(actualSHA256, manifest.SHA256) {
		return fmt.Errorf("artifact sha256 mismatch: got %s", actualSHA256)
	}
	if manifest.SizeBytes > 0 && manifest.SizeBytes != sizeBytes {
		return errors.New("artifact size does not match manifest")
	}
	return nil
}

func (u *Updater) downloadAndVerify(ctx context.Context, response CheckResponse, manifest Manifest) (string, error) {
	stageDir := filepath.Join(u.config.UpdateDir, "staged", response.DeploymentID)
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		return "", err
	}
	artifactPath := filepath.Join(stageDir, executableName())
	tempFile, err := os.CreateTemp(stageDir, ".artifact-*.tmp")
	if err != nil {
		return "", err
	}
	tempPath := tempFile.Name()
	committed := false
	defer func() {
		_ = tempFile.Close()
		if !committed {
			_ = os.Remove(tempPath)
		}
	}()

	limit := u.maxArtifactBytes()
	if manifest.SizeBytes > 0 && manifest.SizeBytes < limit {
		limit = manifest.SizeBytes
	} else if response.Release.SizeBytes > 0 && response.Release.SizeBytes < limit {
		limit = response.Release.SizeBytes
	}
	sizeBytes, actualSHA256, err := u.streamArtifact(ctx, response.ArtifactURL, tempFile, limit)
	if err != nil {
		return "", err
	}
	if err := verifyArtifact(manifest, sizeBytes, actualSHA256); err != nil {
		return "", fmt.Errorf("%w: %v", errArtifactVerification, err)
	}
	if response.Release.SizeBytes > 0 && response.Release.SizeBytes != sizeBytes {
		return "", fmt.Errorf("%w: artifact size does not match release", errArtifactVerification)
	}
	if err := tempFile.Chmod(0o755); err != nil {
		return "", err
	}
	if err := tempFile.Sync(); err != nil {
		return "", err
	}
	if err := tempFile.Close(); err != nil {
		return "", err
	}
	if err := replaceDurableFile(tempPath, artifactPath); err != nil {
		return "", err
	}
	committed = true
	return artifactPath, nil
}

func (u *Updater) stageVerifiedArtifact(response CheckResponse, manifest Manifest, artifactPath string) (State, error) {
	executable, err := os.Executable()
	if err != nil {
		return State{}, err
	}
	if resolved, err := filepath.EvalSymlinks(executable); err == nil {
		executable = resolved
	}
	previousDir := filepath.Join(u.config.UpdateDir, "previous")
	if err := os.MkdirAll(previousDir, 0o700); err != nil {
		return State{}, err
	}
	previousPath := filepath.Join(previousDir, executableName()+".prev")
	mode := fileMode(executable)
	if err := copyFile(executable, previousPath, mode); err != nil {
		return State{}, err
	}
	state := State{
		DeploymentID: response.DeploymentID,
		ReleaseID:    response.Release.ID,
		FromVersion:  u.config.Version,
		ToVersion:    manifest.Version,
		TargetPath:   executable,
		PreviousPath: previousPath,
		ArtifactPath: artifactPath,
		Status:       "DOWNLOADED",
		Message:      "artifact staged",
		UpdatedAt:    time.Now().UTC().Format(time.RFC3339),
	}
	return state, u.writeState(state)
}

func (u *Updater) maxArtifactBytes() int64 {
	if u.config.MaxArtifactBytes > 0 {
		return u.config.MaxArtifactBytes
	}
	return defaultMaxArtifactBytes
}

func (u *Updater) apply(state State) error {
	if runtime.GOOS == "windows" {
		return u.startWindowsHelper(state)
	}
	if err := replaceFile(state.ArtifactPath, state.TargetPath, 0o755); err != nil {
		return err
	}
	return nil
}

func (u *Updater) applyUpdate(state State) error {
	if u.applyArtifact != nil {
		return u.applyArtifact(state)
	}
	return u.apply(state)
}

func (u *Updater) rollbackUpdate(state State) error {
	if u.rollbackArtifact != nil {
		return u.rollbackArtifact(state)
	}
	if runtime.GOOS == "windows" {
		return u.startWindowsReplacementHelper(state, state.PreviousPath)
	}
	return replaceFile(state.PreviousPath, state.TargetPath, 0o755)
}

func (u *Updater) failStagedUpdate(ctx context.Context, response CheckResponse, state State, message string, cause error) error {
	state.Status = "FAILED"
	state.Message = message
	var failures []error
	statePersisted := false
	if cause != nil {
		failures = append(failures, cause)
	}
	if err := u.writeState(state); err != nil {
		failures = append(failures, fmt.Errorf("persist failed update state: %w", err))
	} else {
		statePersisted = true
	}
	if err := u.report(ctx, response, "FAILED", message); err != nil {
		failures = append(failures, fmt.Errorf("report failed update state: %w", err))
	}
	if statePersisted {
		if err := u.cleanupTerminalStaging(state); err != nil {
			failures = append(failures, fmt.Errorf("clean failed update staging: %w", err))
		}
	}
	return errors.Join(failures...)
}

func isTerminalStatus(status string) bool {
	switch status {
	case "APPLIED", "ROLLBACK", "FAILED":
		return true
	default:
		return false
	}
}

func isReplacementPendingStatus(status string) bool {
	switch status {
	case "APPLY_INTENT", "APPLYING", "ROLLBACK_INTENT", "ROLLBACKING":
		return true
	default:
		return false
	}
}

func (u *Updater) resumePendingReplacementLocked(ctx context.Context, state State) error {
	if state.Status != "ROLLBACK_INTENT" {
		reconciled, _, reconcileErr := u.reconcileReplacementState(state)
		if reconcileErr != nil && reconciled.Status == "FAILED" {
			response := CheckResponse{DeploymentID: reconciled.DeploymentID, Release: Release{ID: reconciled.ReleaseID}}
			_ = u.report(ctx, response, "FAILED", reconciled.Message)
		}
		if errors.Is(reconcileErr, errReplacementHelperIncomplete) {
			return errors.Join(ErrRestartRequired, reconcileErr)
		}
		return reconcileErr
	}

	replaced, compareErr := filesHaveSameContent(state.PreviousPath, state.TargetPath)
	if compareErr == nil && replaced {
		state.Status = "ROLLBACK"
		state.Message = "rollback replacement verified against previous binary"
		if err := u.writeState(state); err != nil {
			return fmt.Errorf("persist recovered rollback state: %w", err)
		}
		return u.cleanupTerminalStaging(state)
	}
	if u.windowsHelperMayBeRunning() {
		return ErrRestartRequired
	}

	state.Message = "resuming interrupted rollback replacement"
	if compareErr != nil {
		state.Message += ": " + compareErr.Error()
	}
	if err := u.writeState(state); err != nil {
		return fmt.Errorf("persist resumed rollback intent: %w", err)
	}
	if err := u.rollbackUpdate(state); err != nil {
		state.Message = "resumed rollback replacement failed: " + err.Error()
		if writeErr := u.writeState(state); writeErr != nil {
			return errors.Join(err, fmt.Errorf("persist retryable rollback intent: %w", writeErr))
		}
		return err
	}

	if u.rollbackRunsAsync || (runtime.GOOS == "windows" && u.rollbackArtifact == nil) {
		state.Status = "ROLLBACKING"
		state.Message = "resumed rollback helper started"
		if err := u.writeState(state); err != nil {
			return errors.Join(ErrRestartRequired, fmt.Errorf("persist resumed rollback helper state: %w", err))
		}
		return ErrRestartRequired
	}

	replaced, compareErr = filesHaveSameContent(state.PreviousPath, state.TargetPath)
	if compareErr != nil || !replaced {
		if compareErr == nil {
			compareErr = errors.New("target does not match previous binary after resumed rollback")
		}
		state.Message = "resumed rollback replacement is not verifiable: " + compareErr.Error()
		if writeErr := u.writeState(state); writeErr != nil {
			return errors.Join(compareErr, fmt.Errorf("persist retryable rollback intent: %w", writeErr))
		}
		return compareErr
	}
	state.Status = "ROLLBACK"
	state.Message = "interrupted rollback replacement resumed successfully"
	if err := u.writeState(state); err != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("persist resumed rollback state: %w", err))
	}
	if err := u.cleanupTerminalStaging(state); err != nil {
		return errors.Join(ErrRestartRequired, fmt.Errorf("clean resumed rollback staging: %w", err))
	}
	return ErrRestartRequired
}

func (u *Updater) reconcileReplacementState(state State) (State, bool, error) {
	var sourcePath string
	var completedStatus string
	var completedMessage string
	var completedPending bool
	switch state.Status {
	case "APPLY_INTENT", "APPLYING":
		sourcePath = state.ArtifactPath
		completedStatus = "APPLYING"
		completedMessage = "binary replacement verified against staged artifact"
		completedPending = true
	case "ROLLBACK_INTENT", "ROLLBACKING":
		sourcePath = state.PreviousPath
		completedStatus = "ROLLBACK"
		completedMessage = "rollback replacement verified against previous binary"
	default:
		return state, false, nil
	}

	replaced, compareErr := filesHaveSameContent(sourcePath, state.TargetPath)
	if compareErr == nil && replaced {
		if state.Status != completedStatus {
			state.Status = completedStatus
			state.Message = completedMessage
			if err := u.writeState(state); err != nil {
				return state, completedPending, fmt.Errorf("persist reconciled %s state: %w", strings.ToLower(completedStatus), err)
			}
		}
		if isTerminalStatus(state.Status) {
			if err := u.cleanupTerminalStaging(state); err != nil {
				return state, false, fmt.Errorf("clean reconciled terminal staging: %w", err)
			}
		}
		return state, completedPending, nil
	}

	if u.windowsHelperMayBeRunning() {
		if compareErr != nil {
			return state, true, fmt.Errorf("%w: %v", errReplacementHelperIncomplete, compareErr)
		}
		return state, true, errReplacementHelperIncomplete
	}

	operation := "binary replacement"
	if completedStatus == "ROLLBACK" {
		operation = "rollback replacement"
	}
	state.Status = "FAILED"
	state.Message = operation + " did not produce the expected target"
	if compareErr != nil {
		state.Message += ": " + compareErr.Error()
	}
	if err := u.writeState(state); err != nil {
		return state, false, fmt.Errorf("persist failed reconciliation state: %w", err)
	}
	if err := u.cleanupTerminalStaging(state); err != nil {
		return state, false, fmt.Errorf("clean failed reconciliation staging: %w", err)
	}
	return state, false, errors.New(state.Message)
}

func (u *Updater) windowsHelperMayBeRunning() bool {
	if runtime.GOOS != "windows" {
		return false
	}
	info, err := os.Stat(filepath.Join(u.config.UpdateDir, "update_helper_state.json"))
	if err != nil {
		return !errors.Is(err, os.ErrNotExist)
	}
	age := time.Since(info.ModTime())
	return age < 0 || age <= windowsHelperActivityWindow
}

func (u *Updater) cleanupTerminalStaging(state State) error {
	if !isTerminalStatus(state.Status) {
		return nil
	}
	if strings.TrimSpace(u.config.UpdateDir) == "" {
		return errors.New("update dir is required to clean terminal staging")
	}
	return os.RemoveAll(filepath.Join(u.config.UpdateDir, "staged"))
}

func (u *Updater) startWindowsHelper(state State) error {
	return u.startWindowsReplacementHelper(state, state.ArtifactPath)
}

func (u *Updater) startWindowsReplacementHelper(state State, sourcePath string) error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	helperPath := filepath.Join(u.config.UpdateDir, "castrelyx-update-helper.exe")
	if err := copyFile(executable, helperPath, 0o755); err != nil {
		return err
	}
	state.ReplacementSourcePath = sourcePath
	state.HelperMainStatePath = u.statePath()
	helperStatePath := filepath.Join(u.config.UpdateDir, "update_helper_state.json")
	if err := writeStateFile(helperStatePath, state); err != nil {
		return err
	}
	cmd := exec.Command(helperPath, "-update-helper", "-update-state", helperStatePath, "-update-service", "CastrelyxAgent")
	if err := cmd.Start(); err != nil {
		_ = os.Remove(helperStatePath)
		return err
	}
	return nil
}

func RunWindowsHelper(statePath, serviceName string) error {
	return runWindowsHelper(statePath, serviceName, 60, 3, time.Second, replaceFile, startWindowsService)
}

func runWindowsHelper(
	statePath string,
	serviceName string,
	replaceAttempts int,
	serviceStartAttempts int,
	retryDelay time.Duration,
	replace func(string, string, os.FileMode) error,
	startService func(string) error,
) error {
	state, err := readState(statePath)
	if err != nil {
		return err
	}
	defer os.Remove(statePath)
	if replaceAttempts <= 0 {
		replaceAttempts = 1
	}
	if serviceStartAttempts <= 0 {
		serviceStartAttempts = 1
	}
	replacementSource := state.ReplacementSourcePath
	if replacementSource == "" {
		replacementSource = state.ArtifactPath
	}
	var replaceErr error
	for i := 0; i < replaceAttempts; i++ {
		replaceErr = replace(replacementSource, state.TargetPath, 0o755)
		if replaceErr == nil {
			break
		}
		if retryDelay > 0 && i+1 < replaceAttempts {
			time.Sleep(retryDelay)
		}
	}
	var serviceErr error
	if serviceName != "" {
		for i := 0; i < serviceStartAttempts; i++ {
			serviceErr = startService(serviceName)
			if serviceErr == nil {
				break
			}
			if retryDelay > 0 && i+1 < serviceStartAttempts {
				time.Sleep(retryDelay)
			}
		}
	}
	helperErr := errors.Join(replaceErr, serviceErr)
	if helperErr != nil {
		state.Status = "FAILED"
		state.Message = "Windows replacement helper failed: " + helperErr.Error()
		mainStatePath := state.HelperMainStatePath
		state.ReplacementSourcePath = ""
		state.HelperMainStatePath = ""
		if mainStatePath != "" {
			if stateErr := writeStateFile(mainStatePath, state); stateErr != nil {
				helperErr = errors.Join(helperErr, fmt.Errorf("persist Windows helper failure: %w", stateErr))
			}
		}
		return helperErr
	}
	return nil
}

func startWindowsService(serviceName string) error {
	return exec.Command("sc.exe", "start", serviceName).Run()
}

func (u *Updater) report(ctx context.Context, response CheckResponse, status, message string) error {
	if response.DeploymentID == "" || response.Release.ID == 0 {
		return nil
	}
	if u.httpClient == nil {
		return nil
	}
	body, err := json.Marshal(statusRequest{
		DeploymentID: response.DeploymentID,
		ReleaseID:    response.Release.ID,
		FromVersion:  u.config.Version,
		Status:       status,
		Message:      message,
	})
	if err != nil {
		return err
	}
	return u.doJSON(ctx, http.MethodPost, "/api/agent/updates/status", body, nil)
}

func (u *Updater) doJSON(ctx context.Context, method, path string, body []byte, out any) error {
	endpoint, err := u.absoluteURL(path)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := u.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer drainResponseBody(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		payload, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s returned status %d: %s", path, resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	if out == nil || resp.StatusCode == http.StatusNoContent {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func drainResponseBody(body io.ReadCloser) {
	if body == nil {
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(body, 64*1024))
	_ = body.Close()
}

func (u *Updater) absoluteURL(path string) (string, error) {
	if strings.HasPrefix(path, "https://") {
		return path, nil
	}
	if strings.HasPrefix(path, "http://") {
		return "", errors.New("update endpoint must use https")
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	parsed, err := url.Parse(u.config.BaseURL)
	if err != nil {
		return "", err
	}
	return strings.TrimRight(parsed.String(), "/") + path, nil
}

func (u *Updater) statePath() string {
	return filepath.Join(u.config.UpdateDir, "update_state.json")
}

func (u *Updater) loadState() (State, error) {
	u.stateMu.Lock()
	defer u.stateMu.Unlock()
	return readState(u.statePath())
}

func (u *Updater) writeState(state State) error {
	u.stateMu.Lock()
	defer u.stateMu.Unlock()
	state.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	return writeStateFile(u.statePath(), state)
}

func writeStateFile(path string, state State) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	payload, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".update-state-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return err
	}
	if _, err := temporary.Write(payload); err != nil {
		return err
	}
	if err := temporary.Sync(); err != nil {
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := replaceDurableFile(temporaryPath, path); err != nil {
		return err
	}
	committed = true
	return nil
}

func readState(path string) (State, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return State{}, err
	}
	var state State
	if err := json.Unmarshal(payload, &state); err != nil {
		return State{}, err
	}
	return state, nil
}

func filesHaveSameContent(leftPath, rightPath string) (bool, error) {
	leftInfo, err := os.Stat(leftPath)
	if err != nil {
		return false, err
	}
	rightInfo, err := os.Stat(rightPath)
	if err != nil {
		return false, err
	}
	if leftInfo.Size() != rightInfo.Size() {
		return false, nil
	}
	leftDigest, err := fileSHA256(leftPath)
	if err != nil {
		return false, err
	}
	rightDigest, err := fileSHA256(rightPath)
	if err != nil {
		return false, err
	}
	return leftDigest == rightDigest, nil
}

func fileSHA256(path string) ([sha256.Size]byte, error) {
	input, err := os.Open(path)
	if err != nil {
		return [sha256.Size]byte{}, err
	}
	defer input.Close()
	hasher := sha256.New()
	if _, err := io.Copy(hasher, input); err != nil {
		return [sha256.Size]byte{}, err
	}
	var digest [sha256.Size]byte
	copy(digest[:], hasher.Sum(nil))
	return digest, nil
}

func readPublicKey(path string) (ed25519.PublicKey, error) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(payload)
	if block == nil {
		return nil, errors.New("update public key is not PEM encoded")
	}
	key, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	publicKey, ok := key.(ed25519.PublicKey)
	if !ok {
		return nil, errors.New("update public key must be Ed25519")
	}
	return publicKey, nil
}

func copyFile(source, destination string, mode os.FileMode) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
		return err
	}
	output, err := os.CreateTemp(filepath.Dir(destination), ".durable-copy-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := output.Name()
	committed := false
	defer func() {
		_ = output.Close()
		if !committed {
			_ = os.Remove(temporaryPath)
		}
	}()
	if _, err := io.Copy(output, input); err != nil {
		return err
	}
	if err := output.Chmod(mode); err != nil {
		return err
	}
	if err := output.Sync(); err != nil {
		return err
	}
	if err := output.Close(); err != nil {
		return err
	}
	if err := replaceDurableFile(temporaryPath, destination); err != nil {
		return err
	}
	committed = true
	return nil
}

func replaceFile(source, target string, mode os.FileMode) error {
	return copyFile(source, target, mode)
}

func fileMode(path string) os.FileMode {
	info, err := os.Stat(path)
	if err != nil {
		return 0o755
	}
	return info.Mode().Perm()
}

func executableName() string {
	if runtime.GOOS == "windows" {
		return "castrelyx-agent.exe"
	}
	return "castrelyx-agent"
}

func defaultInstallMode() string {
	if runtime.GOOS == "windows" {
		return "service"
	}
	return "daemon"
}
