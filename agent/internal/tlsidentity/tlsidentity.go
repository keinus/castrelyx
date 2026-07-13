package tlsidentity

import (
	"bytes"
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
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

const enrollmentTransactionMarkerSuffix = ".identity-enrollment-update"
const enrollmentStageSuffix = ".identity-stage"
const enrollmentBackupSuffix = ".identity-backup"
const enrollmentMissingSuffix = ".identity-missing"

var enrollmentTransactionMu sync.Mutex
var privateKeyMu sync.Mutex

var ErrEnrollmentArtifactCleanup = errors.New("stale identity transaction artifact cleanup failed")

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

func EnsurePrivateKey(paths Paths) (key *ecdsa.PrivateKey, returnErr error) {
	privateKeyMu.Lock()
	defer privateKeyMu.Unlock()
	if err := validateIdentityPaths(paths); err != nil {
		return nil, err
	}
	unlock, err := acquireIdentityTransactionLock(identityKeyLockPath(paths))
	if err != nil {
		return nil, fmt.Errorf("lock client private key: %w", err)
	}
	defer func() {
		if err := unlock(); err != nil {
			returnErr = errors.Join(returnErr, fmt.Errorf("unlock client private key: %w", err))
		}
	}()
	return ensurePrivateKeyLocked(paths)
}

func ensurePrivateKeyLocked(paths Paths) (*ecdsa.PrivateKey, error) {
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
	if err := writeRestrictedAtomically(paths.ClientKeyPath, data); err != nil {
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
	if err := writeRestrictedAtomically(paths.CACertPath, caPEM); err != nil {
		return err
	}
	return writeRestrictedAtomically(paths.ClientCertPath, clientPEM)
}

func SaveMetadata(paths Paths, metadata Metadata) error {
	if err := os.MkdirAll(paths.CertDir, 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		return err
	}
	return writeRestrictedAtomically(paths.MetadataPath, append(data, '\n'))
}

type EnrollmentCommitResult struct {
	Committed bool
	Changed   bool
}

// SaveEnrollment validates and commits a CA, client certificate, and metadata
// bundle as one recoverable transaction. Committed remains true when the
// identity is durable but cleanup of restricted backup files needs retry.
func SaveEnrollment(paths Paths, caPEM, clientPEM []byte, expectedAgentID string, metadata Metadata) (EnrollmentCommitResult, error) {
	return saveEnrollment(paths, caPEM, clientPEM, expectedAgentID, metadata, replaceDurableFile)
}

func saveEnrollment(paths Paths, caPEM, clientPEM []byte, expectedAgentID string, metadata Metadata, commitFile func(string, string) error) (result EnrollmentCommitResult, returnErr error) {
	enrollmentTransactionMu.Lock()
	defer enrollmentTransactionMu.Unlock()

	if err := validateIdentityPaths(paths); err != nil {
		return EnrollmentCommitResult{}, err
	}
	unlock, err := acquireIdentityBundleLocks(paths)
	if err != nil {
		return EnrollmentCommitResult{}, fmt.Errorf("lock identity transaction: %w", err)
	}
	defer func() {
		if err := unlock(); err != nil {
			returnErr = errors.Join(returnErr, fmt.Errorf("unlock identity transaction: %w", err))
		}
	}()
	if err := recoverEnrollmentLocked(paths); err != nil {
		return EnrollmentCommitResult{}, err
	}
	if len(caPEM) == 0 {
		return EnrollmentCommitResult{}, errors.New("ca certificate is empty")
	}
	if len(clientPEM) == 0 {
		return EnrollmentCommitResult{}, errors.New("client certificate is empty")
	}
	if err := validateMetadata(metadata, false); err != nil {
		return EnrollmentCommitResult{}, err
	}
	if metadata.AgentID != expectedAgentID {
		return EnrollmentCommitResult{}, fmt.Errorf("identity metadata agent id %q does not match configured agent id %q", metadata.AgentID, expectedAgentID)
	}
	if err := validateNoImplicitCARotation(paths.CACertPath, caPEM); err != nil {
		return EnrollmentCommitResult{}, err
	}
	metadataData, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		return EnrollmentCommitResult{}, err
	}
	metadataData = append(metadataData, '\n')

	materials := enrollmentMaterials(paths, caPEM, clientPEM, metadataData)
	for _, material := range materials {
		if err := writeRestrictedAtomically(material.stage, material.data); err != nil {
			_ = cleanupEnrollmentArtifactsLocked(paths)
			return EnrollmentCommitResult{}, fmt.Errorf("stage identity material %s: %w", filepath.Base(material.target), err)
		}
	}
	stagedPaths := Paths{
		CertDir:        paths.CertDir,
		CACertPath:     enrollmentStagePath(paths.CACertPath),
		ClientCertPath: enrollmentStagePath(paths.ClientCertPath),
		ClientKeyPath:  paths.ClientKeyPath,
		MetadataPath:   enrollmentStagePath(paths.MetadataPath),
	}
	status, err := ValidateClientIdentity(stagedPaths, metadata.AgentID, false)
	if err != nil {
		_ = cleanupEnrollmentArtifactsLocked(paths)
		return EnrollmentCommitResult{}, fmt.Errorf("validate staged client identity: %w", err)
	}
	if err := ValidateEnrollmentMetadata(metadata, expectedAgentID, status.ExpiresAt, false); err != nil {
		_ = cleanupEnrollmentArtifactsLocked(paths)
		return EnrollmentCommitResult{}, err
	}
	changed, err := enrollmentMaterialChanged(materials)
	if err != nil {
		_ = cleanupEnrollmentArtifactsLocked(paths)
		return EnrollmentCommitResult{}, err
	}
	if !changed {
		if err := cleanupEnrollmentArtifactsLocked(paths); err != nil {
			return EnrollmentCommitResult{Committed: true}, fmt.Errorf("validated identity unchanged but staging cleanup failed: %w", err)
		}
		return EnrollmentCommitResult{Committed: true}, nil
	}

	for _, material := range materials {
		if err := prepareEnrollmentBackup(material.target); err != nil {
			_ = cleanupEnrollmentArtifactsLocked(paths)
			return EnrollmentCommitResult{}, fmt.Errorf("backup identity material %s: %w", filepath.Base(material.target), err)
		}
	}
	marker := enrollmentMarkerPath(paths)
	if err := writeRestrictedAtomically(marker, []byte("version=1\nstate=committing\n")); err != nil {
		_ = cleanupEnrollmentArtifactsLocked(paths)
		return EnrollmentCommitResult{}, fmt.Errorf("persist identity transaction marker: %w", err)
	}

	rollback := func(commitErr error) (EnrollmentCommitResult, error) {
		restoreErr := restoreEnrollmentLocked(paths)
		return EnrollmentCommitResult{}, errors.Join(commitErr, restoreErr)
	}
	for _, material := range materials {
		if err := commitFile(material.stage, material.target); err != nil {
			return rollback(fmt.Errorf("commit identity material %s: %w", filepath.Base(material.target), err))
		}
	}
	finalStatus, err := ValidateClientIdentity(paths, metadata.AgentID, false)
	if err != nil {
		return rollback(fmt.Errorf("validate committed client identity: %w", err))
	}
	if delta := absoluteDuration(finalStatus.ExpiresAt.Sub(metadata.ExpiresAt)); delta > 2*time.Second {
		return rollback(errors.New("committed client certificate expiry does not match metadata"))
	}
	loadedMetadata, err := LoadMetadata(paths)
	if err != nil {
		return rollback(fmt.Errorf("validate committed identity metadata: %w", err))
	}
	if loadedMetadata.AgentID != metadata.AgentID || loadedMetadata.IngestURL != metadata.IngestURL || !loadedMetadata.ExpiresAt.Equal(metadata.ExpiresAt) {
		return rollback(errors.New("committed identity metadata does not match staged metadata"))
	}
	if err := removeDurable(marker); err != nil {
		return rollback(fmt.Errorf("finalize identity transaction: %w", err))
	}
	if err := cleanupEnrollmentArtifactsLocked(paths); err != nil {
		return EnrollmentCommitResult{Committed: true, Changed: true}, fmt.Errorf("identity committed but transaction artifact cleanup failed: %w", err)
	}
	return EnrollmentCommitResult{Committed: true, Changed: true}, nil
}

// RecoverEnrollment rolls an interrupted marked transaction back to its last
// complete identity. Unmarked staging artifacts never replaced live files and
// are removed.
func RecoverEnrollment(paths Paths) (returnErr error) {
	enrollmentTransactionMu.Lock()
	defer enrollmentTransactionMu.Unlock()
	if err := validateIdentityPaths(paths); err != nil {
		return err
	}
	unlock, err := acquireIdentityBundleLocks(paths)
	if err != nil {
		return fmt.Errorf("lock identity transaction: %w", err)
	}
	defer func() {
		if err := unlock(); err != nil {
			returnErr = errors.Join(returnErr, fmt.Errorf("unlock identity transaction: %w", err))
		}
	}()
	return recoverEnrollmentLocked(paths)
}

// ValidateClientIdentity verifies the key pair, configured agent identity,
// clientAuth usage, and CA chain. allowExpired only changes the verification
// time; it never disables CA or identity verification.
func ValidateClientIdentity(paths Paths, agentID string, allowExpired bool) (CertificateStatusInfo, error) {
	if strings.TrimSpace(agentID) == "" {
		return CertificateStatusInfo{}, errors.New("agent id is required")
	}
	caPEM, err := os.ReadFile(paths.CACertPath)
	if err != nil {
		return CertificateStatusInfo{}, fmt.Errorf("read ca certificate: %w", err)
	}
	root, err := parseSingleRootCA(caPEM)
	if err != nil {
		return CertificateStatusInfo{}, fmt.Errorf("validate ca certificate: %w", err)
	}
	tlsConfig, err := BuildTLSConfig(paths, "")
	if err != nil {
		return CertificateStatusInfo{}, err
	}
	if len(tlsConfig.Certificates) != 1 || len(tlsConfig.Certificates[0].Certificate) == 0 {
		return CertificateStatusInfo{}, errors.New("client certificate chain is empty")
	}
	leaf, err := x509.ParseCertificate(tlsConfig.Certificates[0].Certificate[0])
	if err != nil {
		return CertificateStatusInfo{}, fmt.Errorf("parse client certificate: %w", err)
	}
	if leaf.Subject.CommonName != agentID {
		return CertificateStatusInfo{}, fmt.Errorf("client certificate common name %q does not match agent id %q", leaf.Subject.CommonName, agentID)
	}
	if leaf.IsCA {
		return CertificateStatusInfo{}, errors.New("client certificate must not be a CA certificate")
	}
	publicKey, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || publicKey.Curve != elliptic.P256() {
		return CertificateStatusInfo{}, errors.New("client certificate must use an ECDSA P-256 public key")
	}
	if leaf.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
		return CertificateStatusInfo{}, errors.New("client certificate must permit digital signatures")
	}
	hasClientAuth := false
	for _, usage := range leaf.ExtKeyUsage {
		if usage == x509.ExtKeyUsageClientAuth {
			hasClientAuth = true
			break
		}
	}
	if !hasClientAuth {
		return CertificateStatusInfo{}, errors.New("client certificate must explicitly include the clientAuth extended key usage")
	}
	if !leaf.NotAfter.After(leaf.NotBefore) {
		return CertificateStatusInfo{}, errors.New("client certificate validity interval is invalid")
	}
	if leaf.NotBefore.Before(root.NotBefore) || leaf.NotAfter.After(root.NotAfter) {
		return CertificateStatusInfo{}, errors.New("client certificate validity must remain within the root CA validity horizon")
	}
	intermediates := x509.NewCertPool()
	for _, raw := range tlsConfig.Certificates[0].Certificate[1:] {
		certificate, err := x509.ParseCertificate(raw)
		if err != nil {
			return CertificateStatusInfo{}, fmt.Errorf("parse client certificate chain: %w", err)
		}
		intermediates.AddCert(certificate)
	}
	verificationTime := time.Now()
	if allowExpired && (verificationTime.Before(leaf.NotBefore) || verificationTime.After(leaf.NotAfter)) {
		verificationTime = leaf.NotBefore.Add(leaf.NotAfter.Sub(leaf.NotBefore) / 2)
	}
	if _, err := leaf.Verify(x509.VerifyOptions{
		Roots:         tlsConfig.RootCAs,
		Intermediates: intermediates,
		CurrentTime:   verificationTime,
		KeyUsages:     []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}); err != nil {
		return CertificateStatusInfo{}, fmt.Errorf("verify client certificate chain: %w", err)
	}
	now := time.Now()
	return CertificateStatusInfo{
		Exists:       true,
		ExpiresAt:    leaf.NotAfter,
		NeedsRenewal: now.After(leaf.NotAfter),
	}, nil
}

type enrollmentMaterial struct {
	target string
	stage  string
	data   []byte
}

func enrollmentMaterials(paths Paths, caPEM, clientPEM, metadataData []byte) []enrollmentMaterial {
	return []enrollmentMaterial{
		{target: paths.CACertPath, stage: enrollmentStagePath(paths.CACertPath), data: caPEM},
		{target: paths.ClientCertPath, stage: enrollmentStagePath(paths.ClientCertPath), data: clientPEM},
		{target: paths.MetadataPath, stage: enrollmentStagePath(paths.MetadataPath), data: metadataData},
	}
}

func enrollmentMaterialChanged(materials []enrollmentMaterial) (bool, error) {
	for _, material := range materials {
		current, err := os.ReadFile(material.target)
		if errors.Is(err, os.ErrNotExist) {
			return true, nil
		}
		if err != nil {
			return false, err
		}
		if !bytes.Equal(current, material.data) {
			return true, nil
		}
	}
	return false, nil
}

func enrollmentTargets(paths Paths) []string {
	return []string{paths.CACertPath, paths.ClientCertPath, paths.MetadataPath}
}

func enrollmentStagePath(target string) string {
	return filepath.Join(filepath.Dir(target), "."+filepath.Base(target)+enrollmentStageSuffix)
}

func enrollmentBackupPath(target string) string {
	return filepath.Join(filepath.Dir(target), "."+filepath.Base(target)+enrollmentBackupSuffix)
}

func enrollmentMissingPath(target string) string {
	return filepath.Join(filepath.Dir(target), "."+filepath.Base(target)+enrollmentMissingSuffix)
}

func enrollmentMarkerPath(paths Paths) string {
	return filepath.Join(filepath.Dir(paths.ClientCertPath), "."+filepath.Base(paths.ClientCertPath)+enrollmentTransactionMarkerSuffix)
}

func enrollmentLockPath(paths Paths) string {
	return filepath.Join(paths.CertDir, ".identity.lock")
}

func identityKeyLockPath(paths Paths) string {
	return identityResourceLockPath(paths.ClientKeyPath)
}

func identityResourceLockPath(target string) string {
	return filepath.Join(filepath.Dir(target), "."+filepath.Base(target)+".identity-resource.lock")
}

func acquireIdentityBundleLocks(paths Paths) (func() error, error) {
	lockPaths := []string{enrollmentLockPath(paths)}
	for _, target := range []string{paths.CACertPath, paths.ClientCertPath, paths.ClientKeyPath, paths.MetadataPath} {
		lockPaths = append(lockPaths, identityResourceLockPath(target))
	}
	sort.Slice(lockPaths, func(left, right int) bool {
		return canonicalIdentityPath(lockPaths[left]) < canonicalIdentityPath(lockPaths[right])
	})
	unique := lockPaths[:0]
	for _, path := range lockPaths {
		if len(unique) == 0 || canonicalIdentityPath(unique[len(unique)-1]) != canonicalIdentityPath(path) {
			unique = append(unique, path)
		}
	}

	var unlockers []func() error
	for _, path := range unique {
		unlock, err := acquireIdentityTransactionLock(path)
		if err != nil {
			var releaseErr error
			for index := len(unlockers) - 1; index >= 0; index-- {
				releaseErr = errors.Join(releaseErr, unlockers[index]())
			}
			return nil, errors.Join(fmt.Errorf("lock identity resource %s: %w", path, err), releaseErr)
		}
		unlockers = append(unlockers, unlock)
	}
	return func() error {
		var unlockErr error
		for index := len(unlockers) - 1; index >= 0; index-- {
			unlockErr = errors.Join(unlockErr, unlockers[index]())
		}
		return unlockErr
	}, nil
}

func canonicalIdentityPath(path string) string {
	canonical, err := filepath.Abs(filepath.Clean(path))
	if err != nil {
		canonical = filepath.Clean(path)
	}
	if runtime.GOOS == "windows" {
		canonical = strings.ToLower(canonical)
	}
	return canonical
}

func validateIdentityPaths(paths Paths) error {
	if strings.TrimSpace(paths.CertDir) == "" {
		return errors.New("certificate directory is required")
	}
	required := []struct {
		name string
		path string
	}{
		{name: "ca certificate", path: paths.CACertPath},
		{name: "client certificate", path: paths.ClientCertPath},
		{name: "client key", path: paths.ClientKeyPath},
		{name: "metadata", path: paths.MetadataPath},
	}
	for _, candidate := range required {
		name, path := candidate.name, candidate.path
		if strings.TrimSpace(path) == "" {
			return fmt.Errorf("%s path is required", name)
		}
	}

	type namedPath struct {
		name string
		path string
	}
	candidates := []namedPath{{name: "certificate directory", path: paths.CertDir}}
	for _, candidate := range required {
		candidates = append(candidates, namedPath(candidate))
	}
	marker := enrollmentMarkerPath(paths)
	candidates = append(candidates,
		namedPath{name: "transaction marker", path: marker},
		namedPath{name: "transaction lock", path: enrollmentLockPath(paths)},
		namedPath{name: "transaction marker delete tombstone", path: durableDeleteTombstone(marker)},
	)
	for _, candidate := range required {
		candidates = append(candidates, namedPath{name: candidate.name + " resource lock", path: identityResourceLockPath(candidate.path)})
	}
	for _, target := range enrollmentTargets(paths) {
		artifacts := []namedPath{
			{name: "identity stage", path: enrollmentStagePath(target)},
			{name: "identity backup", path: enrollmentBackupPath(target)},
			{name: "identity missing marker", path: enrollmentMissingPath(target)},
			{name: "identity delete tombstone", path: durableDeleteTombstone(target)},
		}
		for _, artifact := range artifacts[:3] {
			artifacts = append(artifacts, namedPath{name: artifact.name + " delete tombstone", path: durableDeleteTombstone(artifact.path)})
		}
		candidates = append(candidates, artifacts...)
	}

	seen := make(map[string]string, len(candidates))
	for _, candidate := range candidates {
		canonical := canonicalIdentityPath(candidate.path)
		if previous, exists := seen[canonical]; exists {
			return fmt.Errorf("identity path collision between %s and %s: %s", previous, candidate.name, candidate.path)
		}
		seen[canonical] = candidate.name
	}
	return nil
}

func ValidateEnrollmentMetadata(metadata Metadata, expectedAgentID string, certificateExpiresAt time.Time, allowExpired bool) error {
	if strings.TrimSpace(expectedAgentID) == "" {
		return errors.New("configured agent id is required")
	}
	if err := validateMetadata(metadata, allowExpired); err != nil {
		return err
	}
	if metadata.AgentID != expectedAgentID {
		return fmt.Errorf("identity metadata agent id %q does not match configured agent id %q", metadata.AgentID, expectedAgentID)
	}
	if certificateExpiresAt.IsZero() || absoluteDuration(certificateExpiresAt.Sub(metadata.ExpiresAt)) > 2*time.Second {
		return fmt.Errorf("metadata expiry %s does not match client certificate expiry %s", metadata.ExpiresAt.UTC().Format(time.RFC3339), certificateExpiresAt.UTC().Format(time.RFC3339))
	}
	return nil
}

func validateMetadata(metadata Metadata, allowExpired bool) error {
	if strings.TrimSpace(metadata.AgentID) == "" {
		return errors.New("identity metadata agent id is required")
	}
	parsed, err := url.Parse(metadata.IngestURL)
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Host == "" {
		return errors.New("identity metadata ingest URL must be an absolute HTTPS URL")
	}
	if !allowExpired && !metadata.ExpiresAt.After(time.Now()) {
		return errors.New("identity metadata expiry must be in the future")
	}
	return nil
}

func validateNoImplicitCARotation(existingPath string, replacementPEM []byte) error {
	existingPEM, err := os.ReadFile(existingPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	existing, err := parseSingleRootCA(existingPEM)
	if err != nil {
		return fmt.Errorf("parse existing CA certificate: %w", err)
	}
	replacement, err := parseSingleRootCA(replacementPEM)
	if err != nil {
		return fmt.Errorf("parse replacement CA certificate: %w", err)
	}
	if !bytes.Equal(existing.Raw, replacement.Raw) {
		return errors.New("CA rotation requires an explicit trust migration")
	}
	return nil
}

func parseCertificateBundle(data []byte) ([]*x509.Certificate, error) {
	var certificates []*x509.Certificate
	remaining := data
	for len(remaining) > 0 {
		block, rest := pem.Decode(remaining)
		if block == nil {
			break
		}
		remaining = rest
		if block.Type != "CERTIFICATE" {
			continue
		}
		certificate, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, err
		}
		certificates = append(certificates, certificate)
	}
	if len(certificates) == 0 {
		return nil, errors.New("certificate PEM did not decode")
	}
	return certificates, nil
}

func parseSingleRootCA(data []byte) (*x509.Certificate, error) {
	certificates, err := parseCertificateBundle(data)
	if err != nil {
		return nil, err
	}
	if len(certificates) != 1 {
		return nil, fmt.Errorf("CA bundle must contain exactly one certificate, found %d", len(certificates))
	}
	root := certificates[0]
	if !root.IsCA || !root.BasicConstraintsValid || root.KeyUsage&x509.KeyUsageCertSign == 0 {
		return nil, errors.New("root certificate does not have valid CA constraints")
	}
	publicKey, ok := root.PublicKey.(*ecdsa.PublicKey)
	if !ok || publicKey.Curve != elliptic.P256() {
		return nil, errors.New("root certificate must use an ECDSA P-256 public key")
	}
	if err := root.CheckSignatureFrom(root); err != nil {
		return nil, fmt.Errorf("root certificate is not self-signed: %w", err)
	}
	return root, nil
}

func prepareEnrollmentBackup(target string) error {
	data, err := os.ReadFile(target)
	if errors.Is(err, os.ErrNotExist) {
		return writeRestrictedAtomically(enrollmentMissingPath(target), []byte("missing\n"))
	}
	if err != nil {
		return err
	}
	return writeRestrictedAtomically(enrollmentBackupPath(target), data)
}

func recoverEnrollmentLocked(paths Paths) error {
	marker := enrollmentMarkerPath(paths)
	if _, err := os.Stat(marker); errors.Is(err, os.ErrNotExist) {
		if cleanupErr := cleanupEnrollmentArtifactsLocked(paths); cleanupErr != nil {
			return errors.Join(ErrEnrollmentArtifactCleanup, cleanupErr)
		}
		return nil
	} else if err != nil {
		return err
	}
	return restoreEnrollmentLocked(paths)
}

func restoreEnrollmentLocked(paths Paths) error {
	for _, target := range enrollmentTargets(paths) {
		backup := enrollmentBackupPath(target)
		missing := enrollmentMissingPath(target)
		data, err := os.ReadFile(backup)
		if err == nil {
			if err := writeRestrictedAtomically(target, data); err != nil {
				return fmt.Errorf("restore identity material %s: %w", filepath.Base(target), err)
			}
			continue
		}
		if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		if _, err := os.Stat(missing); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return fmt.Errorf("identity transaction backup is incomplete for %s", target)
			}
			return err
		}
		if err := removeDurable(target); err != nil {
			return fmt.Errorf("remove partially committed identity material %s: %w", filepath.Base(target), err)
		}
	}
	if err := removeDurable(enrollmentMarkerPath(paths)); err != nil {
		return err
	}
	return cleanupEnrollmentArtifactsLocked(paths)
}

func cleanupEnrollmentArtifactsLocked(paths Paths) error {
	var cleanupErr error
	cleanupTargets := []string{enrollmentMarkerPath(paths)}
	for _, target := range enrollmentTargets(paths) {
		for _, artifact := range []string{enrollmentStagePath(target), enrollmentBackupPath(target), enrollmentMissingPath(target)} {
			cleanupTargets = append(cleanupTargets, artifact)
			if err := removeDurable(artifact); err != nil {
				cleanupErr = errors.Join(cleanupErr, err)
			}
		}
		cleanupTargets = append(cleanupTargets, target)
	}
	for _, target := range cleanupTargets {
		if err := cleanupDurableDelete(target); err != nil {
			cleanupErr = errors.Join(cleanupErr, err)
		}
	}
	return cleanupErr
}

func writeRestrictedAtomically(path string, data []byte) (returnErr error) {
	parent := filepath.Dir(path)
	if err := os.MkdirAll(parent, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(parent, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer func() {
		_ = temporary.Close()
		_ = os.Remove(temporaryPath)
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		return err
	}
	if err := temporary.Sync(); err != nil {
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return replaceDurableFile(temporaryPath, path)
}

func removeDurable(path string) error {
	return removeDurableFile(path)
}

func durableDeleteTombstone(path string) string {
	return filepath.Join(filepath.Dir(path), "."+filepath.Base(path)+".identity-deleted")
}

func absoluteDuration(value time.Duration) time.Duration {
	if value < 0 {
		return -value
	}
	return value
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
	root, err := parseSingleRootCA(caData)
	if err != nil {
		return nil, fmt.Errorf("validate ca certificate: %w", err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(root)
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
