package tlsidentity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestEnsurePrivateKeyCreatesECDSAP256Key(t *testing.T) {
	paths := PathsFromDir(t.TempDir())

	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatalf("EnsurePrivateKey returned error: %v", err)
	}
	if key.Curve != elliptic.P256() {
		t.Fatalf("unexpected curve: %v", key.Curve)
	}

	info, err := os.Stat(paths.ClientKeyPath)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("key file permissions = %v", info.Mode().Perm())
	}
}

func TestCreateCSRIncludesAgentIdentity(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	csrPEM, err := CreateCSR(key, CSRInfo{
		AgentID:  "agent-01",
		Hostname: "host-01",
		Version:  "test",
	})
	if err != nil {
		t.Fatalf("CreateCSR returned error: %v", err)
	}

	block, _ := pem.Decode(csrPEM)
	if block == nil {
		t.Fatal("CSR PEM did not decode")
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	if csr.Subject.CommonName != "agent-01" {
		t.Fatalf("CSR CommonName = %q", csr.Subject.CommonName)
	}
	if len(csr.DNSNames) != 1 || csr.DNSNames[0] != "host-01" {
		t.Fatalf("CSR DNSNames = %#v", csr.DNSNames)
	}
}

func TestCertificateStatusDetectsNearExpiry(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	caPEM, certPEM := makeTestCertificate(t, key, time.Now().Add(48*time.Hour))
	if err := SaveCertificates(paths, caPEM, certPEM); err != nil {
		t.Fatal(err)
	}

	status, err := CertificateStatus(paths, 7*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if !status.Exists || !status.NeedsRenewal {
		t.Fatalf("status = %#v", status)
	}
}

func TestBuildTLSConfigLoadsClientCertificateAndCA(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	caPEM, certPEM := makeTestCertificate(t, key, time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, caPEM, certPEM); err != nil {
		t.Fatal(err)
	}

	cfg, err := BuildTLSConfig(paths, "manager.local")
	if err != nil {
		t.Fatalf("BuildTLSConfig returned error: %v", err)
	}
	if cfg.ServerName != "manager.local" {
		t.Fatalf("ServerName = %q", cfg.ServerName)
	}
	if len(cfg.Certificates) != 1 {
		t.Fatalf("Certificates length = %d", len(cfg.Certificates))
	}
	if cfg.RootCAs == nil {
		t.Fatal("RootCAs is nil")
	}
}

func TestBuildRootTLSConfigLoadsCAWithoutClientCertificate(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	caPEM, _ := makeTestCertificate(t, key, time.Now().Add(30*24*time.Hour))
	if err := os.WriteFile(paths.CACertPath, caPEM, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := BuildRootTLSConfig(paths, "manager.local")
	if err != nil {
		t.Fatalf("BuildRootTLSConfig returned error: %v", err)
	}
	if cfg.ServerName != "manager.local" {
		t.Fatalf("ServerName = %q", cfg.ServerName)
	}
	if len(cfg.Certificates) != 0 {
		t.Fatalf("Certificates length = %d", len(cfg.Certificates))
	}
	if cfg.RootCAs == nil {
		t.Fatal("RootCAs is nil")
	}
}

func makeTestCertificate(t *testing.T, key *ecdsa.PrivateKey, notAfter time.Time) ([]byte, []byte) {
	t.Helper()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}

	certTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "agent-01"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	certDER, err := x509.CreateCertificate(rand.Reader, certTemplate, caTemplate, &key.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	return caPEM, certPEM
}

func TestPathsFromDirUsesExpectedFilenames(t *testing.T) {
	dir := t.TempDir()
	paths := PathsFromDir(dir)
	if paths.CACertPath != filepath.Join(dir, "ca.pem") {
		t.Fatalf("CACertPath = %q", paths.CACertPath)
	}
	if paths.ClientCertPath != filepath.Join(dir, "client.pem") {
		t.Fatalf("ClientCertPath = %q", paths.ClientCertPath)
	}
	if paths.ClientKeyPath != filepath.Join(dir, "client.key") {
		t.Fatalf("ClientKeyPath = %q", paths.ClientKeyPath)
	}
}
