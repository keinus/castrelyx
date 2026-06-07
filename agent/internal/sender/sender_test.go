package sender

import (
  "context"
  "crypto/tls"
  "net/http"
  "net/http/httptest"
  "testing"

  "castrelyx/agent/internal/envelope"
)

func TestSenderReturnsErrorForNonSuccessStatus(t *testing.T) {
  server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    w.WriteHeader(http.StatusInternalServerError)
  }))
  defer server.Close()

  tlsConfig := server.Client().Transport.(*http.Transport).TLSClientConfig
  client, err := New(server.URL, tlsConfig.Clone())
  if err != nil {
    t.Fatal(err)
  }
  batch := envelope.NewBatch("agent", "agent-01", "default")

  if err := client.Send(context.Background(), batch); err == nil {
    t.Fatal("Send returned nil error for 500 response")
  }
}

func TestNewRejectsMissingTLSConfig(t *testing.T) {
  if _, err := New("https://manager.local/api/ingest", nil); err == nil {
    t.Fatal("New returned nil error for missing tls config")
  }
}

func TestClassifyTransportErrorDetectsTLSFailure(t *testing.T) {
  err := classifyTransportError(tls.RecordHeaderError{})
  if err == nil {
    t.Fatal("classifyTransportError returned nil")
  }
}
