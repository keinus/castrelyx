package tlsidentity

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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

func TestEnsurePrivateKeyConcurrentBootstrapUsesOnePersistedKey(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	const workers = 12
	start := make(chan struct{})
	keys := make(chan *ecdsa.PrivateKey, workers)
	errorsCh := make(chan error, workers)
	var wait sync.WaitGroup
	for i := 0; i < workers; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			key, err := EnsurePrivateKey(paths)
			if err != nil {
				errorsCh <- err
				return
			}
			keys <- key
		}()
	}
	close(start)
	wait.Wait()
	close(keys)
	close(errorsCh)
	for err := range errorsCh {
		t.Fatal(err)
	}
	var expected []byte
	count := 0
	for key := range keys {
		count++
		encoded := key.D.Bytes()
		if expected == nil {
			expected = append([]byte(nil), encoded...)
			continue
		}
		if !bytes.Equal(encoded, expected) {
			t.Fatal("concurrent bootstrap returned different private keys")
		}
	}
	if count != workers {
		t.Fatalf("key count = %d, want %d", count, workers)
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

func TestBuildRootTLSConfigRejectsAppendedTrustRoot(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	first := newTestCA(t)
	second := newTestCA(t)
	bundle := append(append([]byte(nil), first.pem...), second.pem...)
	if err := os.WriteFile(paths.CACertPath, bundle, 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := BuildRootTLSConfig(paths, "manager.local"); err == nil || !strings.Contains(err.Error(), "exactly one") {
		t.Fatalf("appended root error = %v", err)
	}
}

func TestValidateClientIdentityRequiresExplicitClientAuthUsage(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClientWithUsages(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour), nil)
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}

	if _, err := ValidateClientIdentity(paths, "agent-01", false); err == nil || !strings.Contains(err.Error(), "clientAuth") {
		t.Fatalf("missing clientAuth EKU error = %v", err)
	}
}

func TestValidateClientIdentityRejectsLeafBeyondRootHorizon(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClient(t, ca, key, "agent-01", ca.cert.NotAfter.Add(time.Hour))
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}

	if _, err := ValidateClientIdentity(paths, "agent-01", false); err == nil || !strings.Contains(err.Error(), "root CA validity horizon") {
		t.Fatalf("root horizon error = %v", err)
	}
}

func TestValidateIdentityPathsRejectsDerivedArtifactCollision(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	paths.ClientCertPath = enrollmentStagePath(paths.CACertPath)
	if err := validateIdentityPaths(paths); err == nil || !strings.Contains(err.Error(), "path collision") {
		t.Fatalf("derived path collision error = %v", err)
	}
}

func TestIdentityTransactionNamespaceFollowsSharedClientTarget(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	alternate := paths
	alternate.CertDir = t.TempDir()
	if enrollmentMarkerPath(paths) != enrollmentMarkerPath(alternate) {
		t.Fatal("shared client certificate target produced different transaction markers")
	}
	if identityResourceLockPath(paths.ClientCertPath) != identityResourceLockPath(alternate.ClientCertPath) {
		t.Fatal("shared client certificate target produced different resource locks")
	}
}

func TestSaveEnrollmentCommitsOnlyValidatedBundle(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}
	expiresAt := certificateExpiry(t, clientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: expiresAt}); err != nil {
		t.Fatal(err)
	}

	commit, err := SaveEnrollment(paths, ca.pem, clientPEM, "agent-01", Metadata{
		AgentID:   "agent-01",
		IngestURL: "https://new.example/api/agent/ingest",
		ExpiresAt: expiresAt,
	})
	if err != nil || !commit.Committed || !commit.Changed {
		t.Fatalf("SaveEnrollment result=%+v err=%v", commit, err)
	}
	metadata, err := LoadMetadata(paths)
	if err != nil {
		t.Fatal(err)
	}
	if metadata.IngestURL != "https://new.example/api/agent/ingest" {
		t.Fatalf("metadata ingest URL = %q", metadata.IngestURL)
	}
	if _, err := ValidateClientIdentity(paths, "agent-01", false); err != nil {
		t.Fatalf("committed identity did not validate: %v", err)
	}
	if _, err := os.Stat(enrollmentMarkerPath(paths)); !os.IsNotExist(err) {
		t.Fatalf("transaction marker remains after commit: %v", err)
	}
}

func TestSaveEnrollmentRejectsMismatchedPrivateKeyWithoutChangingLiveIdentity(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	oldClientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, oldClientPEM); err != nil {
		t.Fatal(err)
	}
	oldExpiry := certificateExpiry(t, oldClientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: oldExpiry}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)

	otherKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	replacement := issueTestClient(t, ca, otherKey, "agent-01", time.Now().Add(60*24*time.Hour))
	commit, err := SaveEnrollment(paths, ca.pem, replacement, "agent-01", Metadata{
		AgentID: "agent-01", IngestURL: "https://new.example/ingest", ExpiresAt: certificateExpiry(t, replacement),
	})
	if err == nil || commit.Committed {
		t.Fatalf("mismatched key result=%+v err=%v", commit, err)
	}
	assertIdentitySnapshot(t, paths, before)
}

func TestSaveEnrollmentRollsBackWhenCommitIsInterrupted(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	oldClientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, oldClientPEM); err != nil {
		t.Fatal(err)
	}
	oldExpiry := certificateExpiry(t, oldClientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: oldExpiry}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)
	replacement := issueTestClient(t, ca, key, "agent-01", time.Now().Add(60*24*time.Hour))

	commitCalls := 0
	injectedCommit := func(temporaryPath, targetPath string) error {
		commitCalls++
		if commitCalls == 2 {
			return errors.New("injected second-file commit failure")
		}
		return replaceDurableFile(temporaryPath, targetPath)
	}
	commit, err := saveEnrollment(paths, ca.pem, replacement, "agent-01", Metadata{
		AgentID: "agent-01", IngestURL: "https://new.example/ingest", ExpiresAt: certificateExpiry(t, replacement),
	}, injectedCommit)
	if err == nil || commit.Committed {
		t.Fatalf("interrupted commit result=%+v err=%v", commit, err)
	}
	assertIdentitySnapshot(t, paths, before)
	if _, err := os.Stat(enrollmentMarkerPath(paths)); !os.IsNotExist(err) {
		t.Fatalf("transaction marker remains after rollback: %v", err)
	}
}

func TestSaveEnrollmentRejectsImplicitCARotation(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	oldCA := newTestCA(t)
	oldClientPEM := issueTestClient(t, oldCA, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, oldCA.pem, oldClientPEM); err != nil {
		t.Fatal(err)
	}
	oldExpiry := certificateExpiry(t, oldClientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: oldExpiry}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)

	newCA := newTestCA(t)
	newClientPEM := issueTestClient(t, newCA, key, "agent-01", time.Now().Add(60*24*time.Hour))
	commit, err := SaveEnrollment(paths, newCA.pem, newClientPEM, "agent-01", Metadata{
		AgentID: "agent-01", IngestURL: "https://new.example/ingest", ExpiresAt: certificateExpiry(t, newClientPEM),
	})
	if err == nil || commit.Committed || !strings.Contains(err.Error(), "explicit trust migration") {
		t.Fatalf("CA rotation result=%+v err=%v", commit, err)
	}
	assertIdentitySnapshot(t, paths, before)
}

func TestSaveEnrollmentRejectsAppendedTrustRoot(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}
	expiresAt := certificateExpiry(t, clientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: expiresAt}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)
	additionalCA := newTestCA(t)
	appendedBundle := append(append([]byte(nil), ca.pem...), additionalCA.pem...)

	commit, err := SaveEnrollment(paths, appendedBundle, clientPEM, "agent-01", Metadata{
		AgentID: "agent-01", IngestURL: "https://new.example/ingest", ExpiresAt: expiresAt,
	})
	if err == nil || commit.Committed || !strings.Contains(err.Error(), "exactly one") {
		t.Fatalf("appended CA result=%+v err=%v", commit, err)
	}
	assertIdentitySnapshot(t, paths, before)
}

func TestSaveEnrollmentRejectsExpiryMismatchWithoutChangingLiveIdentity(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}
	expiresAt := certificateExpiry(t, clientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: expiresAt}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)

	commit, err := SaveEnrollment(paths, ca.pem, clientPEM, "agent-01", Metadata{
		AgentID: "agent-01", IngestURL: "https://new.example/ingest", ExpiresAt: expiresAt.Add(time.Hour),
	})
	if err == nil || commit.Committed {
		t.Fatalf("expiry mismatch result=%+v err=%v", commit, err)
	}
	assertIdentitySnapshot(t, paths, before)
}

func TestRecoverEnrollmentRestoresMarkedPartialCommit(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	key, err := EnsurePrivateKey(paths)
	if err != nil {
		t.Fatal(err)
	}
	ca := newTestCA(t)
	clientPEM := issueTestClient(t, ca, key, "agent-01", time.Now().Add(30*24*time.Hour))
	if err := SaveCertificates(paths, ca.pem, clientPEM); err != nil {
		t.Fatal(err)
	}
	expiresAt := certificateExpiry(t, clientPEM)
	if err := SaveMetadata(paths, Metadata{AgentID: "agent-01", IngestURL: "https://old.example/ingest", ExpiresAt: expiresAt}); err != nil {
		t.Fatal(err)
	}
	before := readIdentitySnapshot(t, paths)
	for _, target := range enrollmentTargets(paths) {
		if err := prepareEnrollmentBackup(target); err != nil {
			t.Fatal(err)
		}
	}
	if err := writeRestrictedAtomically(enrollmentMarkerPath(paths), []byte("version=1\nstate=committing\n")); err != nil {
		t.Fatal(err)
	}
	if err := writeRestrictedAtomically(paths.CACertPath, []byte("partial-ca")); err != nil {
		t.Fatal(err)
	}
	if err := writeRestrictedAtomically(paths.ClientCertPath, []byte("partial-client")); err != nil {
		t.Fatal(err)
	}
	if err := writeRestrictedAtomically(paths.MetadataPath, []byte("partial-metadata")); err != nil {
		t.Fatal(err)
	}

	if err := RecoverEnrollment(paths); err != nil {
		t.Fatalf("RecoverEnrollment returned error: %v", err)
	}
	assertIdentitySnapshot(t, paths, before)
	if _, err := os.Stat(enrollmentMarkerPath(paths)); !os.IsNotExist(err) {
		t.Fatalf("transaction marker remains after recovery: %v", err)
	}
}

func TestRecoverEnrollmentClassifiesUnmarkedCleanupFailureAsNonTransactional(t *testing.T) {
	paths := PathsFromDir(t.TempDir())
	artifact := enrollmentStagePath(paths.CACertPath)
	if err := os.MkdirAll(artifact, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(artifact, "locked"), []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}

	err := RecoverEnrollment(paths)
	if !errors.Is(err, ErrEnrollmentArtifactCleanup) {
		t.Fatalf("RecoverEnrollment error = %v", err)
	}
}

type testCertificateAuthority struct {
	key  *ecdsa.PrivateKey
	cert *x509.Certificate
	pem  []byte
}

func newTestCA(t *testing.T) testCertificateAuthority {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: "test-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return testCertificateAuthority{key: key, cert: certificate, pem: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})}
}

func issueTestClient(t *testing.T, ca testCertificateAuthority, key *ecdsa.PrivateKey, agentID string, notAfter time.Time) []byte {
	return issueTestClientWithUsages(t, ca, key, agentID, notAfter, []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth})
}

func issueTestClientWithUsages(t *testing.T, ca testCertificateAuthority, key *ecdsa.PrivateKey, agentID string, notAfter time.Time, usages []x509.ExtKeyUsage) []byte {
	t.Helper()
	template := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: agentID},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  usages,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}

func certificateExpiry(t *testing.T, certificatePEM []byte) time.Time {
	t.Helper()
	block, _ := pem.Decode(certificatePEM)
	if block == nil {
		t.Fatal("certificate PEM did not decode")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return certificate.NotAfter
}

func readIdentitySnapshot(t *testing.T, paths Paths) map[string][]byte {
	t.Helper()
	result := make(map[string][]byte, 3)
	for _, path := range enrollmentTargets(paths) {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		result[path] = data
	}
	return result
}

func assertIdentitySnapshot(t *testing.T, paths Paths, expected map[string][]byte) {
	t.Helper()
	for _, path := range enrollmentTargets(paths) {
		actual, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(actual, expected[path]) {
			t.Fatalf("identity material changed at %s", path)
		}
	}
}

func makeTestCertificate(t *testing.T, key *ecdsa.PrivateKey, notAfter time.Time) ([]byte, []byte) {
	t.Helper()
	ca := newTestCA(t)
	return ca.pem, issueTestClient(t, ca, key, "agent-01", notAfter)
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
