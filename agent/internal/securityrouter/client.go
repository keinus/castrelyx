// Package securityrouter implements the bounded Security Router control adapter.
// Router credentials and destinations come only from local agent configuration.
package securityrouter

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

type Config struct {
	Enabled                                          bool
	URL, CAFile, CredentialsFile, RouterID, StateDir string
}
type Request struct {
	Action               string            `json:"action"`
	RouterID             string            `json:"routerId"`
	ExpectedRevision     *int64            `json:"expectedRevision,omitempty"`
	Kind                 string            `json:"kind,omitempty"`
	Config               json.RawMessage   `json:"config,omitempty"`
	Parameters           map[string]string `json:"parameters,omitempty"`
	JobID                string            `json:"jobId,omitempty"`
	BackupID             string            `json:"backupId,omitempty"`
	Mode                 string            `json:"mode,omitempty"`
	Until                string            `json:"until,omitempty"`
	Service              string            `json:"service,omitempty"`
	ConfirmWithinSeconds int               `json:"confirmWithinSeconds,omitempty"`
}
type Client struct {
	cfg     Config
	http    *http.Client
	mu      sync.Mutex
	token   string
	loginAt time.Time
	restart func(context.Context, string) error
}
type outcome struct {
	Hash    string         `json:"hash"`
	Started bool           `json:"started"`
	Result  map[string]any `json:"result"`
	Error   string         `json:"error,omitempty"`
}

var identifier = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,128}$`)

func New(cfg Config) (*Client, error) {
	if !cfg.Enabled {
		return nil, nil
	}
	u, err := url.Parse(cfg.URL)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("security_router_url must be an HTTPS origin")
	}
	if cfg.RouterID == "" || cfg.CredentialsFile == "" || cfg.StateDir == "" {
		return nil, errors.New("router ID, credential file and state directory are required")
	}
	ca, err := os.ReadFile(cfg.CAFile)
	if err != nil {
		return nil, fmt.Errorf("router CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(ca) {
		return nil, errors.New("invalid router CA")
	}
	if err = os.MkdirAll(cfg.StateDir, 0700); err != nil {
		return nil, err
	}
	c := &Client{cfg: cfg, http: &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}}, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("router redirects are forbidden") }}}
	c.restart = func(ctx context.Context, service string) error {
		if runtime.GOOS != "linux" {
			return errors.New("service restart requires Linux")
		}
		if err := exec.CommandContext(ctx, "systemctl", "restart", service).Run(); err != nil {
			return fmt.Errorf("service restart failed: %w", err)
		}
		return exec.CommandContext(ctx, "systemctl", "is-active", "--quiet", service).Run()
	}
	return c, nil
}

// Execute journals the outcome before returning it to the mTLS task channel.
// Router submissions use the same idempotency key even after a process crash.
func (c *Client) Execute(ctx context.Context, taskID string, payload []byte) (map[string]any, error) {
	if c == nil {
		return nil, errors.New("security router control is disabled on this agent")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !identifier.MatchString(taskID) {
		return nil, errors.New("invalid router task ID")
	}
	var r Request
	d := json.NewDecoder(bytes.NewReader(payload))
	d.DisallowUnknownFields()
	if err := d.Decode(&r); err != nil {
		return nil, err
	}
	if d.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("trailing JSON")
	}
	if r.RouterID != c.cfg.RouterID {
		return nil, errors.New("router binding does not match this agent")
	}
	sum := sha256.Sum256(payload)
	hash := hex.EncodeToString(sum[:])
	path := filepath.Join(c.cfg.StateDir, "task-"+taskID+".json")
	var saved outcome
	if data, err := os.ReadFile(path); err == nil {
		if err = json.Unmarshal(data, &saved); err != nil {
			return nil, errors.New("router task journal is corrupt")
		}
		if saved.Hash != hash {
			return nil, errors.New("task ID reused with different payload")
		}
		if !saved.Started {
			return saved.Result, resultError(saved.Error)
		}
		if r.Action == "restart" {
			return nil, errors.New("restart outcome unknown after interruption; inspect service state before a new request")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err := save(path, outcome{Hash: hash, Started: true}); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 75*time.Second)
	defer cancel()
	result, err := c.run(ctx, taskID, r)
	saved = outcome{Hash: hash, Result: result}
	if err != nil {
		saved.Error = err.Error()
	}
	if writeErr := save(path, saved); writeErr != nil {
		return nil, fmt.Errorf("could not persist router outcome: %w", writeErr)
	}
	c.cleanup()
	return result, err
}
func resultError(message string) error {
	if message == "" {
		return nil
	}
	return errors.New(message)
}

func (c *Client) run(ctx context.Context, id string, r Request) (map[string]any, error) {
	allowed := map[string]bool{"state": true, "jobs": true, "audit": true, "validate": true, "apply": true, "mode": true, "backup": true, "backups": true, "restore": true, "restart": true, "confirm": true, "rollback": true}
	if !allowed[r.Action] {
		return nil, errors.New("unsupported router action")
	}
	state, err := c.call(ctx, "GET", "/api/v1/state", nil)
	if err != nil {
		return nil, err
	}
	config, _ := state["config"].(map[string]any)
	device, _ := config["device"].(map[string]any)
	if device["id"] != c.cfg.RouterID {
		return nil, errors.New("router returned a different device identity")
	}
	switch r.Action {
	case "state":
		state["controlVersion"] = "2"
		state["actions"] = []string{"state", "jobs", "audit", "validate", "apply", "mode", "backup", "backups", "restore", "restart", "confirm", "rollback"}
		state["restartServices"] = restartServices
		return state, nil
	case "confirm", "rollback":
		if !identifier.MatchString(r.JobID) {
			return nil, errors.New("valid router job ID required")
		}
		return c.call(ctx, "POST", "/api/v1/jobs/"+r.JobID+"/"+r.Action, map[string]any{})
	case "jobs", "audit":
		path := "/api/v1/" + r.Action
		if r.JobID != "" {
			if r.Action != "jobs" || !identifier.MatchString(r.JobID) {
				return nil, errors.New("invalid job ID")
			}
			path += "/" + r.JobID
		}
		return c.call(ctx, "GET", path, nil)
	case "backup":
		return c.backup(id, state)
	case "backups":
		entries, err := os.ReadDir(c.cfg.StateDir)
		if err != nil {
			return nil, err
		}
		items := []map[string]any{}
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), "backup-") && strings.HasSuffix(entry.Name(), ".json") {
				info, er := entry.Info()
				if er == nil {
					items = append(items, map[string]any{"id": strings.TrimSuffix(strings.TrimPrefix(entry.Name(), "backup-"), ".json"), "createdAt": info.ModTime().UTC()})
				}
			}
		}
		return map[string]any{"items": items}, nil
	case "mode":
		if r.Mode != "automatic" && r.Mode != "maintenance" && r.Mode != "emergency-stop" {
			return nil, errors.New("invalid router mode")
		}
		body := map[string]any{"mode": r.Mode}
		if r.Until != "" {
			body["until"] = r.Until
		}
		return c.call(ctx, "POST", "/api/v1/mode", body)
	case "restart":
		ok := false
		for _, service := range restartServices {
			if r.Service == service {
				ok = true
			}
		}
		if !ok {
			return nil, errors.New("service is not in the router restart allowlist")
		}
		jobs, err := c.call(ctx, "GET", "/api/v1/jobs", nil)
		if err != nil {
			return nil, err
		}
		if items, ok := jobs["items"].([]any); ok {
			for _, item := range items {
				j, _ := item.(map[string]any)
				if !terminalJob(fmt.Sprint(j["status"])) {
					return nil, errors.New("router has unfinished jobs; restart denied")
				}
			}
		}
		if err := c.restart(ctx, r.Service); err != nil {
			return nil, err
		}
		return map[string]any{"service": r.Service, "status": "active"}, nil
	}
	if r.ExpectedRevision == nil || *r.ExpectedRevision < 0 {
		return nil, errors.New("expectedRevision is required")
	}
	if r.Action == "restore" || (r.Action == "validate" && r.BackupID != "") {
		if !identifier.MatchString(r.BackupID) {
			return nil, errors.New("invalid backup ID")
		}
		data, err := os.ReadFile(filepath.Join(c.cfg.StateDir, "backup-"+r.BackupID+".json"))
		if err != nil {
			return nil, err
		}
		var backup struct {
			Config   json.RawMessage `json:"config"`
			RouterID string          `json:"routerId"`
		}
		if err = json.Unmarshal(data, &backup); err != nil {
			return nil, err
		}
		if backup.RouterID != r.RouterID {
			return nil, errors.New("backup belongs to another router")
		}
		r.Config = backup.Config
		r.Kind = "config"
	}
	if r.Kind != "config" && r.Kind != "port.admin" && r.Kind != "port.vlan" && r.Kind != "system" && r.Kind != "quarantine.release" {
		return nil, errors.New("unsupported router job kind")
	}
	if r.Kind == "config" {
		var candidate struct {
			Device struct {
				ID string `json:"id"`
			} `json:"device"`
		}
		if json.Unmarshal(r.Config, &candidate) != nil || candidate.Device.ID != c.cfg.RouterID {
			return nil, errors.New("configuration must preserve the router device identity")
		}
	}
	body := map[string]any{"idempotencyKey": "castrelyx-" + id, "expectedRevision": *r.ExpectedRevision, "kind": r.Kind}
	if r.ConfirmWithinSeconds != 0 {
		if r.ConfirmWithinSeconds < 30 || r.ConfirmWithinSeconds > 300 {
			return nil, errors.New("confirmation window must be 30..300 seconds")
		}
		body["confirmWithinSeconds"] = r.ConfirmWithinSeconds
	}
	if len(r.Config) > 0 {
		body["config"] = r.Config
	}
	if r.Parameters != nil {
		body["parameters"] = r.Parameters
	}
	if r.Action == "validate" {
		return c.call(ctx, "POST", "/api/v1/policies/validate", body)
	}
	// Back up before every submission. The Router owns revision checks, validation,
	// serialized application, verification and independent executor rollback.
	if _, err := c.backup(id, state); err != nil {
		return nil, err
	}
	result, err := c.call(ctx, "POST", "/api/v1/jobs", body)
	if result != nil {
		result["backupId"] = id
	}
	return result, err
}

var restartServices = []string{"switchd", "switch-zeek", "switch-telemetryd", "switch-lldpd", "switch-syslog", "switch-agentx", "switch-snmpd"}

func terminalJob(s string) bool {
	return s == "committed" || s == "rejected" || s == "rolled_back" || s == "cancelled"
}
func (c *Client) backup(id string, state map[string]any) (map[string]any, error) {
	path := filepath.Join(c.cfg.StateDir, "backup-"+id+".json")
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		// These are API configuration snapshots, not credential/database backups.
		if err = save(path, map[string]any{"routerId": c.cfg.RouterID, "revision": state["appliedRevision"], "config": state["config"], "createdAt": time.Now().UTC()}); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	return map[string]any{"backupId": id, "revision": state["appliedRevision"]}, nil
}
func (c *Client) call(ctx context.Context, method, path string, body any) (map[string]any, error) {
	if c.token == "" || time.Since(c.loginAt) > 7*time.Hour {
		if err := c.login(ctx); err != nil {
			return nil, err
		}
	}
	result, status, err := c.request(ctx, method, path, body, c.token)
	if status == 401 {
		c.token = ""
		if err = c.login(ctx); err != nil {
			return nil, err
		}
		result, _, err = c.request(ctx, method, path, body, c.token)
	}
	return result, err
}
func (c *Client) login(ctx context.Context) error {
	info, err := os.Lstat(c.cfg.CredentialsFile)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || (runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		return errors.New("router credentials must be a private regular file")
	}
	data, err := os.ReadFile(c.cfg.CredentialsFile)
	if err != nil {
		return err
	}
	var credentials struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err = json.Unmarshal(data, &credentials); err != nil {
		return errors.New("invalid router credentials file")
	}
	result, _, err := c.request(ctx, "POST", "/api/v1/login", credentials, "")
	if err != nil {
		return errors.New("router authentication failed")
	}
	c.token, _ = result["token"].(string)
	if c.token == "" {
		return errors.New("router authentication returned no token")
	}
	c.loginAt = time.Now()
	return nil
}
func (c *Client) request(ctx context.Context, method, path string, body any, token string) (map[string]any, int, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, 0, err
	}
	if len(data) > 1<<20 {
		return nil, 0, errors.New("router request exceeds 1 MiB")
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.cfg.URL, "/")+path, bytes.NewReader(data))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("router transport: %w", err)
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, (4<<20)+1))
	if err != nil {
		return nil, res.StatusCode, err
	}
	if len(raw) > 4<<20 {
		return nil, res.StatusCode, errors.New("router response too large")
	}
	var value any
	if err = json.Unmarshal(raw, &value); err != nil {
		return nil, res.StatusCode, fmt.Errorf("router returned non-JSON (HTTP %d)", res.StatusCode)
	}
	obj, ok := value.(map[string]any)
	if !ok {
		obj = map[string]any{"items": value}
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return obj, res.StatusCode, fmt.Errorf("router HTTP %d: %v", res.StatusCode, obj["error"])
	}
	return obj, res.StatusCode, nil
}
func save(path string, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".router-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(name, path); err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		dir, err := os.Open(filepath.Dir(path))
		if err != nil {
			return err
		}
		defer dir.Close()
		return dir.Sync()
	}
	return nil
}
func (c *Client) cleanup() {
	entries, _ := os.ReadDir(c.cfg.StateDir)
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "task-") && strings.HasSuffix(entry.Name(), ".json") {
			if info, err := entry.Info(); err == nil && time.Since(info.ModTime()) > 7*24*time.Hour {
				_ = os.Remove(filepath.Join(c.cfg.StateDir, entry.Name()))
			}
		}
	}
}
