package remotetasks

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const testPublicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCtest castrelyx-test"

func TestAuthorizedKeyLineIncludesRestrictionsAndMarker(t *testing.T) {
	expiresAt := time.Date(2026, 7, 5, 1, 2, 3, 0, time.UTC)
	line := authorizedKeyLine(testPublicKey, "session-1", expiresAt, "192.0.2.10")

	required := []string{
		`from="192.0.2.10"`,
		"no-agent-forwarding",
		"no-X11-forwarding",
		"no-port-forwarding",
		testPublicKey,
		"castrelyx-session=session-1",
		"expires=2026-07-05T01:02:03Z",
	}
	for _, value := range required {
		if !strings.Contains(line, value) {
			t.Fatalf("authorized key line missing %q: %s", value, line)
		}
	}
}

func TestUpsertCleanupAndRemoveSessionKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "authorized_keys")
	expired := authorizedKeyLine(testPublicKey, "expired", time.Now().UTC().Add(-time.Minute), "")
	active := authorizedKeyLine(testPublicKey, "active", time.Now().UTC().Add(time.Hour), "")
	if err := os.WriteFile(path, []byte(expired+"\n"+active+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	removedExpired, err := cleanupExpiredKeys(path)
	if err != nil {
		t.Fatal(err)
	}
	if removedExpired != 1 {
		t.Fatalf("expected one expired key removed, got %d", removedExpired)
	}

	replacement := authorizedKeyLine(testPublicKey, "active", time.Now().UTC().Add(2*time.Hour), "198.51.100.7")
	added, err := upsertSessionKey(path, "active", replacement)
	if err != nil {
		t.Fatal(err)
	}
	if !added {
		t.Fatal("replacement should be reported as added after removing prior session marker")
	}

	lines, err := readLines(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(lines) != 1 || !strings.Contains(lines[0], `from="198.51.100.7"`) {
		t.Fatalf("expected only replacement key, got %#v", lines)
	}

	removed, err := removeSessionKey(path, "active")
	if err != nil {
		t.Fatal(err)
	}
	if !removed {
		t.Fatal("expected active session key to be removed")
	}
	lines, err = readLines(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(lines) != 0 {
		t.Fatalf("expected authorized_keys to be empty, got %#v", lines)
	}
}
