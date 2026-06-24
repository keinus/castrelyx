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
	"time"
)

var ErrRestartRequired = errors.New("restart required to apply agent update")

type Config struct {
	BaseURL       string
	AgentID       string
	Version       string
	Channel       string
	InstallMode   string
	UpdateDir     string
	PublicKeyPath string
	TLSConfig     *tls.Config
}

type Updater struct {
	config     Config
	httpClient *http.Client
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
	DeploymentID string `json:"deployment_id"`
	ReleaseID    int64  `json:"release_id"`
	FromVersion  string `json:"from_version"`
	ToVersion    string `json:"to_version"`
	TargetPath   string `json:"target_path"`
	PreviousPath string `json:"previous_path"`
	ArtifactPath string `json:"artifact_path"`
	Status       string `json:"status"`
	Message      string `json:"message,omitempty"`
	UpdatedAt    string `json:"updated_at"`
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
	response, err := u.check(ctx)
	if err != nil || !response.UpdateAvailable {
		return err
	}
	artifact, err := u.download(ctx, response.ArtifactURL)
	if err != nil {
		_ = u.report(ctx, response, "FAILED", "download failed: "+err.Error())
		return err
	}
	manifest, err := u.verify(response, artifact)
	if err != nil {
		_ = u.report(ctx, response, "VERIFY_FAILED", err.Error())
		return err
	}
	state, err := u.stage(response, manifest, artifact)
	if err != nil {
		_ = u.report(ctx, response, "FAILED", "stage failed: "+err.Error())
		return err
	}
	if err := u.report(ctx, response, "DOWNLOADED", "artifact verified and staged"); err != nil {
		return err
	}
	state.Status = "APPLYING"
	state.Message = "replacing agent binary"
	if err := u.writeState(state); err != nil {
		return err
	}
	if err := u.report(ctx, response, "APPLYING", "replacing agent binary"); err != nil {
		return err
	}
	if err := u.apply(state); err != nil {
		return err
	}
	return ErrRestartRequired
}

func (u *Updater) MarkApplied(ctx context.Context) error {
	state, err := u.loadState()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	if state.Status != "APPLYING" {
		return nil
	}
	response := CheckResponse{DeploymentID: state.DeploymentID, Release: Release{ID: state.ReleaseID}}
	if err := u.report(ctx, response, "APPLIED", "agent restarted after update"); err != nil {
		return err
	}
	state.Status = "APPLIED"
	state.Message = "agent restarted after update"
	return u.writeState(state)
}

func (u *Updater) Rollback(ctx context.Context, reason string) error {
	state, err := u.loadState()
	if err != nil {
		return err
	}
	if state.Status != "APPLYING" {
		return nil
	}
	response := CheckResponse{DeploymentID: state.DeploymentID, Release: Release{ID: state.ReleaseID}}
	_ = u.report(ctx, response, "ROLLBACK", reason)
	if err := replaceFile(state.PreviousPath, state.TargetPath, 0o755); err != nil {
		_ = u.report(ctx, response, "FAILED", "rollback failed: "+err.Error())
		return err
	}
	state.Status = "ROLLBACK"
	state.Message = reason
	_ = u.writeState(state)
	return ErrRestartRequired
}

func (u *Updater) HasPendingApply() bool {
	state, err := u.loadState()
	return err == nil && state.Status == "APPLYING"
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

func (u *Updater) download(ctx context.Context, artifactURL string) ([]byte, error) {
	endpoint, err := u.absoluteURL(artifactURL)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := u.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("artifact download returned status %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

func (u *Updater) verify(response CheckResponse, artifact []byte) (Manifest, error) {
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
	sum := sha256.Sum256(artifact)
	actual := hex.EncodeToString(sum[:])
	if !strings.EqualFold(actual, manifest.SHA256) {
		return Manifest{}, fmt.Errorf("artifact sha256 mismatch: got %s", actual)
	}
	if manifest.Version != response.Release.Version || manifest.OS != runtime.GOOS || manifest.Arch != runtime.GOARCH {
		return Manifest{}, errors.New("manifest does not match this agent")
	}
	if manifest.Channel != response.Release.Channel {
		return Manifest{}, errors.New("manifest channel does not match release")
	}
	if manifest.SizeBytes > 0 && manifest.SizeBytes != int64(len(artifact)) {
		return Manifest{}, errors.New("artifact size does not match manifest")
	}
	return manifest, nil
}

func (u *Updater) stage(response CheckResponse, manifest Manifest, artifact []byte) (State, error) {
	executable, err := os.Executable()
	if err != nil {
		return State{}, err
	}
	if resolved, err := filepath.EvalSymlinks(executable); err == nil {
		executable = resolved
	}
	stageDir := filepath.Join(u.config.UpdateDir, "staged", response.DeploymentID)
	if err := os.MkdirAll(stageDir, 0o700); err != nil {
		return State{}, err
	}
	artifactPath := filepath.Join(stageDir, executableName())
	if err := os.WriteFile(artifactPath, artifact, 0o755); err != nil {
		return State{}, err
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

func (u *Updater) apply(state State) error {
	if runtime.GOOS == "windows" {
		return u.startWindowsHelper(state)
	}
	if err := replaceFile(state.ArtifactPath, state.TargetPath, 0o755); err != nil {
		return err
	}
	return nil
}

func (u *Updater) startWindowsHelper(state State) error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	helperPath := filepath.Join(u.config.UpdateDir, "castrelyx-update-helper.exe")
	if err := copyFile(executable, helperPath, 0o755); err != nil {
		return err
	}
	cmd := exec.Command(helperPath, "-update-helper", "-update-state", u.statePath(), "-update-service", "CastrelyxAgent")
	return cmd.Start()
}

func RunWindowsHelper(statePath, serviceName string) error {
	state, err := readState(statePath)
	if err != nil {
		return err
	}
	var replaceErr error
	for i := 0; i < 60; i++ {
		replaceErr = replaceFile(state.ArtifactPath, state.TargetPath, 0o755)
		if replaceErr == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if replaceErr != nil {
		return replaceErr
	}
	if serviceName != "" {
		_ = exec.Command("sc.exe", "start", serviceName).Run()
	}
	return nil
}

func (u *Updater) report(ctx context.Context, response CheckResponse, status, message string) error {
	if response.DeploymentID == "" || response.Release.ID == 0 {
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
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		payload, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s returned status %d: %s", path, resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	if out == nil || resp.StatusCode == http.StatusNoContent {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
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
	return readState(u.statePath())
}

func (u *Updater) writeState(state State) error {
	state.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	if err := os.MkdirAll(filepath.Dir(u.statePath()), 0o700); err != nil {
		return err
	}
	payload, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(u.statePath(), payload, 0o600)
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
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(output, input); err != nil {
		_ = output.Close()
		return err
	}
	if err := output.Close(); err != nil {
		return err
	}
	return os.Chmod(destination, mode)
}

func replaceFile(source, target string, mode os.FileMode) error {
	tmp := target + ".new"
	if err := copyFile(source, tmp, mode); err != nil {
		return err
	}
	if runtime.GOOS == "windows" {
		_ = os.Remove(target)
	}
	if err := os.Rename(tmp, target); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
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
