package enrollment

import (
  "context"
  "encoding/json"
  "net/http"
  "net/http/httptest"
  "testing"
)

func TestEnrollPostsCSRWithEnrollmentToken(t *testing.T) {
  var sawAuth string
  var sawRequest Request
  var server *httptest.Server

  server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    sawAuth = r.Header.Get("Authorization")
    if r.URL.Path != "/api/agent/enroll" {
      t.Fatalf("path = %q", r.URL.Path)
    }
    if err := json.NewDecoder(r.Body).Decode(&sawRequest); err != nil {
      t.Fatalf("decode request: %v", err)
    }
    _ = json.NewEncoder(w).Encode(Response{
      AgentID:       "agent-01",
      CACertPEM:     "ca",
      ClientCertPEM: "cert",
      ExpiresAt:     "2030-01-01T00:00:00Z",
      IngestURL:     server.URL + "/api/agent/ingest",
    })
  }))
  defer server.Close()

  client := New(server.URL, "enroll-token", "", server.Client().Transport)
  resp, err := client.Enroll(context.Background(), Request{
    AgentID:  "agent-01",
    Hostname: "host-01",
    Version:  "test",
    CSRPem:   "csr",
  })
  if err != nil {
    t.Fatalf("Enroll returned error: %v", err)
  }
  if sawAuth != "Bearer enroll-token" {
    t.Fatalf("Authorization = %q", sawAuth)
  }
  if sawRequest.CSRPem != "csr" || resp.AgentID != "agent-01" {
    t.Fatalf("unexpected request/response: %#v %#v", sawRequest, resp)
  }
}

func TestEnrollReturnsTypedErrorForUnauthorized(t *testing.T) {
  server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    w.WriteHeader(http.StatusUnauthorized)
  }))
  defer server.Close()

  client := New(server.URL, "bad-token", "", server.Client().Transport)
  _, err := client.Enroll(context.Background(), Request{AgentID: "agent-01", CSRPem: "csr"})
  if err == nil {
    t.Fatal("Enroll returned nil error")
  }
  enrollErr, ok := err.(*Error)
  if !ok || enrollErr.StatusCode != http.StatusUnauthorized {
    t.Fatalf("unexpected error: %#v", err)
  }
}
