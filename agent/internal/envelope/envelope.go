package envelope

import (
  "strings"
  "time"
)

const (
  SchemaVersion = "1.0"
  RedactedValue = "[REDACTED]"
)

type Batch struct {
  SchemaVersion string    `json:"schema_version"`
  Source        string    `json:"source"`
  SourceID      string    `json:"source_id"`
  TenantID      string    `json:"tenant_id"`
  ObservedAt    time.Time `json:"observed_at"`
  SentAt        time.Time `json:"sent_at"`
  Items         []Item    `json:"items"`
}

type Item struct {
  Kind    string `json:"kind"`
  Type    string `json:"type"`
  Key     string `json:"key"`
  Payload any    `json:"payload"`
}

func NewBatch(source, sourceID, tenantID string) Batch {
  now := time.Now().UTC()
  return Batch{
    SchemaVersion: SchemaVersion,
    Source:        source,
    SourceID:      sourceID,
    TenantID:      tenantID,
    ObservedAt:    now,
    SentAt:        now,
    Items:         []Item{},
  }
}

func (b *Batch) Add(item Item) {
  item.Payload = Redact(item.Payload)
  b.Items = append(b.Items, item)
}

func Redact(value any) any {
  switch v := value.(type) {
  case map[string]any:
    out := make(map[string]any, len(v))
    for key, child := range v {
      if isSensitiveKey(key) {
        out[key] = RedactedValue
        continue
      }
      out[key] = Redact(child)
    }
    return out
  case []any:
    out := make([]any, len(v))
    for i := range v {
      out[i] = Redact(v[i])
    }
    return out
  default:
    return value
  }
}

func isSensitiveKey(key string) bool {
  normalized := strings.ToLower(strings.ReplaceAll(key, "-", "_"))
  if normalized == "key" || normalized == "api_key" || normalized == "apikey" {
    return true
  }
  sensitive := []string{"token", "password", "secret", "authorization", "credential"}
  for _, needle := range sensitive {
    if strings.Contains(normalized, needle) {
      return true
    }
  }
  return false
}
