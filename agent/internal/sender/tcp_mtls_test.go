package sender

import (
  "bytes"
  "compress/gzip"
  "context"
  "crypto/tls"
  "crypto/x509"
  "encoding/binary"
  "encoding/json"
  "io"
  "testing"
  "time"

  "castrelyx/agent/internal/envelope"
)

func TestTCPMTLSSenderWritesGzipLengthPrefixedBatchAndRequiresAck(t *testing.T) {
  caCert, caKey := testCA(t)
  serverCert := testSignedCert(t, caCert, caKey, "127.0.0.1", x509.ExtKeyUsageServerAuth)
  clientCert := testSignedCert(t, caCert, caKey, "agent-01", x509.ExtKeyUsageClientAuth)
  pool := x509.NewCertPool()
  pool.AddCert(caCert)

  listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
    Certificates: []tls.Certificate{serverCert},
    ClientAuth:   tls.RequireAndVerifyClientCert,
    ClientCAs:    pool,
    MinVersion:   tls.VersionTLS12,
  })
  if err != nil {
    t.Fatal(err)
  }
  defer listener.Close()

  gotBatch := make(chan envelope.Batch, 1)
  go func() {
    conn, err := listener.Accept()
    if err != nil {
      return
    }
    defer conn.Close()
    var lengthBytes [4]byte
    if _, err := io.ReadFull(conn, lengthBytes[:]); err != nil {
      t.Errorf("read length: %v", err)
      return
    }
    payload := make([]byte, int(binary.BigEndian.Uint32(lengthBytes[:])))
    if _, err := io.ReadFull(conn, payload); err != nil {
      t.Errorf("read payload: %v", err)
      return
    }
    gz, err := gzip.NewReader(bytes.NewReader(payload))
    if err != nil {
      t.Errorf("gzip reader: %v", err)
      return
    }
    defer gz.Close()
    var batch envelope.Batch
    if err := json.NewDecoder(gz).Decode(&batch); err != nil {
      t.Errorf("decode batch: %v", err)
      return
    }
    gotBatch <- batch
    _, _ = conn.Write([]byte("{\"status\":\"accepted\"}\n"))
  }()

  clientTLS := &tls.Config{
    Certificates: []tls.Certificate{clientCert},
    RootCAs:      pool,
    ServerName:   "127.0.0.1",
    MinVersion:   tls.VersionTLS12,
  }
  client, err := NewTCPMTLS(listener.Addr().String(), clientTLS)
  if err != nil {
    t.Fatal(err)
  }
  batch := envelope.NewBatch("agent", "agent-01", "default")
  batch.Add(envelope.Item{Kind: "event", Type: "health", Key: "heartbeat", Payload: map[string]any{"ok": true}})

  if err := client.Send(context.Background(), batch); err != nil {
    t.Fatalf("Send returned error: %v", err)
  }

  select {
  case decoded := <-gotBatch:
    if decoded.SourceID != "agent-01" || len(decoded.Items) != 1 {
      t.Fatalf("unexpected batch: %#v", decoded)
    }
  case <-time.After(2 * time.Second):
    t.Fatal("server did not receive batch")
  }
}

func TestTCPMTLSSenderReturnsErrorForNack(t *testing.T) {
  caCert, caKey := testCA(t)
  serverCert := testSignedCert(t, caCert, caKey, "127.0.0.1", x509.ExtKeyUsageServerAuth)
  clientCert := testSignedCert(t, caCert, caKey, "agent-01", x509.ExtKeyUsageClientAuth)
  pool := x509.NewCertPool()
  pool.AddCert(caCert)

  listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
    Certificates: []tls.Certificate{serverCert},
    ClientAuth:   tls.RequireAndVerifyClientCert,
    ClientCAs:    pool,
    MinVersion:   tls.VersionTLS12,
  })
  if err != nil {
    t.Fatal(err)
  }
  defer listener.Close()

  go func() {
    conn, err := listener.Accept()
    if err != nil {
      return
    }
    defer conn.Close()
    var lengthBytes [4]byte
    if _, err := io.ReadFull(conn, lengthBytes[:]); err != nil {
      return
    }
    payload := make([]byte, int(binary.BigEndian.Uint32(lengthBytes[:])))
    if _, err := io.ReadFull(conn, payload); err != nil {
      return
    }
    _, _ = conn.Write([]byte("{\"status\":\"error\",\"code\":\"bad_frame\",\"message\":\"invalid gzip payload\"}\n"))
  }()

  clientTLS := &tls.Config{
    Certificates: []tls.Certificate{clientCert},
    RootCAs:      pool,
    ServerName:   "127.0.0.1",
    MinVersion:   tls.VersionTLS12,
  }
  client, err := NewTCPMTLS(listener.Addr().String(), clientTLS)
  if err != nil {
    t.Fatal(err)
  }

  if err := client.Send(context.Background(), envelope.NewBatch("agent", "agent-01", "default")); err == nil {
    t.Fatal("Send returned nil error for NACK")
  }
}
