package updater

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestVerifyAcceptsSignedManifestAndMatchingArtifact(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := filepath.Join(dir, "update_public_key.pem")
	der, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(publicKeyPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}

	artifact := []byte("new-agent-binary")
	manifest := `{"version":"0.2.0","os":"` + runtimeOS() + `","arch":"` + runtimeArch() + `","channel":"stable","sha256":"` + sha256Hex(artifact) + `","size_bytes":16}`
	signature := base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, []byte(manifest)))
	u := Updater{config: Config{Version: "0.1.0", Channel: "stable", PublicKeyPath: publicKeyPath}}

	parsed, err := u.verify(CheckResponse{
		Release: Release{Version: "0.2.0", OS: runtimeOS(), Arch: runtimeArch(), Channel: "stable"},
		Manifest: manifest,
		Signature: signature,
	}, artifact)
	if err != nil {
		t.Fatalf("verify returned error: %v", err)
	}
	if parsed.Version != "0.2.0" {
		t.Fatalf("Version = %q", parsed.Version)
	}
}

func TestVerifyRejectsTamperedArtifact(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	publicKeyPath := filepath.Join(dir, "update_public_key.pem")
	der, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(publicKeyPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}

	manifestBytes, err := json.Marshal(Manifest{
		Version: "0.2.0",
		OS: runtimeOS(),
		Arch: runtimeArch(),
		Channel: "stable",
		SHA256: sha256Hex([]byte("expected")),
		SizeBytes: 8,
	})
	if err != nil {
		t.Fatal(err)
	}
	u := Updater{config: Config{Version: "0.1.0", Channel: "stable", PublicKeyPath: publicKeyPath}}
	_, err = u.verify(CheckResponse{
		Release: Release{Version: "0.2.0", OS: runtimeOS(), Arch: runtimeArch(), Channel: "stable"},
		Manifest: string(manifestBytes),
		Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, manifestBytes)),
	}, []byte("tampered"))
	if err == nil {
		t.Fatal("verify returned nil error for tampered artifact")
	}
}

func runtimeOS() string {
	return runtime.GOOS
}

func runtimeArch() string {
	return runtime.GOARCH
}

func sha256Hex(payload []byte) string {
	sum := sha256.Sum256(payload)
	return hex.EncodeToString(sum[:])
}
