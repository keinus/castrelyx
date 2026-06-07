package envelope

import "testing"

func TestRedactPayloadRemovesSecretLikeFields(t *testing.T) {
  payload := map[string]any{
    "username": "alice",
    "token": "abc",
    "nested": map[string]any{
      "Authorization": "Bearer abc",
      "path": "/usr/bin/thing",
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

  if batch.SchemaVersion != "1.0" {
    t.Fatalf("SchemaVersion = %q", batch.SchemaVersion)
  }
  if batch.Source != "agent" || batch.SourceID != "agent-01" || batch.TenantID != "default" {
    t.Fatalf("unexpected source metadata: %#v", batch)
  }
  if len(batch.Items) != 1 {
    t.Fatalf("Items length = %d", len(batch.Items))
  }
}
