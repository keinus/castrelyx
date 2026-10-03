package updater

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"
)

func TestPinnedBundleAllowsBothRotationKeysAndRejectsRemovedKey(t *testing.T) {
	var bundle []byte
	var privateKeys []ed25519.PrivateKey
	var blocks [][]byte
	for i := 0; i < 3; i++ {
		public, private, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		der, err := x509.MarshalPKIXPublicKey(public)
		if err != nil {
			t.Fatal(err)
		}
		block := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})
		blocks = append(blocks, block)
		privateKeys = append(privateKeys, private)
		if i < 2 {
			bundle = append(bundle, block...)
		}
	}
	path := filepath.Join(t.TempDir(), "pinned-keys.pem")
	if err := os.WriteFile(path, bundle, 0600); err != nil {
		t.Fatal(err)
	}
	u := Updater{config: Config{PublicKeyPath: path}}
	manifest := `{"version":"2","os":"` + runtimeOS() + `","arch":"` + runtimeArch() + `","channel":"stable","size_bytes":1,"sha256":"` + sha256Hex([]byte{1}) + `","signing_key_id":"test-id","signing_key_version":2,"signing_algorithm":"Ed25519"}`
	verify := func(private ed25519.PrivateKey) error {
		_, err := u.verifyManifest(CheckResponse{Release: Release{Version: "2", Channel: "stable"}, Manifest: manifest,
			Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(private, []byte(manifest)))})
		return err
	}
	if err := verify(privateKeys[0]); err != nil {
		t.Fatal(err)
	}
	if err := verify(privateKeys[1]); err != nil {
		t.Fatal(err)
	}
	if err := verify(privateKeys[2]); err == nil {
		t.Fatal("untrusted signer accepted")
	}
	if err := os.WriteFile(path, blocks[1], 0600); err != nil {
		t.Fatal(err)
	}
	if err := verify(privateKeys[0]); err == nil {
		t.Fatal("removed signer accepted")
	}
	if err := verify(privateKeys[1]); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, append(blocks[1], []byte("corrupt trailing bytes")...), 0600); err != nil {
		t.Fatal(err)
	}
	if err := verify(privateKeys[1]); err == nil {
		t.Fatal("malformed bundle accepted")
	}
}
