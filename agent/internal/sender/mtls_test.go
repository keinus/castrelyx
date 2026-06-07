package sender

import (
  "compress/gzip"
  "context"
  "crypto/ecdsa"
  "crypto/elliptic"
  "crypto/rand"
  "crypto/tls"
  "crypto/x509"
  "crypto/x509/pkix"
  "encoding/json"
  "encoding/pem"
  "math/big"
  "net"
  "net/http"
  "net/http/httptest"
  "testing"
  "time"

  "castrelyx/agent/internal/envelope"
)

func TestMTLSSenderPostsGzipBatchWithoutBearerToken(t *testing.T) {
  caCert, caKey := testCA(t)
  serverCert := testSignedCert(t, caCert, caKey, "127.0.0.1", x509.ExtKeyUsageServerAuth)
  clientCert := testSignedCert(t, caCert, caKey, "agent-01", x509.ExtKeyUsageClientAuth)
  pool := x509.NewCertPool()
  pool.AddCert(caCert)

  var sawAuth string
  var sawBatch envelope.Batch
  server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    sawAuth = r.Header.Get("Authorization")
    if len(r.TLS.PeerCertificates) != 1 {
      t.Fatalf("peer certificates = %d", len(r.TLS.PeerCertificates))
    }
    gz, err := gzip.NewReader(r.Body)
    if err != nil {
      t.Fatalf("gzip body: %v", err)
    }
    defer gz.Close()
    if err := json.NewDecoder(gz).Decode(&sawBatch); err != nil {
      t.Fatalf("decode batch: %v", err)
    }
    w.WriteHeader(http.StatusAccepted)
  }))
  server.TLS = &tls.Config{
    Certificates: []tls.Certificate{serverCert},
    ClientAuth:   tls.RequireAndVerifyClientCert,
    ClientCAs:    pool,
    MinVersion:   tls.VersionTLS12,
  }
  server.StartTLS()
  defer server.Close()

  clientTLS := &tls.Config{
    Certificates: []tls.Certificate{clientCert},
    RootCAs:      pool,
    ServerName:   "127.0.0.1",
    MinVersion:   tls.VersionTLS12,
  }
  client, err := New(server.URL, clientTLS)
  if err != nil {
    t.Fatal(err)
  }
  batch := envelope.NewBatch("agent", "agent-01", "default")
  batch.Add(envelope.Item{Kind: "event", Type: "health", Key: "heartbeat", Payload: map[string]any{"ok": true}})

  if err := client.Send(context.Background(), batch); err != nil {
    t.Fatalf("Send returned error: %v", err)
  }
  if sawAuth != "" {
    t.Fatalf("Authorization header must be empty, got %q", sawAuth)
  }
  if sawBatch.SourceID != "agent-01" || len(sawBatch.Items) != 1 {
    t.Fatalf("unexpected batch: %#v", sawBatch)
  }
}

func TestNewRejectsPlaintextEndpoint(t *testing.T) {
  if _, err := New("http://manager.local/api/ingest", &tls.Config{}); err == nil {
    t.Fatal("New returned nil error for plaintext endpoint")
  }
}

func testCA(t *testing.T) (*x509.Certificate, *ecdsa.PrivateKey) {
  t.Helper()
  key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
  if err != nil {
    t.Fatal(err)
  }
  tmpl := &x509.Certificate{
    SerialNumber:          big.NewInt(100),
    Subject:               pkix.Name{CommonName: "test-ca"},
    NotBefore:             time.Now().Add(-time.Hour),
    NotAfter:              time.Now().Add(365 * 24 * time.Hour),
    KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
    BasicConstraintsValid: true,
    IsCA:                  true,
  }
  der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
  if err != nil {
    t.Fatal(err)
  }
  cert, err := x509.ParseCertificate(der)
  if err != nil {
    t.Fatal(err)
  }
  return cert, key
}

func testSignedCert(t *testing.T, ca *x509.Certificate, caKey *ecdsa.PrivateKey, commonName string, usage x509.ExtKeyUsage) tls.Certificate {
  t.Helper()
  key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
  if err != nil {
    t.Fatal(err)
  }
  tmpl := &x509.Certificate{
    SerialNumber: big.NewInt(time.Now().UnixNano()),
    Subject:      pkix.Name{CommonName: commonName},
    NotBefore:    time.Now().Add(-time.Hour),
    NotAfter:     time.Now().Add(24 * time.Hour),
    KeyUsage:     x509.KeyUsageDigitalSignature,
    ExtKeyUsage:  []x509.ExtKeyUsage{usage},
  }
  if ip := net.ParseIP(commonName); ip != nil {
    tmpl.IPAddresses = []net.IP{ip}
  } else {
    tmpl.DNSNames = []string{commonName}
  }
  der, err := x509.CreateCertificate(rand.Reader, tmpl, ca, &key.PublicKey, caKey)
  if err != nil {
    t.Fatal(err)
  }
  keyBytes, err := x509.MarshalECPrivateKey(key)
  if err != nil {
    t.Fatal(err)
  }
  certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
  keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyBytes})
  cert, err := tls.X509KeyPair(certPEM, keyPEM)
  if err != nil {
    t.Fatal(err)
  }
  return cert
}
