package securityrouter

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func fixture(t *testing.T) (*Client, *atomic.Int32) {
	t.Helper()
	calls := &atomic.Int32{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/v1/login" {
			_ = json.NewEncoder(w).Encode(map[string]string{"token": "private-token"})
			return
		}
		if r.Header.Get("Authorization") != "Bearer private-token" {
			w.WriteHeader(401)
			return
		}
		switch r.URL.Path {
		case "/api/v1/state":
			_ = json.NewEncoder(w).Encode(map[string]any{"appliedRevision": 4, "config": map[string]any{"device": map[string]string{"id": "router-25"}}})
		case "/api/v1/jobs":
			if r.Method == "GET" {
				_, _ = w.Write([]byte(`[]`))
				return
			}
			calls.Add(1)
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["expectedRevision"] != float64(4) || body["idempotencyKey"] != "castrelyx-test-1" {
				t.Errorf("bad job payload: %v", body)
			}
			_, _ = w.Write([]byte(`{"id":"job-1","status":"accepted"}`))
		default:
			w.WriteHeader(404)
			_, _ = w.Write([]byte(`{"error":"unknown"}`))
		}
	}))
	t.Cleanup(server.Close)
	dir := t.TempDir()
	credentials := filepath.Join(dir, "credentials.json")
	if err := os.WriteFile(credentials, []byte(`{"username":"agent","password":"private-password"}`), 0600); err != nil {
		t.Fatal(err)
	}
	c := &Client{cfg: Config{URL: server.URL, RouterID: "router-25", CredentialsFile: credentials, StateDir: dir}, http: server.Client()}
	c.restart = func(context.Context, string) error { return nil }
	return c, calls
}
func TestReplayUsesDurableResultAndDoesNotSubmitAgain(t *testing.T) {
	c, calls := fixture(t)
	body := []byte(`{"action":"apply","routerId":"router-25","expectedRevision":4,"kind":"port.admin","parameters":{"port":"eth2","state":"down"}}`)
	for i := 0; i < 2; i++ {
		v, err := c.Execute(context.Background(), "test-1", body)
		if err != nil || v["id"] != "job-1" {
			t.Fatalf("%v %v", v, err)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("duplicate application")
	}
	restarted := &Client{cfg: c.cfg, http: c.http, restart: c.restart}
	if _, err := restarted.Execute(context.Background(), "test-1", body); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("restarted process submitted twice")
	}
	if _, err := os.Stat(filepath.Join(c.cfg.StateDir, "backup-test-1.json")); err != nil {
		t.Fatal("missing pre-change backup")
	}
}

func TestConfirmedApplyAndSettlementUseTypedRouterAPI(t *testing.T) {
	c, _ := fixture(t)
	calls := []string{}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/login":
			_ = json.NewEncoder(w).Encode(map[string]string{"token": "private-token"})
		case "/api/v1/state":
			_ = json.NewEncoder(w).Encode(map[string]any{"config": map[string]any{"device": map[string]string{"id": "router-25"}}})
		case "/api/v1/jobs":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["confirmWithinSeconds"] != float64(120) {
				t.Errorf("confirmation deadline lost: %v", body)
			}
			calls = append(calls, "apply")
			_ = json.NewEncoder(w).Encode(map[string]string{"id": strings.Repeat("a", 32), "status": "accepted"})
		default:
			if r.Method != "POST" || !(strings.HasSuffix(r.URL.Path, "/confirm") || strings.HasSuffix(r.URL.Path, "/rollback")) {
				t.Errorf("unexpected request %s", r.URL.Path)
			}
			calls = append(calls, r.URL.Path)
			_ = json.NewEncoder(w).Encode(map[string]string{"status": "committed"})
		}
	}))
	defer server.Close()
	c.cfg.URL = server.URL
	c.http = server.Client()
	body := []byte(`{"action":"apply","routerId":"router-25","expectedRevision":4,"kind":"port.admin","parameters":{"port":"eth1","state":"down"},"confirmWithinSeconds":120}`)
	if _, err := c.Execute(context.Background(), "confirm-apply", body); err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"confirm", "rollback"} {
		payload, _ := json.Marshal(map[string]string{"action": action, "routerId": "router-25", "jobId": strings.Repeat("a", 32)})
		if _, err := c.Execute(context.Background(), action, payload); err != nil {
			t.Fatal(err)
		}
		if _, err := c.Execute(context.Background(), action, payload); err != nil {
			t.Fatal(err)
		}
	}
	if len(calls) != 3 {
		t.Fatal("replay submitted a duplicate mutation", calls)
	}
}
func TestRejectWrongIdentityMissingRevisionAndInjection(t *testing.T) {
	c, calls := fixture(t)
	for i, body := range []string{
		`{"action":"apply","routerId":"other","expectedRevision":4,"kind":"config"}`,
		`{"action":"apply","routerId":"router-25","kind":"config"}`,
		`{"action":"restart","routerId":"router-25","service":"switchd; reboot"}`,
		`{"action":"state","routerId":"router-25","url":"https://evil.test"}`,
		`{"action":"restore","routerId":"router-25","expectedRevision":4,"backupId":"../../credentials"}`,
	} {
		if _, err := c.Execute(context.Background(), strings.Repeat("x", i+1), []byte(body)); err == nil {
			t.Fatalf("accepted %s", body)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid request reached write API")
	}
}
func TestRestartInterruptedIsNotRepeated(t *testing.T) {
	c, _ := fixture(t)
	payload := []byte(`{"action":"restart","routerId":"router-25","service":"switchd"}`)
	count := 0
	c.restart = func(context.Context, string) error { count++; return nil }
	_, err := c.Execute(context.Background(), "restart-1", payload)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(c.cfg.StateDir, "task-restart-1.json")
	raw, _ := os.ReadFile(path)
	var journal outcome
	_ = json.Unmarshal(raw, &journal)
	journal.Started = true
	_ = save(path, journal)
	if _, err = c.Execute(context.Background(), "restart-1", payload); err == nil {
		t.Fatal("interrupted restart accepted")
	}
	if count != 1 {
		t.Fatal("restart repeated")
	}
}
func TestTaskContentAndTLSValidation(t *testing.T) {
	c, _ := fixture(t)
	_, err := c.Execute(context.Background(), "same", []byte(`{"action":"state","routerId":"router-25"}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = c.Execute(context.Background(), "same", []byte(`{"action":"jobs","routerId":"router-25"}`)); err == nil {
		t.Fatal("different task content accepted")
	}
	for _, url := range []string{"http://localhost", "https://user:pass@localhost", "https://localhost/api", "https://localhost?q=1"} {
		if _, err := New(Config{Enabled: true, URL: url}); err == nil {
			t.Fatalf("accepted origin %s", url)
		}
	}
	c.http = &http.Client{}
	c.token = ""
	if _, err := c.Execute(context.Background(), "tls", []byte(`{"action":"state","routerId":"router-25"}`)); err == nil {
		t.Fatal("untrusted TLS accepted")
	}
}
