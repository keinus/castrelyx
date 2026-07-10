package envelope

import (
	"strings"
	"testing"
)

func TestRedactPayloadRemovesSecretLikeFields(t *testing.T) {
	payload := map[string]any{
		"username": "alice",
		"token":    "abc",
		"nested": map[string]any{
			"Authorization": "Bearer abc",
			"path":          "/usr/bin/thing",
		},
		"items": []any{
			map[string]any{"api_key": "secret-key"},
			"plain",
		},
	}

	redacted := Redact(payload).(map[string]any)
	if redacted["username"] != "alice" {
		t.Fatalf("username was changed: %#v", redacted["username"])
	}
	if redacted["token"] != RedactedValue {
		t.Fatalf("token was not redacted: %#v", redacted["token"])
	}
	nested := redacted["nested"].(map[string]any)
	if nested["Authorization"] != RedactedValue {
		t.Fatalf("Authorization was not redacted: %#v", nested["Authorization"])
	}
	items := redacted["items"].([]any)
	first := items[0].(map[string]any)
	if first["api_key"] != RedactedValue {
		t.Fatalf("api_key was not redacted: %#v", first["api_key"])
	}
}

func TestBatchEnvelopeKeepsStableSourceMetadata(t *testing.T) {
	batch := NewBatch("agent", "agent-01", "default")
	batch.Add(Item{
		Kind: "metric",
		Type: "cpu",
		Key:  "cpu.total",
		Payload: map[string]any{
			"value": 10,
		},
	})

	if batch.SchemaVersion != SchemaVersion {
		t.Fatalf("SchemaVersion = %q", batch.SchemaVersion)
	}
	if batch.Source != "agent" || batch.SourceID != "agent-01" || batch.TenantID != "default" {
		t.Fatalf("unexpected source metadata: %#v", batch)
	}
	if len(batch.Items) != 1 {
		t.Fatalf("Items length = %d", len(batch.Items))
	}
}

func TestBatchAssignsStableItemIDsAndSequences(t *testing.T) {
	batch := NewBatch("agent", "agent-01", "tenant-01")
	if batch.BatchID == "" {
		t.Fatal("BatchID is empty")
	}
	if batch.ChunkIndex != 0 || batch.ChunkCount != 1 {
		t.Fatalf("unexpected initial chunk metadata: index=%d count=%d", batch.ChunkIndex, batch.ChunkCount)
	}

	batch.Add(Item{Kind: "event", Type: "test", Key: "first", Payload: map[string]any{"token": "secret"}})
	batch.Add(Item{ItemID: "caller-supplied", Kind: "event", Type: "test", Key: "second"})

	if batch.Items[0].Sequence != 0 || batch.Items[0].ItemID != batch.BatchID+":0" {
		t.Fatalf("unexpected first item identity: %#v", batch.Items[0])
	}
	if batch.Items[1].Sequence != 1 || batch.Items[1].ItemID != "caller-supplied" {
		t.Fatalf("unexpected second item identity: %#v", batch.Items[1])
	}
	if batch.Items[0].Payload.(map[string]any)["token"] != RedactedValue {
		t.Fatalf("item payload was not redacted: %#v", batch.Items[0].Payload)
	}
}

func TestWithItemsPreservesBatchAndItemIdentityAndCopiesSlice(t *testing.T) {
	batch := NewBatch("agent", "agent-01", "default")
	for _, key := range []string{"first", "second", "third"} {
		batch.Add(Item{Kind: "event", Type: "test", Key: key})
	}
	originalBatchID := batch.BatchID
	originalItemID := batch.Items[1].ItemID
	selected := batch.Items[1:]

	chunk := batch.WithItems(selected, 1, 2)
	selected[0].ItemID = "mutated-after-copy"

	if chunk.BatchID != originalBatchID || chunk.ChunkIndex != 1 || chunk.ChunkCount != 2 {
		t.Fatalf("unexpected chunk metadata: %#v", chunk)
	}
	if len(chunk.Items) != 2 || chunk.Items[0].ItemID != originalItemID || chunk.Items[0].Sequence != 1 {
		t.Fatalf("chunk item identity changed: %#v", chunk.Items)
	}
	if batch.Items[0].ItemID != originalBatchID+":0" {
		t.Fatalf("source batch identity changed: %#v", batch.Items[0])
	}
}

func TestNewBatchIDsAreNonEmptyAndUnique(t *testing.T) {
	seen := map[string]struct{}{}
	for i := 0; i < 128; i++ {
		id := NewBatch("agent", "agent-01", "default").BatchID
		if strings.TrimSpace(id) == "" {
			t.Fatal("generated empty batch ID")
		}
		if _, duplicate := seen[id]; duplicate {
			t.Fatalf("duplicate batch ID %q", id)
		}
		seen[id] = struct{}{}
	}
}
