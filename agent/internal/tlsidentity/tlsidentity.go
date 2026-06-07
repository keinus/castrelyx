package tlsidentity

import (
  "crypto/ecdsa"
  "crypto/elliptic"
  "crypto/rand"
  "crypto/tls"
  "crypto/x509"
  "crypto/x509/pkix"
  "encoding/json"
  "encoding/pem"
  "errors"
  "fmt"
  "os"
  "path/filepath"
  "runtime"
  "time"
)

type Paths struct {
  CertDir        string
  CACertPath     string
  ClientCertPath string
  ClientKeyPath  string
  MetadataPath   string
}

type CSRInfo struct {
  AgentID  string
  Hostname string
  Version  string
}

type CertificateStatusInfo struct {
  Exists       bool
  ExpiresAt    time.Time
  NeedsRenewal bool
}

type Metadata struct {
  AgentID   string    `json:"agent_id"`
  IngestURL string    `json:"ingest_url"`
  ExpiresAt time.Time `json:"expires_at"`
}

func PathsFromDir(dir string) Paths {
  return Paths{
    CertDir:        dir,
    CACertPath:     filepath.Join(dir, "ca.pem"),
    ClientCertPath: filepath.Join(dir, "client.pem"),
    ClientKeyPath:  filepath.Join(dir, "client.key"),
    MetadataPath:   filepath.Join(dir, "enrollment.json"),
  }
}

func EnsurePrivateKey(paths Paths) (*ecdsa.PrivateKey, error) {
  if err := os.MkdirAll(paths.CertDir, 0o700); err != nil {
    return nil, err
  }
  if data, err := os.ReadFile(paths.ClientKeyPath); err == nil {
    return parsePrivateKey(data)
  } else if !errors.Is(err, os.ErrNotExist) {
    return nil, err
  }

  key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
  if err != nil {
    return nil, err
  }
  der, err := x509.MarshalECPrivateKey(key)
  if err != nil {
    return nil, err
  }
  data := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})
  if err := os.WriteFile(paths.ClientKeyPath, data, 0o600); err != nil {
    return nil, err
  }
  return key, nil
}

func CreateCSR(key *ecdsa.PrivateKey, info CSRInfo) ([]byte, error) {
  if info.AgentID == "" {
    return nil, errors.New("agent id is required")
  }
  template := &x509.CertificateRequest{
    Subject: pkix.Name{
      CommonName:         info.AgentID,
      Organization:       []string{"Castrelyx"},
      OrganizationalUnit: []string{"agent", runtime.GOOS, runtime.GOARCH, info.Version},
    },
  }
  if info.Hostname != "" {
    template.DNSNames = []string{info.Hostname}
  }
  der, err := x509.CreateCertificateRequest(rand.Reader, template, key)
  if err != nil {
    return nil, err
  }
  return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der}), nil
}

func SaveCertificates(paths Paths, caPEM, clientPEM []byte) error {
  if len(caPEM) == 0 {
    return errors.New("ca certificate is empty")
  }
  if len(clientPEM) == 0 {
    return errors.New("client certificate is empty")
  }
  if err := os.MkdirAll(paths.CertDir, 0o700); err != nil {
    return err
  }
  if err := os.WriteFile(paths.CACertPath, caPEM, 0o600); err != nil {
    return err
  }
  return os.WriteFile(paths.ClientCertPath, clientPEM, 0o600)
}

func SaveMetadata(paths Paths, metadata Metadata) error {
  if err := os.MkdirAll(paths.CertDir, 0o700); err != nil {
    return err
  }
  data, err := json.MarshalIndent(metadata, "", "  ")
  if err != nil {
    return err
  }
  return os.WriteFile(paths.MetadataPath, append(data, '\n'), 0o600)
}

func LoadMetadata(paths Paths) (Metadata, error) {
  data, err := os.ReadFile(paths.MetadataPath)
  if err != nil {
    return Metadata{}, err
  }
  var metadata Metadata
  if err := json.Unmarshal(data, &metadata); err != nil {
    return Metadata{}, err
  }
  return metadata, nil
}

func CertificateStatus(paths Paths, renewBefore time.Duration) (CertificateStatusInfo, error) {
  data, err := os.ReadFile(paths.ClientCertPath)
  if errors.Is(err, os.ErrNotExist) {
    return CertificateStatusInfo{NeedsRenewal: true}, nil
  }
  if err != nil {
    return CertificateStatusInfo{}, err
  }
  block, _ := pem.Decode(data)
  if block == nil {
    return CertificateStatusInfo{Exists: true, NeedsRenewal: true}, nil
  }
  cert, err := x509.ParseCertificate(block.Bytes)
  if err != nil {
    return CertificateStatusInfo{}, err
  }
  now := time.Now()
  needsRenewal := now.After(cert.NotAfter) || time.Until(cert.NotAfter) <= renewBefore
  return CertificateStatusInfo{
    Exists:       true,
    ExpiresAt:    cert.NotAfter,
    NeedsRenewal: needsRenewal,
  }, nil
}

func BuildTLSConfig(paths Paths, serverName string) (*tls.Config, error) {
  cert, err := tls.LoadX509KeyPair(paths.ClientCertPath, paths.ClientKeyPath)
  if err != nil {
    return nil, fmt.Errorf("load client certificate: %w", err)
  }
  pool, err := loadCAPool(paths)
  if err != nil {
    return nil, err
  }
  return &tls.Config{
    MinVersion:   tls.VersionTLS12,
    ServerName:   serverName,
    Certificates: []tls.Certificate{cert},
    RootCAs:      pool,
  }, nil
}

func BuildRootTLSConfig(paths Paths, serverName string) (*tls.Config, error) {
  pool, err := loadCAPool(paths)
  if err != nil {
    return nil, err
  }
  return &tls.Config{
    MinVersion: tls.VersionTLS12,
    ServerName: serverName,
    RootCAs:    pool,
  }, nil
}

func loadCAPool(paths Paths) (*x509.CertPool, error) {
  caData, err := os.ReadFile(paths.CACertPath)
  if err != nil {
    return nil, fmt.Errorf("read ca certificate: %w", err)
  }
  pool := x509.NewCertPool()
  if !pool.AppendCertsFromPEM(caData) {
    return nil, errors.New("failed to parse ca certificate")
  }
  return pool, nil
}

func parsePrivateKey(data []byte) (*ecdsa.PrivateKey, error) {
  block, _ := pem.Decode(data)
  if block == nil {
    return nil, errors.New("private key PEM did not decode")
  }
  key, err := x509.ParseECPrivateKey(block.Bytes)
  if err != nil {
    return nil, err
  }
  if key.Curve != elliptic.P256() {
    return nil, errors.New("private key must use P-256 curve")
  }
  return key, nil
}
